package com.subtitlecreator.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.subtitlecreator.ui.components.KeepScreenOn

@Composable
fun ExportScreen(
    state: com.subtitlecreator.ui.UiState,
    onDone: () -> Unit
) {
    KeepScreenOn()

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (state.exporting) {
            Text(
                "${state.exportProgress}%",
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            Text("Burning subtitles into video", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(32.dp))
            LinearProgressIndicator(
                progress = { (state.exportProgress / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp)
            )
        } else if (state.exportedFile != null) {
            Icon(
                Icons.Default.CheckCircle,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(96.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text("Done!", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                if (state.savedUri != null) "Saved to Movies/SubtitlesCreator"
                else "Saved to app storage: ${state.exportedFile.name}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(40.dp))
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text("New video", style = MaterialTheme.typography.titleLarge) }
        }

        state.error?.let { err ->
            Spacer(Modifier.height(24.dp))
            Text(err, color = MaterialTheme.colorScheme.error)
        }
    }
}
