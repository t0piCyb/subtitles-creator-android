package com.subtitlecreator.service

import android.util.Log
import com.subtitlecreator.jni.WhisperLib
import com.subtitlecreator.model.Subtitle
import com.subtitlecreator.model.TranscriptionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Orchestrates model load → PCM decode → whisper.cpp call → post-processing.
 *
 * Word-timing strategy: whisper.cpp is invoked with split_on_word=true + max_len=1 so
 * each returned segment is one word with native timestamps. We then:
 *   1) merge compound fragments (punctuation, leading/trailing quotes) back into their neighbour
 *   2) enforce a minimum visible duration so subtitles don't flash
 */
class WhisperService {

    private var ctxPtr: Long = 0L

    fun ensureLoaded(modelFile: File) {
        if (ctxPtr != 0L) return
        require(modelFile.exists()) { "Model file missing: ${modelFile.absolutePath}" }
        ctxPtr = WhisperLib.init(modelFile.absolutePath)
        Log.i(TAG, "Loaded: ${modelFile.name} (${modelFile.length() / 1024 / 1024} MB)")
    }

    fun release() {
        if (ctxPtr != 0L) {
            WhisperLib.free(ctxPtr)
            ctxPtr = 0L
        }
    }

    suspend fun transcribe(
        wav16kMono: File,
        language: String = "auto",
        nThreads: Int = 6,
        onProgress: (Int) -> Unit = {}
    ): TranscriptionResult = withContext(Dispatchers.Default) {
        check(ctxPtr != 0L) { "Model not loaded — call ensureLoaded() first" }

        val pcm = readWavPcmFloat(wav16kMono)
        Log.i(TAG, "Decoded ${pcm.size} samples (${pcm.size / 16000.0}s)")

        val raw = WhisperLib.transcribe(
            ctxPtr = ctxPtr,
            pcm = pcm,
            language = language,
            nThreads = nThreads,
            translate = false,
            onProgress = onProgress
        )

        // whisper's t0 marks where the word starts — trust it.
        // whisper's t1 often gets stretched to the next event (next word / end of
        // chunk), so the last word before a pause can occupy 5+ seconds of silence.
        // Cap each word's duration to a per-character budget.
        val merged = mergeCompoundFragments(raw)
        val capped = capWordDurations(merged)

        TranscriptionResult(
            language = language,
            subtitles = capped
        )
    }

    /** Decode a 16kHz mono PCM16 WAV file into a float32 array normalized to [-1, 1]. */
    private fun readWavPcmFloat(wav: File): FloatArray {
        RandomAccessFile(wav, "r").use { raf ->
            val header = ByteArray(44)
            raf.readFully(header)

            val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            require(hb.getInt(0) == 0x46464952) { "Not a RIFF/WAV file" }
            val sampleRate = hb.getInt(24)
            val numChannels = hb.getShort(22).toInt()
            val bitsPerSample = hb.getShort(34).toInt()
            require(sampleRate == 16000) { "Expected 16kHz WAV, got ${sampleRate}Hz" }
            require(numChannels == 1) { "Expected mono WAV, got $numChannels channels" }
            require(bitsPerSample == 16) { "Expected PCM16, got $bitsPerSample bps" }

            val dataSize = (raf.length() - 44).toInt()
            val bytes = ByteArray(dataSize)
            raf.readFully(bytes)
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val out = FloatArray(dataSize / 2)
            for (i in out.indices) out[i] = buf.short.toFloat() / 32768f
            return out
        }
    }

    /**
     * whisper.cpp can emit bare punctuation or leading-apostrophe fragments as their own
     * "word" when max_len=1. Glue those back onto their lexical neighbour.
     */
    private fun mergeCompoundFragments(raw: List<WhisperLib.RawSegment>): List<Subtitle> {
        if (raw.isEmpty()) return emptyList()
        val out = ArrayList<Subtitle>(raw.size)
        for (seg in raw) {
            val txt = seg.text
            val isFragment = txt.isEmpty()
                || txt.all { !it.isLetterOrDigit() }
                || txt.startsWith("'")
                || txt.startsWith("-")
            if (isFragment && out.isNotEmpty()) {
                val last = out.removeAt(out.size - 1)
                out += last.copy(
                    text = (last.text + txt).trim(),
                    endMs = seg.endMs
                )
            } else if (txt.isNotEmpty()) {
                out += Subtitle(text = txt, startMs = seg.startMs, endMs = seg.endMs)
            }
        }
        return out
    }

    /**
     * Whisper often stretches a word's `t0` backwards into the preceding silence
     * (or `t1` forwards, for the last word of a chunk), so a short word can
     * occupy 5+ seconds of screen time. We trust `t1` (the word's actual end)
     * and trim the **start** forward so the subtitle only appears during the
     * word's real speaking window — leading silences stay silent on screen.
     *
     * Budget = ~120 ms per char, clamped to [400 ms, 1500 ms].
     */
    private fun capWordDurations(subs: List<Subtitle>): List<Subtitle> {
        return subs.map { s ->
            val budget = (200 + s.text.length * 120).toLong()
                .coerceIn(400, 1500)
            val cappedStart = (s.endMs - budget).coerceAtLeast(s.startMs)
            if (cappedStart > s.startMs) s.copy(startMs = cappedStart) else s
        }
    }

    companion object { private const val TAG = "WhisperService" }
}
