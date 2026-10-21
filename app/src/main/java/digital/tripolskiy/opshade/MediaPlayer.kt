package digital.tripolskiy.opshade

import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/**
 * Pixel-style media carousel: one card per player, swiped sideways, with the next card peeking in.
 */
@Composable
fun MediaCarousel(items: List<MediaItem>, host: ShadeService, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    val pager = rememberPagerState { items.size }
    Column(modifier) {
        HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(end = if (items.size > 1) 20.u else 0.u),
            pageSpacing = 8.u,
            key = { items[it].token.hashCode() },
        ) { page ->
            MediaCard(items[page], host)
        }
        if (items.size > 1) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.u),
                horizontalArrangement = Arrangement.Center,
            ) {
                repeat(items.size) { i ->
                    Box(
                        Modifier
                            .padding(horizontal = 3.u)
                            .size(6.u)
                            .clip(CircleShape)
                            .background(P.text.copy(alpha = if (pager.currentPage == i) 1f else 0.3f)),
                    )
                }
            }
        }
    }
}

@Composable
fun MediaCard(item: MediaItem, host: ShadeService) {
    val controls = MediaRepo.controller(item.token)?.transportControls
    val onArt = item.art != null
    val fg = if (onArt) Color.White else P.text
    val fgDim = fg.copy(alpha = 0.75f)
    // Pixel tints the play button from the artwork; without art it uses the theme accent.
    val buttonBg = item.accent?.let { lerp(it, Color.White, 0.55f) } ?: P.active
    val buttonFg = item.accent?.let { lerp(it, Color.Black, 0.7f) } ?: P.onActive

    Box(
        Modifier
            .fillMaxWidth()
            .height(150.u)
            .clip(RoundedCornerShape(24.u))
            .background(P.card)
            .clickable { host.sendPendingIntent(item.openIntent) },
    ) {
        item.art?.let { art ->
            Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            // Darkens the art so text and controls stay readable, heavier on the text side.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.78f), Color.Black.copy(alpha = 0.45f))),
                ),
            )
        }
        Column(Modifier.fillMaxSize().padding(start = 16.u, end = 16.u, top = 14.u, bottom = 10.u)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                item.appIcon?.let { Image(it, null, modifier = Modifier.size(22.u).clip(CircleShape)) }
                Spacer(Modifier.weight(1f))
                OutputChip(item, fg) { host.openOutputSwitcher(item.pkg, item.token) }
            }
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.u)) {
                    Text(
                        item.title, color = fg, fontSize = 16.us, fontFamily = Op.Display, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (item.artist.isNotBlank()) {
                        Text(item.artist, color = fgDim, fontSize = 13.5.us, fontFamily = Op.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(
                    Modifier
                        .size(64.u, 44.u)
                        .clip(RoundedCornerShape(18.u))
                        .background(buttonBg)
                        .clickable { MediaRepo.command(item.token, if (item.playing) MediaRepo.Cmd.PAUSE else MediaRepo.Cmd.PLAY) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (item.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (item.playing) "Pause" else "Play", tint = buttonFg, modifier = Modifier.size(26.u),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                MediaButton(Icons.Rounded.SkipPrevious, "Previous", fg, item.has(PlaybackState.ACTION_SKIP_TO_PREVIOUS)) {
                    MediaRepo.command(item.token, MediaRepo.Cmd.PREV)
                }
                SeekBar(item, fg, Modifier.weight(1f).padding(horizontal = 6.u)) { ms -> controls?.seekTo(ms) }
                MediaButton(Icons.Rounded.SkipNext, "Next", fg, item.has(PlaybackState.ACTION_SKIP_TO_NEXT)) {
                    MediaRepo.command(item.token, MediaRepo.Cmd.NEXT)
                }
                // App-defined actions (e.g. -15 / +30 seconds), as Pixel shows them after next.
                item.custom.take(2).forEach { a ->
                    Box(
                        Modifier.size(36.u).clip(CircleShape).clickable { MediaRepo.custom(item.token, a) },
                        contentAlignment = Alignment.Center,
                    ) {
                        a.icon?.let { Image(it, a.name, colorFilter = ColorFilter.tint(fg), modifier = Modifier.size(22.u)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaButton(icon: ImageVector, label: String, tint: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(36.u).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = tint.copy(alpha = if (enabled) 1f else 0.3f), modifier = Modifier.size(24.u))
    }
}

/** Chip with the current output device; opens Pixel's own output switcher for this player. */
@Composable
private fun OutputChip(item: MediaItem, fg: Color, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val route by produceState<Pair<String, Boolean>?>(null, item.token) {
        while (true) {
            value = try {
                val r = MediaRouter2.getInstance(ctx).systemController.selectedRoutes.firstOrNull()
                if (r == null) null else {
                    val bt = Build.VERSION.SDK_INT >= 34 && r.type in setOf(
                        MediaRoute2Info.TYPE_BLUETOOTH_A2DP, MediaRoute2Info.TYPE_BLE_HEADSET, MediaRoute2Info.TYPE_HEARING_AID,
                        MediaRoute2Info.TYPE_WIRED_HEADPHONES, MediaRoute2Info.TYPE_WIRED_HEADSET, MediaRoute2Info.TYPE_USB_HEADSET,
                    )
                    r.name.toString() to bt
                }
            } catch (_: Exception) { null }
            delay(2000)
        }
    }
    val r = route
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(fg.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.u, vertical = 5.u)
            .widthIn(max = 160.u),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when { r == null -> Icons.Rounded.Speaker; r.second -> Icons.Rounded.Headphones; else -> Icons.Rounded.PhoneAndroid },
            null, tint = fg, modifier = Modifier.size(16.u),
        )
        Text(
            r?.first ?: "This phone", color = fg, fontSize = 12.5.us, fontFamily = Op.Body,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.u),
        )
    }
}

/**
 * Pixel's squiggly progress: the played part waves while playing and flattens when paused, the
 * rest is a faint straight line, and a short vertical bar marks the position. Drag or tap to seek.
 */
@Composable
private fun SeekBar(item: MediaItem, color: Color, modifier: Modifier, onSeek: (Long) -> Unit) {
    val canSeek = item.duration > 0 && item.has(PlaybackState.ACTION_SEEK_TO)
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(item.playing, item.positionTime) {
        while (item.playing) {
            now = SystemClock.elapsedRealtime()
            delay(100)
        }
        now = SystemClock.elapsedRealtime()
    }
    var drag by remember { mutableFloatStateOf(-1f) }
    val currentItem by rememberUpdatedState(item)
    val fraction = if (drag >= 0f) drag else if (item.duration > 0) item.positionAt(now).toFloat() / item.duration else 0f
    val amplitude by animateFloatAsState(if (item.playing && drag < 0f) 1f else 0f, tween(300), label = "wave")
    val phase by rememberInfiniteTransition(label = "phase").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "phase",
    )

    Canvas(
        modifier
            .height(28.u)
            .then(
                if (!canSeek) Modifier else Modifier
                    .pointerInput(Unit) {
                        detectTapGestures { o -> onSeek((o.x / size.width * currentItem.duration).toLong()) }
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                            onDragEnd = { onSeek((drag * currentItem.duration).toLong()); drag = -1f },
                            onDragCancel = { drag = -1f },
                        ) { ch, _ -> ch.consume(); drag = (ch.position.x / size.width).coerceIn(0f, 1f) }
                    },
            ),
    ) {
        val w = size.width
        val cy = size.height / 2f
        val x = (fraction.coerceIn(0f, 1f) * w)
        val stroke = 3.dp2px(this)
        val wave = 3.dp2px(this) * amplitude
        val wl = 20.dp2px(this)
        // Remaining part: faint straight line.
        drawLine(color.copy(alpha = 0.3f), Offset(x, cy), Offset(w, cy), strokeWidth = stroke, cap = StrokeCap.Round)
        // Played part: a wave that travels while playing.
        val path = Path()
        var px = 0f
        path.moveTo(0f, cy)
        while (px <= x) {
            val y = cy + wave * sin(((px / wl) - phase) * 2f * PI.toFloat())
            path.lineTo(px, y)
            px += 2f
        }
        drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
        // Position marker.
        drawLine(color, Offset(x, cy - 8.dp2px(this)), Offset(x, cy + 8.dp2px(this)), strokeWidth = 4.dp2px(this), cap = StrokeCap.Round)
    }
}

private fun Int.dp2px(d: androidx.compose.ui.unit.Density) = with(d) { this@dp2px.toFloat() * density }
