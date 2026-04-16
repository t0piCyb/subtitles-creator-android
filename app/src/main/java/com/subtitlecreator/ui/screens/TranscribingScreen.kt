package com.subtitlecreator.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.subtitlecreator.ui.components.KeepScreenOn

@Composable
fun TranscribingScreen(
    state: com.subtitlecreator.ui.UiState,
    onDone: () -> Unit
) {
    KeepScreenOn()

    LaunchedEffect(state.transcribing, state.subtitles) {
        if (!state.transcribing && state.subtitles.isNotEmpty()) onDone()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "${state.transcribeProgress}%",
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Transcribing on-device",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Whisper is processing your audio locally. No data leaves the phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(40.dp))
        LinearProgressIndicator(
            progress = { (state.transcribeProgress / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp)
        )
        state.error?.let { err ->
            Spacer(Modifier.height(24.dp))
            Text(err, color = MaterialTheme.colorScheme.error)
        }
    }
}
