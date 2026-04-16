package com.subtitlecreator.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.subtitlecreator.model.Subtitle
import com.subtitlecreator.ui.AppViewModel
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun EditorScreen(
    state: com.subtitlecreator.ui.UiState,
    vm: AppViewModel,
    onExport: () -> Unit
) {
    val context = LocalContext.current
    val player = remember(state.videoFile) {
        ExoPlayer.Builder(context).build().apply {
            state.videoFile?.let {
                setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(it)))
                prepare()
            }
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    var positionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition
            delay(30)
        }
    }
    val activeIndex = remember(state.subtitles, positionMs) {
        state.subtitles.indexOfFirst { positionMs in it.startMs..it.endMs }
    }
    val activeText = state.subtitles.getOrNull(activeIndex)?.text ?: ""

    val listState = rememberLazyListState()
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) listState.animateScrollToItem(activeIndex.coerceAtMost(state.subtitles.lastIndex))
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    PlayerView(it).apply {
                        useController = true
                        this.player = player
                    }
                }
            )
            // Live subtitle overlay — previews what the burned output will look like.
            if (activeText.isNotBlank()) {
                // ASS fontSize 72 on a 1080p canvas ≈ 30sp on screen. Keep the ratio.
                val overlaySp = (state.subtitleFontSize / 2.4f).sp
                Text(
                    text = activeText,
                    color = Color(0xFFFFD400),
                    style = TextStyle(
                        fontSize = overlaySp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color.Black,
                            blurRadius = 12f,
                            offset = androidx.compose.ui.geometry.Offset(0f, 2f)
                        )
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp, start = 16.dp, end = 16.dp)
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${state.subtitles.size} words",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = onExport, enabled = state.subtitles.isNotEmpty()) { Text("Export") }
        }

        // Font size slider — updates both the overlay and the burn-in.
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Size", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(48.dp))
                Slider(
                    value = state.subtitleFontSize.toFloat(),
                    onValueChange = { vm.setFontSize(it.toInt()) },
                    valueRange = 32f..140f,
                    modifier = Modifier.weight(1f)
                )
                Text("${state.subtitleFontSize}", modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(state.subtitles) { idx, sub ->
                SubtitleRow(
                    sub = sub,
                    isActive = idx == activeIndex,
                    onClick = { player.seekTo(sub.startMs) },
                    onTextChange = { vm.updateSubtitle(idx, text = it) },
                    onDelete = { vm.deleteSubtitle(idx) },
                    onAdd = { vm.addSubtitle(idx) }
                )
            }
        }
    }
}

@Composable
private fun SubtitleRow(
    sub: Subtitle,
    isActive: Boolean,
    onClick: () -> Unit,
    onTextChange: (String) -> Unit,
    onDelete: () -> Unit,
    onAdd: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(0.18f).padding(end = 8.dp)) {
                TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp)) {
                    Text(
                        formatMs(sub.startMs),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    formatMs(sub.endMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value = sub.text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            IconButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = "Add after") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
        }
    }
}

private fun formatMs(ms: Long): String {
    val m = ms / 60_000
    val s = (ms % 60_000) / 1000
    val cs = (ms % 1000) / 10
    return "%d:%02d.%02d".format(m, s, cs)
}
