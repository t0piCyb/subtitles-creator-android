package com.subtitlecreator.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object FileUtils {

    fun copyUriToAppStorage(context: Context, uri: Uri, fileName: String): File {
        val out = File(context.filesDir, "videos").apply { mkdirs() }.resolve(fileName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: error("Cannot open $uri")
        return out
    }

    data class VideoDimensions(val widthPx: Int, val heightPx: Int)

    /** Read display dimensions (post-rotation) from a video file. */
    fun videoDimensions(file: File): VideoDimensions {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) VideoDimensions(h, w) else VideoDimensions(w, h)
        } catch (t: Throwable) {
            VideoDimensions(1920, 1080)
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun outputVideoFile(context: Context): File {
        val dir = File(context.filesDir, "output").apply { mkdirs() }
        return File(dir, "subtitled_${System.currentTimeMillis()}.mp4")
    }

    /** Save a rendered video to the user's public Movies/SubtitlesCreator/ folder (MediaStore on 29+). */
    fun saveToGallery(context: Context, source: File, displayName: String): Uri? {
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SubtitlesCreator")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "SubtitlesCreator").apply { mkdirs() }
            val dest = File(publicDir, displayName)
            source.copyTo(dest, overwrite = true)
            Uri.fromFile(dest)
        }
    }
}
