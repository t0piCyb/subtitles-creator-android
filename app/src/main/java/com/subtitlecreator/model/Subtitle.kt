package com.subtitlecreator.model

import kotlinx.serialization.Serializable

/**
 * One on-screen word/phrase with its start & end time in ms.
 * Whisper produces one of these per word when split_on_word=true and max_len=1.
 */
@Serializable
data class Subtitle(
    val text: String,
    val startMs: Long,
    val endMs: Long
) {
    val durationMs: Long get() = endMs - startMs
}

@Serializable
data class TranscriptionResult(
    val language: String,
    val subtitles: List<Subtitle>
)

object SubtitleDefaults {
    /** Whisper can emit very short segments — clamp below this and they flash unreadably. */
    const val MIN_DURATION_MS: Long = 150
}
