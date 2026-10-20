package digital.tripolskiy.opshade

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/** OxygenOS press feedback: scale down on press with a critically damped spring (response 0.3). */
@Composable
private fun pressScale(source: MutableInteractionSource, pressed: Float): Float {
    val isPressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (isPressed) pressed else 1f,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 440f),
        label = "press",
    )
    return s
}

@Composable
private fun tileColor(active: Boolean) = animateColorAsState(
    if (active) P.active else P.inactive, tween(200, easing = Op.ColorEasing), label = "tile",
).value

@Composable
private fun tileContent(active: Boolean) = animateColorAsState(
    if (active) P.onActive else P.onInactive, tween(200, easing = Op.ColorEasing), label = "tileContent",
).value

/** Round tile: 50dp in the quick row, 52dp in the full grid. The caller scales it between the two. */
@Composable
fun CircleTile(tile: TileUi, size: Dp, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source, 0.92f)
    Box(
        modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(tileColor(tile.active && !tile.stateless))
            // A disabled clickable still swallows taps, so leave it off entirely when the tile is inert.
            .then(if (enabled) Modifier.combinedClickable(source, indication = null, onClick = onClick, onLongClick = onLongClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = tile.bitmap
        if (bmp != null) {
            androidx.compose.foundation.Image(bmp, null, modifier = Modifier.size(30.u).clip(CircleShape))
        } else {
            Icon(tile.icon, null, tint = tileContent(tile.active && !tile.stateless), modifier = Modifier.size(22.u))
        }
    }
}

@Composable
fun TileLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier = modifier, color = P.text, fontSize = 13.us, lineHeight = 15.us,
        fontFamily = Op.Body, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
}

/** The wide Wi-Fi / Bluetooth tile at the top of the panel, with a details chevron on the right. */
@Composable
fun HighlightTile(tile: TileUi, onClick: () -> Unit, onDetails: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source, 0.96f)
    val content = tileContent(tile.active)
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(50))
            .background(tileColor(tile.active))
            .combinedClickable(source, indication = null, onClick = onClick, onLongClick = onDetails),
    ) {
        Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(44.u), contentAlignment = Alignment.Center) {
                Icon(tile.icon, null, tint = content, modifier = Modifier.offset(x = 3.u).size(24.u))
            }
            Text(
                tile.label, modifier = Modifier.weight(1f), color = content, fontSize = 14.us, lineHeight = 16.us,
                fontFamily = Op.Body, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Box(
                Modifier
                    .padding(start = 4.u, end = 6.u)
                    .size(24.u)
                    .clip(CircleShape)
                    .background(content.copy(alpha = 0.14f))
                    .combinedClickable(onClick = onDetails),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.ChevronRight, null, tint = content, modifier = Modifier.size(18.u))
            }
        }
    }
}

/**
 * The tall brightness / volume pill. Dragging is relative (OxygenOS move type 2: the value follows
 * the finger's travel, it does not jump to the touch point). Past either end the pill stretches up
 * to 4dp with the COUI curve (1 - e^(-11.5 d)) and springs back on release.
 */
@Composable
fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    icon: ImageVector,
    iconTint: Color,
    onIconClick: () -> Unit,
    steps: Int = 0,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    // The gesture handler below lives across recompositions; read the latest arguments through these.
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnIcon by rememberUpdatedState(onIconClick)
    val currentSteps by rememberUpdatedState(steps)
    var dragValue by remember { mutableFloatStateOf(-1f) }
    val shown = if (dragValue >= 0f) dragValue else value
    val animated by animateFloatAsState(shown, spring(stiffness = if (dragValue >= 0f) 6000f else 500f), label = "level")
    val stretch = remember { Animatable(0f) }
    val maxStretch = 4.u
    val iconBox = 36.u
    val iconBottom = 10.u

    Box(
        modifier
            .graphicsLayer {
                val s = stretch.value
                val h = size.height
                if (h > 0f) {
                    scaleY = 1f + abs(s) * maxStretch.toPx() / h
                    transformOrigin = TransformOrigin(0.5f, if (s > 0f) 1f else 0f)
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val h = size.height.toFloat()
                    var v = currentValue
                    var moved = 0f
                    val steps = currentSteps
                    var lastStep = if (steps > 0) (v * steps).roundToInt() else (v * 20).roundToInt()
                    dragValue = v
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val dy = ch.positionChange().y
                        ch.consume()
                        moved += abs(dy)
                        v -= dy / h
                        val clamped = v.coerceIn(0f, 1f)
                        val over = (v - clamped) / 5f // COUI divides overdrag by 5
                        scope.launch { stretch.snapTo((1f - exp(-11.5f * abs(over))) * if (over > 0) 1f else if (over < 0) -1f else 0f) }
                        if (dragValue != clamped) {
                            dragValue = clamped
                            currentOnChange(clamped)
                            val step = if (steps > 0) (clamped * steps).roundToInt() else (clamped * 20).roundToInt()
                            if (step != lastStep) {
                                lastStep = step
                                view.performHapticFeedback(
                                    if (clamped == 0f || clamped == 1f) HapticFeedbackConstants.LONG_PRESS
                                    else HapticFeedbackConstants.CLOCK_TICK
                                )
                            }
                        }
                    }
                    v = v.coerceIn(0f, 1f)
                    // A tap on the icon toggles auto brightness / mute, as on OxygenOS.
                    if (moved < 8f && down.position.y > h - (iconBox + iconBottom).toPx()) currentOnIcon()
                    dragValue = -1f
                    scope.launch { stretch.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 500f)) }
                }
            },
    ) {
        val track = P.sliderTrack
        val fill = P.active
        Canvas(Modifier.fillMaxSize()) {
            val r = size.width / 2f
            drawRoundRect(track, cornerRadius = CornerRadius(r))
            val fillH = size.height * animated
            if (fillH > 0f) {
                // Fill is clipped to the pill: draw the pill and cut it at the fill height.
                clipRect(0f, size.height - fillH, size.width, size.height) {
                    drawRoundRect(fill, cornerRadius = CornerRadius(r))
                }
            }
        }
        val iconCovered = animated * 1f > (iconBox + iconBottom).value / 148f
        Icon(
            icon, null,
            tint = if (iconCovered) iconTint else P.text,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = iconBottom)
                .size(iconBox)
                .padding(6.u),
        )
    }
}

/** Pill-shaped grab handle between the tiles and the notifications. */
@Composable
fun Handle(modifier: Modifier = Modifier) {
    val color = P.handle
    Canvas(modifier.size(48.u, 4.u)) {
        drawRoundRect(color, Offset.Zero, Size(size.width, size.height), CornerRadius(size.height / 2))
    }
}

@Composable
fun Gap(d: Dp) = Spacer(Modifier.size(d))
