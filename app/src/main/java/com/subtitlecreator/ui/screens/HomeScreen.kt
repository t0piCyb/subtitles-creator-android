package com.subtitlecreator.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.subtitlecreator.ui.AppViewModel

@Composable
fun HomeScreen(
    state: com.subtitlecreator.ui.UiState,
    vm: AppViewModel,
    onStartTranscribe: () -> Unit
) {
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let(vm::onVideoPicked)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Spacer(Modifier.height(32.dp))

        Text(
            text = "Subtitles Creator",
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "On-device transcription · Whisper large-v3-turbo",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))

        // Model card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.modelReady) {
                        Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        Text("Model ready", style = MaterialTheme.typography.titleLarge)
                    } else {
                        Icon(Icons.Default.CloudDownload, null)
                        Text("Download model (~574 MB)", style = MaterialTheme.typography.titleLarge)
                    }
                }
                if (!state.modelReady) {
                    if (state.modelTotalMb > 0) {
                        LinearProgressIndicator(
                            progress = { state.modelDownloadMb.toFloat() / state.modelTotalMb.toFloat() },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("${state.modelDownloadMb} / ${state.modelTotalMb} MB", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = vm::downloadModel,
                        enabled = state.modelTotalMb == 0,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Download now") }
                }
            }
        }

        // Video card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.VideoLibrary, null)
                    Text("Video", style = MaterialTheme.typography.titleLarge)
                }
                Text(
                    state.videoFile?.name ?: "No video selected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = {
                        videoPicker.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.VideoOnly
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (state.videoFile == null) "Pick video" else "Change video") }
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onStartTranscribe,
            enabled = state.modelReady && state.videoFile != null && !state.transcribing,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text("Transcribe", style = MaterialTheme.typography.titleLarge) }

        state.error?.let { err ->
            Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
