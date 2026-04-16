package com.subtitlecreator.jni

/**
 * Thin Kotlin wrapper around whisper.cpp native bindings.
 * Loads libwhisper_jni.so built via CMake from app/src/main/cpp/.
 *
 * See whisper_jni.cpp for the native symbols.
 */
object WhisperLib {

    init {
        System.loadLibrary("whisper_jni")
    }

    interface ProgressCallback {
        fun onProgress(percent: Int)
    }

    private external fun nativeInitContext(modelPath: String): Long
    private external fun nativeFreeContext(ctxPtr: Long)
    private external fun nativeTranscribe(
        ctxPtr: Long,
        pcm: FloatArray,
        language: String,
        nThreads: Int,
        translate: Boolean,
        progressCb: ProgressCallback?
    ): Array<String>?
    private external fun nativeCancel()
    private external fun nativeSystemInfo(): String

    fun init(modelPath: String): Long {
        val ptr = nativeInitContext(modelPath)
        check(ptr != 0L) { "Failed to init whisper context from $modelPath" }
        return ptr
    }

    fun free(ctxPtr: Long) = nativeFreeContext(ctxPtr)

    data class RawSegment(val text: String, val startMs: Long, val endMs: Long)

    fun transcribe(
        ctxPtr: Long,
        pcm: FloatArray,
        language: String = "auto",
        nThreads: Int = 4,
        translate: Boolean = false,
        onProgress: ((Int) -> Unit)? = null
    ): List<RawSegment> {
        val cb = onProgress?.let { cb ->
            object : ProgressCallback {
                override fun onProgress(percent: Int) {
                    cb(percent)
                }
            }
        }
        val flat = nativeTranscribe(ctxPtr, pcm, language, nThreads, translate, cb)
            ?: error("whisper_full failed")

        return buildList(flat.size / 3) {
            var i = 0
            while (i < flat.size) {
                add(RawSegment(flat[i].trim(), flat[i + 1].toLong(), flat[i + 2].toLong()))
                i += 3
            }
        }
    }

    fun cancel() = nativeCancel()
    fun systemInfo(): String = nativeSystemInfo()
}
