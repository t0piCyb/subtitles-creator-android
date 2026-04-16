package com.subtitlecreator.util

import android.content.ContentValues
import android.content.Context
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
