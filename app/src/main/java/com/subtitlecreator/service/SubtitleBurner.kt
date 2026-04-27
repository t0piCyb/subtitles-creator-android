package com.subtitlecreator.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import com.subtitlecreator.model.Subtitle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Burns subtitles into a video using AndroidX Media3 Transformer + a custom
 * [BitmapOverlay] that renders each word with a real [TextPaint] on a
 * correctly-sized bitmap.
 *
 * Why not [androidx.media3.effect.TextOverlay]:
 *   TextOverlay internally builds a [android.text.StaticLayout] with a width
 *   measured from a *default* TextPaint (≈16sp) — even when the span has a
 *   larger AbsoluteSizeSpan. The spanned text is then laid out inside that
 *   tiny frame and wrapped mid-word ("donne" → "donn\ne"). By drawing the
 *   bitmap ourselves we control the paint, measure the width correctly, and
 *   never wrap.
 */
class SubtitleBurner(private val context: Context) {

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
        positionFraction: Float = 0.72f,
        onProgress: (Int) -> Unit = {}
    ): File = withContext(Dispatchers.Main) {
        if (videoOut.exists()) videoOut.delete()

        val overlay = SubtitleBitmapOverlay(subtitles, fontSize, montserrat, positionFraction)
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

        val holder = androidx.media3.transformer.ProgressHolder()
        while (!done.isCompleted) {
            val state = transformer.getProgress(holder)
            if (state == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress.coerceIn(0, 99))
            kotlinx.coroutines.delay(250)
        }
        done.await()
    }

    /**
     * One BitmapOverlay for all subtitles — returns a freshly-rendered bitmap
     * for the currently active word, or a 1×1 transparent placeholder during
     * silences. Bitmaps are cached by text so we don't redraw for every frame.
     */
    @androidx.media3.common.util.UnstableApi
    private class SubtitleBitmapOverlay(
        private val subs: List<Subtitle>,
        private val fontSizePx: Int,
        private val typeface: Typeface,
        private val positionFraction: Float
    ) : BitmapOverlay() {

        // positionFraction = 0 → top of video, 1 → bottom. Media3 anchor Y is NDC (1=top, -1=bottom).
        // We pin the top of the overlay bitmap at the fraction; since the bitmap has a tiny top
        // padding (0.05 × fontSize) to protect the shadow from clipping, the glyph top ends up
        // only ~0.5% of frame height below the user-chosen position — visually indistinguishable
        // from the Compose preview.
        private val overlaySettings = OverlaySettings.Builder()
            .setOverlayFrameAnchor(0f, 1f)
            .setBackgroundFrameAnchor(0f, (1f - 2f * positionFraction).coerceIn(-1f, 1f))
            .build()

        private val emptyBitmap: Bitmap by lazy {
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        private val cache = LinkedHashMap<String, Bitmap>(16, 0.75f, true)
        private val cacheMax = 64

        // Match the subtitles-creator Python project: yellow Montserrat Bold with a
        // soft semi-transparent black drop shadow — no outline around letters.
        private val paint: TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = this@SubtitleBitmapOverlay.typeface
            textSize = fontSizePx.toFloat()
            color = Color.rgb(0xFF, 0xD4, 0x00) // yellow
            setShadowLayer(
                /* radius = */ fontSizePx * 0.10f,
                /* dx     = */ fontSizePx * 0.035f,
                /* dy     = */ fontSizePx * 0.065f,
                /* color  = */ Color.argb(0x80, 0, 0, 0) // 50% black
            )
        }

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val ms = presentationTimeUs / 1000L
            val sub = subs.firstOrNull { ms in it.startMs..it.endMs }
            val text = sub?.text?.trim().orEmpty()
            if (text.isEmpty()) return emptyBitmap

            cache[text]?.let { return it }

            // Horizontal padding accommodates the shadow's dx + blur on both sides.
            // Top padding is deliberately minimal (just enough to avoid clipping the
            // shadow's ≈0.035 × fontSize upward extension) so the glyph top sits flush
            // with the bitmap top — this is what lets the Compose preview and the burned
            // output land at the same on-screen position.
            val paddingX = (fontSizePx * 0.35f).toInt()
            val paddingTop = (fontSizePx * 0.05f).toInt().coerceAtLeast(2)
            val paddingBottom = (fontSizePx * 0.35f).toInt()
            val textWidth = paint.measureText(text).toInt() + paddingX * 2
            val fm = paint.fontMetrics
            val textHeight = (fm.descent - fm.ascent).toInt() + paddingTop + paddingBottom

            val bitmap = Bitmap.createBitmap(textWidth.coerceAtLeast(2), textHeight.coerceAtLeast(2), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val baseline = paddingTop - fm.ascent
            canvas.drawText(text, paddingX.toFloat(), baseline, paint)

            if (cache.size >= cacheMax) cache.entries.iterator().next().let { cache.remove(it.key) }
            cache[text] = bitmap
            return bitmap
        }

        @androidx.media3.common.util.UnstableApi
        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = overlaySettings
    }

    companion object { private const val TAG = "SubtitleBurner" }
}
