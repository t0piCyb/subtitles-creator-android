package com.subtitlecreator.service

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.subtitlecreator.model.Subtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * FFmpeg wrapper: extract 16kHz mono PCM16 WAV (for Whisper) and burn ASS subtitles onto video.
 */
class FFmpegService(private val context: Context) {

    suspend fun extractAudio16kMono(videoIn: File, wavOut: File): File = withContext(Dispatchers.IO) {
        if (wavOut.exists()) wavOut.delete()
        val cmd = "-y -i \"${videoIn.absolutePath}\" -vn -ac 1 -ar 16000 -c:a pcm_s16le \"${wavOut.absolutePath}\""
        val session = FFmpegKit.execute(cmd)
        check(ReturnCode.isSuccess(session.returnCode)) {
            "FFmpeg extract failed rc=${session.returnCode}\n${session.allLogsAsString}"
        }
        Log.i(TAG, "Extracted ${wavOut.length() / 1024} KB of PCM")
        wavOut
    }

    /**
     * Burn subtitles into video using an ASS overlay.
     * Style: Montserrat Bold, yellow, black outline, centred mid-low.
     */
    suspend fun burnSubtitles(
        videoIn: File,
        subtitles: List<Subtitle>,
        videoOut: File,
        fontSize: Int = 72,
        onProgress: (Int) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        if (videoOut.exists()) videoOut.delete()
        val assFile = File(context.cacheDir, "subs_${System.currentTimeMillis()}.ass")
        writeAssFile(assFile, subtitles, fontSize)

        val fontDir = prepareFontDir()
        // Escape ASS filter path: backslash, colon, single quote, brackets.
        val escapedAss = assFile.absolutePath
            .replace("\\", "\\\\")
            .replace(":", "\\:")
            .replace("'", "\\'")
        val cmd = buildString {
            append("-y -i \"${videoIn.absolutePath}\" ")
            append("-vf \"subtitles='$escapedAss':fontsdir='${fontDir.absolutePath}'\" ")
            append("-c:v libx264 -preset ultrafast -crf 23 ")
            append("-c:a copy ")
            append("\"${videoOut.absolutePath}\"")
        }
        Log.i(TAG, "FFmpeg cmd: $cmd")

        val totalDurationMs = subtitles.lastOrNull()?.endMs ?: 1L
        val session = FFmpegKit.execute(cmd)

        if (!ReturnCode.isSuccess(session.returnCode)) {
            val logs = session.allLogsAsString ?: ""
            val tail = logs.takeLast(600)
            Log.e(TAG, "FFmpeg burn FAILED rc=${session.returnCode}")
            Log.e(TAG, "--- ffmpeg tail ---\n$tail\n--- end ---")
            error("FFmpeg rc=${session.returnCode}: ${tail.lines().takeLast(4).joinToString(" | ")}")
        }
        onProgress(100)
        onProgress(100)
        assFile.delete()
        videoOut
    }

    private fun prepareFontDir(): File {
        val fontDir = File(context.filesDir, "fonts").apply { mkdirs() }
        val target = File(fontDir, "Montserrat-Bold.ttf")
        if (!target.exists()) {
            context.assets.open("fonts/Montserrat-Bold.ttf").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        return fontDir
    }

    private fun writeAssFile(out: File, subs: List<Subtitle>, fontSize: Int) {
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write(assHeader(fontSize))
            for (s in subs) {
                val start = formatAssTime(s.startMs)
                val end = formatAssTime(s.endMs)
                val text = s.text.replace("\n", " ").replace("{", "(").replace("}", ")")
                w.write("Dialogue: 0,$start,$end,Default,,0,0,0,,$text\n")
            }
        }
    }

    private fun formatAssTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1000
        val cs = (ms % 1000) / 10
        return "%d:%02d:%02d.%02d".format(h, m, s, cs)
    }

    companion object {
        private const val TAG = "FFmpegService"

        // Yellow text (&H0000FFFF = AABBGGRR), black outline, centered low.
        private fun assHeader(fontSize: Int): String = """
            [Script Info]
            ScriptType: v4.00+
            PlayResX: 1920
            PlayResY: 1080
            WrapStyle: 2
            ScaledBorderAndShadow: yes

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,Montserrat,$fontSize,&H0000FFFF,&H000000FF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,4,1,2,80,80,120,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text

        """.trimIndent() + "\n"
    }
}
