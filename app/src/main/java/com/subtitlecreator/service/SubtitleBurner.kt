package com.subtitlecreator.service

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.subtitlecreator.model.Subtitle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.google.common.collect.ImmutableList

/**
 * Burns subtitles into a video using AndroidX Media3 Transformer + TextOverlay.
 *
 * Why not FFmpeg: the current ffmpeg-kit-16kb Maven build is a minimal fork
 * without libx264, libass, or MediaCodec. Media3 Transformer uses the device's
 * hardware H.264 encoder (MediaCodec) so it's actually faster than libx264 on
 * a Snapdragon 8 Gen 2 and has no codec / filter gaps.
 */
class SubtitleBurner(private val context: Context) {

    /** Load Montserrat-Bold.ttf once from assets and reuse for every export. */
    private val montserrat: Typeface by lazy {
        runCatching { Typeface.createFromAsset(context.assets, "fonts/Montserrat-Bold.ttf") }
            .getOrElse {
                Log.w(TAG, "Could not load Montserrat-Bold.ttf, falling back to system bold")
                Typeface.DEFAULT_BOLD
            }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    suspend fun burn(
        videoIn: File,
        subtitles: List<Subtitle>,
        videoOut: File,
        fontSize: Int = 72,
        onProgress: (Int) -> Unit = {}
    ): File = withContext(Dispatchers.Main) {
        if (videoOut.exists()) videoOut.delete()

        val overlay = SubtitleTextOverlay(subtitles, fontSize, montserrat)
        val overlayEffect: Effect = OverlayEffect(ImmutableList.of<androidx.media3.effect.TextureOverlay>(overlay))

        val edited = EditedMediaItem.Builder(MediaItem.fromUri(videoIn.toURI().toString()))
            .setEffects(Effects(emptyList(), listOf(overlayEffect)))
            .build()

        val sequence = EditedMediaItemSequence.Builder(edited).build()
        val composition = Composition.Builder(listOf(sequence)).build()

        val done = CompletableDeferred<File>()

        val transformer = Transformer.Builder(context)
            .setEncoderFactory(DefaultEncoderFactory.Builder(context).build())
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    Log.i(TAG, "Export done: ${videoOut.length() / 1024} KB")
                    onProgress(100)
                    done.complete(videoOut)
                }

                override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                    Log.e(TAG, "Export failed: ${exception.message}", exception)
                    done.completeExceptionally(exception)
                }
            })
            .build()

        transformer.start(composition, videoOut.absolutePath)

        // Poll progress holder while the export runs.
        val holder = androidx.media3.transformer.ProgressHolder()
        while (!done.isCompleted) {
            val state = transformer.getProgress(holder)
            if (state == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress.coerceIn(0, 99))
            kotlinx.coroutines.delay(250)
        }
        done.await()
    }

    /**
     * One TextOverlay for all subtitles — `getText(presentationTimeUs)` returns
     * whichever subtitle is active at that frame, or an empty string (no overlay).
     */
    @androidx.media3.common.util.UnstableApi
    private class SubtitleTextOverlay(
        private val subs: List<Subtitle>,
        private val fontSizePx: Int,
        private val typeface: Typeface
    ) : TextOverlay() {

        private val overlaySettings = OverlaySettings.Builder()
            // Bottom-centre, pushed up by ~15% of the frame.
            .setOverlayFrameAnchor(0f, 1f)
            .setBackgroundFrameAnchor(0f, -0.7f)
            .build()

        override fun getText(presentationTimeUs: Long): SpannableString {
            val ms = presentationTimeUs / 1000L
            val sub = subs.firstOrNull { ms in it.startMs..it.endMs }
            if (sub == null || sub.text.isBlank()) {
                // Media3 TextOverlay.getBitmap() crashes when StaticLayout.width == 0
                // (seen with empty strings AND size-1 spaces). Use a real character with
                // size >= 16 and fully transparent color → bitmap is positive-sized but invisible.
                val placeholder = SpannableString("·")
                placeholder.setSpan(AbsoluteSizeSpan(16), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                placeholder.setSpan(ForegroundColorSpan(Color.TRANSPARENT), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                return placeholder
            }

            val s = SpannableString(sub.text)
            s.setSpan(AbsoluteSizeSpan(fontSizePx), 0, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(ForegroundColorSpan(Color.rgb(0xFF, 0xD4, 0x00)), 0, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            // TypefaceSpan(Typeface) is API 28+ (we require minSdk=28). Montserrat-Bold
            // is already a bold cut — no StyleSpan(BOLD) on top or we'd get faux-bold.
            s.setSpan(TypefaceSpan(typeface), 0, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            return s
        }

        @androidx.media3.common.util.UnstableApi
        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = overlaySettings
    }

    companion object { private const val TAG = "SubtitleBurner" }
}
