package digital.tripolskiy.opshade

import android.text.format.DateUtils
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private fun relTime(t: Long): String =
    DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

/**
 * One notification card, OxygenOS style: app icon on the left, header line with time and
 * expand chevron, title and text.
 *
 * Swiping follows OxygenOS (NotificationMenuRowExtImpl):
 * - right: dismiss; a delete badge grows in the uncovered space and turns red once letting go
 *   would delete (60% of the width, OplusSwipeHelper's far-enough fraction);
 * - left: the card snaps open on a row of round buttons (remind me, notification settings,
 *   delete); dragging on past the threshold stretches the delete button and releasing deletes.
 * Ongoing notifications can still open the menu but have no delete.
 */
@Composable
fun NotificationCard(
    item: NotifItem,
    onOpen: (NotifItem) -> Unit,
    onDismiss: (NotifItem) -> Unit,
    onAction: (NotifItem, Int) -> Unit,
    onSnooze: (NotifItem) -> Unit,
    onSettings: (NotifItem) -> Unit,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(item.key) { mutableStateOf(false) }
    // Ellipsized text also makes a card expandable, as on Pixel.
    var overflow by remember(item.key) { mutableStateOf(false) }
    val canExpand = item.hasMore || overflow
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val offsetX = remember(item.key) { Animatable(0f) }
    var width by remember { mutableIntStateOf(1) }
    val density = LocalDensity.current
    val buttons = if (item.clearable) 3 else 2
    // OxygenOS: icon slots plus margins, 52dp circles with 10dp gaps and 4dp extra.
    val menuWidth = with(density) { (buttons * (52 + 10) + 4).u.toPx() }
    val dismissAt = { width * 0.6f }
    var pastThreshold by remember(item.key) { mutableStateOf(false) }
    val spring = spring<Float>(dampingRatio = 1f, stiffness = 500f)

    // Another card opened its menu (or the list scrolled): fold this one back.
    LaunchedEffect(menuOpen) { if (!menuOpen && offsetX.value < 0f && !offsetX.isRunning) offsetX.animateTo(0f, spring) }

    fun dismiss(direction: Int) {
        scope.launch {
            offsetX.animateTo(direction * width * 1.05f, tween(180))
            onDismiss(item)
        }
    }

    Box(modifier.fillMaxWidth().onSizeChanged { width = it.width }) {
        SwipeBackdrop(
            offset = { offsetX.value },
            width = { width.toFloat() },
            menuWidth = menuWidth,
            dismissAt = dismissAt,
            clearable = item.clearable,
            onSnooze = { onMenuOpenChange(false); onSnooze(item) },
            onSettings = { onMenuOpenChange(false); onSettings(item) },
            onDelete = { onMenuOpenChange(false); dismiss(-1) },
            modifier = Modifier.matchParentSize(),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { d ->
                        var next = offsetX.value + d
                        // Ongoing notifications can't be dismissed: resist going right.
                        if (!item.clearable && next > 0f) next = offsetX.value + d * 0.2f
                        if (!item.clearable && next < -menuWidth) next = offsetX.value + d * 0.2f
                        val past = item.clearable && kotlin.math.abs(next) > dismissAt()
                        if (past != pastThreshold) {
                            pastThreshold = past
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
                        }
                        // Apply the move now, not in a queued coroutine: a queued snap could run after
                        // the release animation starts, cancel it and leave the card stuck half-way.
                        scope.launch(start = CoroutineStart.UNDISPATCHED) { offsetX.snapTo(next) }
                    },
                    onDragStarted = { if (offsetX.value == 0f) onMenuOpenChange(false) },
                    onDragStopped = { v ->
                        val x = offsetX.value
                        when {
                            item.clearable && (x > dismissAt() || (x > width * 0.2f && v > 2500f)) -> dismiss(1)
                            item.clearable && (x < -dismissAt() || (x < -menuWidth && v < -3000f)) -> dismiss(-1)
                            x < 0f && (x < -menuWidth / 2f || v < -800f) && v < 800f -> {
                                onMenuOpenChange(true)
                                offsetX.animateTo(-menuWidth, spring)
                            }
                            else -> {
                                if (x < 0f) onMenuOpenChange(false)
                                offsetX.animateTo(0f, spring)
                            }
                        }
                        pastThreshold = false
                    },
                )
                .clip(RoundedCornerShape(20.u))
                .background(P.card)
                .clickable {
                    when {
                        offsetX.value != 0f -> { onMenuOpenChange(false); scope.launch { offsetX.animateTo(0f, spring) } }
                        item.children.isNotEmpty() && !expanded -> expanded = true
                        else -> onOpen(item)
                    }
                }
                .animateContentSize(),
        ) {
            Row(Modifier.padding(top = 12.u, bottom = 12.u)) {
                Box(Modifier.width(58.u).padding(start = 14.u, end = 12.u).height(if (item.children.isEmpty()) 52.u else 60.u), contentAlignment = Alignment.CenterStart) {
                    AppIcon(item, if (item.children.isEmpty()) 32 else 32)
                    if (item.children.size > 1) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 4.u, y = 2.u)
                                .size(16.u)
                                .clip(CircleShape)
                                .background(P.chip),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(item.children.size.toString(), color = P.text, fontSize = 10.us, fontFamily = Op.Body)
                        }
                    }
                }
                Column(Modifier.weight(1f).padding(end = 14.u)) {
                    // Groups and header-less notifications put the first line next to the time, as OxygenOS does.
                    val inline = item.children.isNotEmpty() && !expanded
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (inline) {
                            GroupLine(item.children.first(), Modifier.weight(1f))
                        } else {
                            Text(
                                if (item.children.isEmpty()) item.header else item.header, Modifier.weight(1f),
                                color = P.textSecondary, fontSize = 11.5.us,
                                fontFamily = Op.Body, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(relTime(item.time), color = P.textSecondary, fontSize = 11.5.us, fontFamily = Op.Body, maxLines = 1)
                        if (canExpand || expanded) {
                            Icon(
                                if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                                if (expanded) "Collapse" else "Expand",
                                tint = P.textSecondary,
                                modifier = Modifier.size(20.u).clip(CircleShape).clickable { expanded = !expanded },
                            )
                        }
                    }
                    if (item.children.isEmpty()) {
                        CardBody(item, expanded, onOverflow = { if (it) overflow = true })
                        if (expanded) ExpandedExtras(item)
                    } else if (!expanded) {
                        // Collapsed group: up to three "title  text" lines, newest first.
                        item.children.drop(1).take(2).forEach { c -> GroupLine(c, Modifier.padding(top = 4.u)) }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.u), modifier = Modifier.padding(top = 6.u)) {
                            item.children.forEach { c ->
                                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.u)).clickable { onOpen(c) }) {
                                    CardBody(c, false)
                                }
                            }
                        }
                    }
                    if (expanded && item.children.isEmpty() && item.actions.isNotEmpty()) {
                        Row(Modifier.padding(top = 10.u), horizontalArrangement = Arrangement.spacedBy(8.u)) {
                            item.actions.take(3).forEachIndexed { i, a ->
                                val reply = a.remoteInputs?.any { it.allowFreeFormInput } == true
                                Row(
                                    Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(if (reply) P.active else P.chip)
                                        .clickable { onAction(item, i) }
                                        .padding(horizontal = 14.u, vertical = 7.u),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (reply) Icon(Icons.AutoMirrored.Rounded.Reply, null, tint = P.onActive, modifier = Modifier.padding(end = 6.u).size(16.u))
                                    Text(
                                        a.title?.toString().orEmpty(), color = if (reply) P.onActive else P.text,
                                        fontSize = 13.us, fontFamily = Op.Body, maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupLine(c: NotifItem, modifier: Modifier = Modifier) {
    Row(modifier) {
        Text(c.title, color = P.text, fontSize = 14.us, fontFamily = Op.Body, fontWeight = FontWeight.Medium, maxLines = 1)
        Text(
            "  " + c.text, color = P.textSecondary, fontSize = 14.us, fontFamily = Op.Body,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CardBody(item: NotifItem, expanded: Boolean, onOverflow: (Boolean) -> Unit = {}) {
    Row {
        Column(Modifier.weight(1f)) {
            if (item.title.isNotBlank()) {
                Text(
                    item.title, color = P.text, fontSize = 14.5.us, lineHeight = 19.us, fontFamily = Op.Body,
                    fontWeight = FontWeight.Medium, maxLines = if (expanded) 3 else 1, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) onOverflow(it.hasVisualOverflow) },
                    modifier = Modifier.padding(top = 4.u),
                )
            }
            // Messaging shows its history when expanded instead of the single latest line.
            val showHistory = expanded && item.messages.size > 1
            val body = if (expanded) item.bigText ?: item.text else item.text
            if (body.isNotBlank() && !showHistory) {
                Text(
                    body, color = P.textSecondary, fontSize = 13.5.us, lineHeight = 18.us, fontFamily = Op.Body,
                    maxLines = if (expanded) 12 else 2, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) onOverflow(it.hasVisualOverflow) },
                    modifier = Modifier.padding(top = 2.u),
                )
            }
            item.progress?.let { p ->
                val mod = Modifier.padding(top = 8.u, bottom = 2.u).fillMaxWidth().height(4.u).clip(RoundedCornerShape(50))
                if (p < 0f) {
                    androidx.compose.material3.LinearProgressIndicator(mod, color = P.active, trackColor = P.chip)
                } else {
                    androidx.compose.material3.LinearProgressIndicator(progress = { p }, modifier = mod, color = P.active, trackColor = P.chip, drawStopIndicator = {})
                }
            }
        }
        if (!expanded || item.picture == null) item.largeIcon?.let {
            Image(
                it, null, contentScale = ContentScale.Crop,
                modifier = Modifier.padding(start = 8.u, top = 6.u).size(26.u).clip(CircleShape),
            )
        }
    }
}

/** What only shows when a card is expanded: the conversation and the big picture. */
@Composable
private fun ExpandedExtras(item: NotifItem) {
    if (item.messages.size > 1) {
        Column(Modifier.padding(top = 4.u), verticalArrangement = Arrangement.spacedBy(3.u)) {
            item.messages.forEach { (who, body) ->
                Row {
                    if (who.isNotBlank()) {
                        Text(who + "  ", color = P.text, fontSize = 13.5.us, fontFamily = Op.Body, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                    Text(body, color = P.textSecondary, fontSize = 13.5.us, lineHeight = 18.us, fontFamily = Op.Body, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    item.picture?.let { pic ->
        Image(
            pic, null, contentScale = ContentScale.Crop,
            modifier = Modifier
                .padding(top = 10.u)
                .fillMaxWidth()
                .aspectRatio((pic.width.toFloat() / pic.height).coerceIn(1f, 2.2f))
                .clip(RoundedCornerShape(12.u)),
        )
    }
}

@Composable
private fun AppIcon(item: NotifItem, size: Int) {
    val bmp = item.appIcon
    if (bmp != null) {
        Image(bmp, null, modifier = Modifier.size(size.u).clip(CircleShape))
    } else {
        Box(Modifier.size(size.u).clip(CircleShape).background(P.inactive))
    }
}

/**
 * What shows in the space a swiped card uncovers. Everything reads the card's offset in
 * draw/layout lambdas, so dragging doesn't recompose; only crossing the delete threshold does.
 */
@Composable
private fun SwipeBackdrop(
    offset: () -> Float,
    width: () -> Float,
    menuWidth: Float,
    dismissAt: () -> Float,
    clearable: Boolean,
    onSnooze: () -> Unit,
    onSettings: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rightPast by remember { derivedStateOf { clearable && offset() > dismissAt() } }
    val leftPast by remember { derivedStateOf { clearable && -offset() > dismissAt() } }
    val button = 52.u
    val buttonPx = with(LocalDensity.current) { button.toPx() }
    val gapPx = with(LocalDensity.current) { 8.u.toPx() }
    val reveal = { (-offset() / menuWidth).coerceIn(0f, 1f) }

    Box(modifier) {
        if (clearable) {
            // Right swipe: the delete badge grows with the drag and turns red once releasing deletes.
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 14.u)
                    .size(48.u)
                    .graphicsLayer {
                        val x = offset().coerceAtLeast(0f)
                        alpha = (x / buttonPx).coerceIn(0f, 1f)
                        val sc = 0.6f + 0.4f * (x / dismissAt()).coerceIn(0f, 1f)
                        scaleX = sc; scaleY = sc
                    }
                    .clip(CircleShape)
                    .background(if (rightPast) P.danger else P.inactive),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Delete, null, tint = if (rightPast) P.onDanger else P.text, modifier = Modifier.size(24.u))
            }
        }

        // Left swipe: OxygenOS menu row on the right.
        Row(
            Modifier.align(Alignment.CenterEnd).padding(end = 4.u),
            horizontalArrangement = Arrangement.spacedBy(10.u),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MenuButton(Icons.Rounded.Snooze, "Remind me in 1 hour", reveal, hidden = leftPast, onClick = onSnooze)
            MenuButton(Icons.Outlined.Settings, "Notification settings", reveal, hidden = leftPast, onClick = onSettings)
            if (clearable) {
                // Past the threshold the delete button stretches over the uncovered space.
                Box(
                    Modifier
                        .layout { m, c ->
                            val w = if (leftPast) (-offset() - gapPx).coerceAtLeast(buttonPx) else buttonPx
                            val pl = m.measure(c.copy(minWidth = w.toInt(), maxWidth = w.toInt(), minHeight = buttonPx.toInt(), maxHeight = buttonPx.toInt()))
                            layout(pl.width, pl.height) { pl.place(0, 0) }
                        }
                        .graphicsLayer { val r = reveal(); alpha = r; val sc = 0.7f + 0.3f * r; scaleX = sc; scaleY = sc }
                        .clip(RoundedCornerShape(50))
                        .background(if (leftPast) P.danger else P.inactive)
                        .clickable(onClickLabel = "Delete notification", onClick = onDelete),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Delete, "Delete notification", tint = if (leftPast) P.onDanger else P.text, modifier = Modifier.size(24.u))
                }
            }
        }
    }
}

@Composable
private fun MenuButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    reveal: () -> Float,
    hidden: Boolean,
    onClick: () -> Unit,
) {
    val fade by animateFloatAsState(if (hidden) 0f else 1f, tween(150), label = "menuFade")
    Box(
        Modifier
            .size(52.u)
            .graphicsLayer { val r = reveal() * fade; alpha = r; val sc = 0.7f + 0.3f * r; scaleX = sc; scaleY = sc }
            .clip(CircleShape)
            .background(P.inactive)
            .clickable(enabled = !hidden, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = P.text, modifier = Modifier.size(24.u))
    }
}
