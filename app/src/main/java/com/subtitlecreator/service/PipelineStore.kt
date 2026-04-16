package com.subtitlecreator.service

import android.net.Uri
import com.subtitlecreator.model.Subtitle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * Singleton holder for pipeline state, shared between the UI layer (AppViewModel)
 * and the background foreground service (PipelineService). Lives for the life of
 * the process so heavy work started in the service keeps publishing progress
 * even when the activity is destroyed.
 */
object PipelineStore {

    data class UiState(
        val modelReady: Boolean = false,
        val modelDownloadMb: Int = 0,
        val modelTotalMb: Int = 0,
        val videoFile: File? = null,
        val transcribeProgress: Int = 0,
        val transcribing: Boolean = false,
        val subtitles: List<Subtitle> = emptyList(),
        val exporting: Boolean = false,
        val exportProgress: Int = 0,
        val exportedFile: File? = null,
        val savedUri: Uri? = null,
        val subtitleFontSize: Int = 72,
        val error: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun update(transform: (UiState) -> UiState) = _state.update(transform)
    fun current(): UiState = _state.value
}
