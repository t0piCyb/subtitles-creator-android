package com.subtitlecreator.ui.screens

import android.graphics.Typeface as AndroidTypeface
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.subtitlecreator.model.Subtitle
import com.subtitlecreator.service.PipelineStore
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

    // Montserrat Bold loaded from assets so the preview uses the exact same typeface as the burn.
    val montserratFamily = remember(context) {
        runCatching {
            FontFamily(AndroidTypeface.createFromAsset(context.assets, "fonts/Montserrat-Bold.ttf"))
        }.getOrDefault(FontFamily.Default)
    }
    val density = LocalDensity.current
    val videoAspect = remember(state.videoWidthPx, state.videoHeightPx) {
        (state.videoWidthPx.toFloat() / state.videoHeightPx.coerceAtLeast(1)).coerceIn(0.3f, 3f)
    }

    Column(Modifier.fillMaxSize()) {
        // Outer container: centers the preview and caps its height so portrait videos don't
        // swallow the whole screen. The inner Box keeps the TRUE video aspect so the subtitle
        // overlay maps 1:1 to the burned frame.
        BoxWithConstraints(Modifier.fillMaxWidth().background(Color.Black)) {
            val maxPreviewH = 360.dp
            val naturalH = maxWidth / videoAspect
            val previewH = if (naturalH <= maxPreviewH) naturalH else maxPreviewH
            val previewW = if (naturalH <= maxPreviewH) maxWidth else maxPreviewH * videoAspect
        BoxWithConstraints(
            Modifier
                .size(previewW, previewH)
                .align(Alignment.Center)
                .background(Color.Black)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    PlayerView(it).apply {
                        useController = true
                        this.player = player
                    }
                }
            )

            // Live subtitle overlay — mirrors SubtitleBurner exactly (font, size, colour, shadow,
            // top anchor). The user can drag it vertically; the chosen position is what the
            // export uses verbatim.
            val boxWidthPx = with(density) { maxWidth.toPx() }
            val boxHeightPx = with(density) { maxHeight.toPx() }
            val videoW = state.videoWidthPx.coerceAtLeast(1).toFloat()
            val scale = boxWidthPx / videoW            // preview-px per video-px
            val burnPx = state.subtitleFontSize * 2f   // same multiplier as PipelineService
            val displayedSp = with(density) { (burnPx * scale).toSp() }
            val shadowBlurPx = burnPx * 0.10f * scale
            val shadowOffsetX = burnPx * 0.035f * scale
            val shadowOffsetY = burnPx * 0.065f * scale

            // Show the active word; when silent, dim the last-seen word so the user always has
            // something to grab. Before any word has played, fall back to the first subtitle.
            val lastIdx = remember { mutableIntStateOf(-1) }
            LaunchedEffect(activeIndex) { if (activeIndex >= 0) lastIdx.intValue = activeIndex }
            val displayIdx = when {
                activeIndex >= 0 -> activeIndex
                lastIdx.intValue >= 0 -> lastIdx.intValue
                else -> 0
            }
            val displayText = state.subtitles.getOrNull(displayIdx)?.text.orEmpty()
            val ghost = activeIndex < 0

            if (displayText.isNotBlank()) {
                val yOffset = maxHeight * state.subtitlePositionFraction
                Text(
                    text = displayText,
                    color = Color(0xFFFFD400).let { if (ghost) it.copy(alpha = 0.45f) else it },
                    style = TextStyle(
                        fontFamily = montserratFamily,
                        fontSize = displayedSp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        shadow = Shadow(
                            color = Color(0x80000000),
                            offset = Offset(shadowOffsetX, shadowOffsetY),
                            blurRadius = shadowBlurPx
                        ),
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = yOffset)
                        .padding(horizontal = 8.dp)
                        .pointerInput(boxHeightPx) {
                            detectVerticalDragGestures { change, dy ->
                                change.consume()
                                val cur = PipelineStore.current().subtitlePositionFraction
                                vm.setSubtitlePosition(cur + dy / boxHeightPx)
                            }
                        }
                )
            }
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
                    valueRange = 32f..180f,
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
