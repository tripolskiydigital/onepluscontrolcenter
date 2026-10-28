package digital.tripolskiy.opshade

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawBehind
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlin.math.abs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun frac(v: Float, start: Float, end: Float) = ((v - start) / (end - start)).coerceIn(0f, 1f)

/**
 * Read-only view of the expansion for drawing. Animated values are only read inside layout,
 * offset and graphicsLayer lambdas, so an animation frame re-runs placement and drawing but
 * never recomposes the panel. That is what keeps it at 120 Hz.
 */
@Stable
private class Progress(private val controller: ShadeController, val px: Float, val landscape: Boolean) {
    /** 0..1: closed to notification stage. */
    fun open() = controller.expansion.value.coerceIn(0f, 1f)
    /** 0..1: notification stage to full control center. Landscape always shows the full control center. */
    fun q() = if (landscape) 1f else (controller.expansion.value - 1f).coerceIn(0f, 1f)
    /** How far the notification list has given way to the control center (never in landscape). */
    fun listQ() = if (landscape) 0f else q()
    fun offset(x: Float, y: Float) = IntOffset((x * px).roundToInt(), (y * px).roundToInt())
    /** Content slides down 36dp while it fades in during the first pull. */
    fun shift() = -(1f - open()) * 36f * px
}

/**
 * The whole shade. Positions are the OnePlus 12 layout measured from its view hierarchy
 * (uiautomator dump), in OnePlus dp, interpolated between the notification stage (q = 0)
 * and the full control center (q = 1).
 */
@Composable
fun ShadePanel(controller: ShadeController, tiles: TileRepository, host: ShadeService, blurEnabled: Boolean) {
    val ctx = LocalContext.current
    val config = LocalConfiguration.current
    // Rebuilt when the system theme (light/dark, wallpaper colours) changes.
    val palette = remember(config.uiMode, config.hashCode()) { ShadePalette.from(ctx) }
    val states by tiles.state.collectAsState()
    val placed by tiles.placed.collectAsState()
    val notifs by NotifListener.items.collectAsState()
    val media by MediaRepo.items.collectAsState()
    val showCcMedia by remember(controller) { derivedStateOf { controller.expansion.value > 1.3f } }
    // Cards the user dismissed, hidden right away. If the system keeps one (it refused the cancel),
    // it comes back after a moment instead of leaving a hole in the list.
    val dismissed = remember { mutableStateMapOf<String, Long>() }
    val visibleNotifs = remember(notifs, dismissed.toMap()) { notifs.filter { it.key !in dismissed } }
    LaunchedEffect(notifs, dismissed.size) {
        val live = notifs.map { it.key }.toSet()
        dismissed.keys.filter { it !in live }.forEach { dismissed.remove(it) }
        if (dismissed.isNotEmpty()) {
            delay(2000)
            val now = System.currentTimeMillis()
            dismissed.entries.filter { now - it.value >= 2000 }.map { it.key }.forEach { dismissed.remove(it) }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Scale from the short side, so turning the phone doesn't blow the shade up.
        val unit = minOf(maxWidth.value, maxHeight.value) / 360f
        // Landscape: tiles in a scrolling column on the left, players and notifications on the right.
        val landscape = maxWidth > maxHeight
        val showList by remember(controller, landscape) { derivedStateOf { landscape || controller.expansion.value < 1.98f } }
        val listScrolls by remember(controller, landscape) { derivedStateOf { landscape || controller.expansion.value < 1.5f } }
        CompositionLocalProvider(LocalUnit provides unit, LocalPalette provides palette) {
            val pxPerU = with(LocalDensity.current) { 1.u.toPx() }
            val p = remember(controller, pxPerU, landscape) { Progress(controller, pxPerU, landscape) }
            val maxStage = if (landscape) 1f else 2f
            val scrimAlpha = if (blurEnabled) 0.84f else 0.97f

            // Scrim: blurred backdrop plus a tint, both ramped in over the first part of the pull.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = frac(p.open(), 0f, 0.4f) * scrimAlpha }
                    .background(Brush.verticalGradient(listOf(palette.scrimTop, palette.scrimBottom)))
                    .panelDrag(controller)
                    .tapToClose(controller),
            )

            // Notifications (behind the tiles, so the control center can slide over them).
            val nested = remember(controller, maxStage) { shadeNestedScroll(controller, maxStage) }
            if (showList) Box(
                Modifier
                    .fillMaxSize()
                    .padding(start = if (landscape) 368.u else 0.u, top = if (landscape) 24.u else 334.u)
                    .panelDrag(controller, maxStage)
                    .tapToClose(controller)
                    .graphicsLayer {
                        val q = p.listQ()
                        alpha = frac(p.open(), 0.3f, 1f) * (1f - frac(q, 0f, 0.6f))
                        translationY = p.shift() * 2f + q * 220f * p.px
                    },
            ) {
                val listState = rememberLazyListState()
                val stack = with(LocalDensity.current) { StackMetrics(STACK_STEP.u.toPx(), STACK_PEEK.u.toPx(), 8.u.toPx(), STACK_OVERSCAN.u.toPx()) }
                val scope = rememberCoroutineScope()
                // Only one card's swipe menu is open at a time, and scrolling folds it.
                var menuKey by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) menuKey = null }
                // The list stays composed while the shade is closed, and new notifications arrive
                // above the anchored item; start every opening at the top, with menus folded.
                val closed by remember(controller) { derivedStateOf { controller.expansion.value == 0f } }
                // Also on opening: a player can be inserted above the anchored first card meanwhile.
                LaunchedEffect(closed) { menuKey = null; listState.scrollToItem(0) }
                LazyColumn(
                    Modifier
                        .stackOverscan(stack.overscan)
                        .stackHandle(listState, controller, maxStage, stack) { scope.launch { listState.animateScrollBy(stack.step * 2f) } }
                        .nestedScroll(nested),
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 16.u, end = 16.u,
                        bottom = 24.u + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + STACK_OVERSCAN.u,
                    ),
                    userScrollEnabled = listScrolls,
                ) {
                    if (media.isNotEmpty()) {
                        item(key = "media") {
                            MediaCarousel(media, host, Modifier.padding(bottom = 8.u).animateItem())
                        }
                    }
                    itemsIndexed(visibleNotifs, key = { _, it -> it.key }, contentType = { _, _ -> NOTIF }) { index, n ->
                        NotificationCard(
                            n,
                            onOpen = { item ->
                                host.sendPendingIntent(item.contentIntent)
                                if (item.autoCancel) NotifListener.instance?.dismiss(item)
                            },
                            onDismiss = { item ->
                                // Drop the card at once so the rest slide up into its place.
                                dismissed[item.key] = System.currentTimeMillis()
                                NotifListener.instance?.dismiss(item)
                            },
                            onAction = { item, i ->
                                val a = item.actions.getOrNull(i)
                                if (a?.remoteInputs?.any { it.allowFreeFormInput } == true) host.openReply(item.key, i)
                                // Buttons that open an app close the shade; others (Archive, Mark as read) keep it open.
                                else host.sendPendingIntent(a?.actionIntent, closeShade = a?.actionIntent?.isActivity == true)
                            },
                            onSnooze = { item ->
                                dismissed[item.key] = System.currentTimeMillis()
                                NotifListener.instance?.snooze(item, 60 * 60 * 1000L)
                            },
                            onSettings = { item ->
                                host.launch(
                                    if (item.channelId != null) {
                                        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                            .putExtra(Settings.EXTRA_APP_PACKAGE, item.pkg)
                                            .putExtra(Settings.EXTRA_CHANNEL_ID, item.channelId)
                                    } else {
                                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, item.pkg)
                                    },
                                )
                            },
                            menuOpen = menuKey == n.key,
                            onMenuOpenChange = { open -> menuKey = if (open) n.key else if (menuKey == n.key) null else menuKey },
                            // Later cards slide under earlier ones in the stack at the bottom.
                            modifier = Modifier
                                .zIndex(-index.toFloat())
                                .stacked(listState, n.key, stack)
                                .padding(bottom = 8.u)
                                .animateItem(),
                        )
                    }
                    // Clear all sits after the last card, as on Pixel, instead of floating over the list.
                    if (visibleNotifs.any { it.clearable }) {
                        item(key = "clear_all") {
                            Box(Modifier.fillMaxWidth().padding(top = 8.u).animateItem(), contentAlignment = Alignment.Center) {
                                Box(
                                    Modifier
                                        .size(48.u)
                                        .clip(CircleShape)
                                        .background(palette.clearAll)
                                        .clickable {
                                            NotifListener.instance?.dismissAll()
                                            controller.close()
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Rounded.Close, "Clear all", tint = palette.text, modifier = Modifier.size(26.u))
                                }
                            }
                        }
                    }
                }
            }

            // Everything above the notifications. It is only as tall as the tiles so the list below
            // keeps its touches; drags that start on a tile still move the panel. In landscape it is
            // a column on the left that scrolls when it doesn't fit.
            val leftScroll = rememberScrollState()
            val nestedLeft = remember(controller) { shadeNestedScroll(controller, 1f) }
            Box(
                if (landscape) Modifier.width(360.u).fillMaxHeight().nestedScroll(nestedLeft).verticalScroll(leftScroll)
                else Modifier,
            ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .layout { m, c ->
                        val h = (lerp(332f, 640f, p.q()) * p.px).roundToInt()
                        val pl = m.measure(c.copy(minHeight = h, maxHeight = h))
                        layout(pl.width, h) { pl.place(0, 0) }
                    }
                    .then(if (landscape) Modifier else Modifier.panelDrag(controller))
                    .tapToClose(controller)
                    .graphicsLayer { alpha = frac(p.open(), 0.1f, 0.6f); translationY = p.shift() },
            ) {
                StatusRow(
                    Modifier
                        .offset { p.offset(0f, lerp(5f, 37f, p.q())) }
                        .fillMaxWidth().height(27.u).padding(horizontal = 24.u),
                )
                ClockAndDate(p)

                // Settings gear moves left to make room for the "more" button in the control center.
                IconButton(Icons.Outlined.Settings, { p.offset(lerp(300f, 252f, p.q()), lerp(32f, 64f, p.q())) }) {
                    host.launch(Intent(Settings.ACTION_SETTINGS))
                }
                val moreVisible by remember(controller, landscape) { derivedStateOf { landscape || controller.expansion.value > 1.01f } }
                if (moreVisible) {
                    Box(Modifier.graphicsLayer { alpha = p.q() }) {
                        IconButton(Icons.Rounded.MoreVert, { p.offset(300f, 64f) }) {
                            host.launch(Intent(ctx, TileEditActivity::class.java))
                        }
                    }
                }

                placed.highlight.take(2).forEachIndexed { i, t ->
                    states[t.id]?.let { s ->
                        HighlightTile(s, onClick = { tiles.click(t, host) }, onDetails = { t.longClick(host) },
                            modifier = Modifier.offset { p.offset(24f, lerp(84f, 135f, p.q()) + 82f * i) }.size(148.u, 66.u))
                    }
                }
                Sliders(Modifier.offset { p.offset(188f, lerp(84f, 135f, p.q())) })

                TileGrid(p, controller, tiles, placed.grid, states, host)

                Handle(
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = 318.u)
                        .graphicsLayer { alpha = 1f - frac(p.q(), 0f, 0.4f) },
                )
            }
            }

            // In the control center the players sit under the tile grid, as in Pixel's QS.
            if (showCcMedia && media.isNotEmpty() && !landscape) {
                MediaCarousel(
                    media, host,
                    Modifier
                        .padding(horizontal = 16.u)
                        .offset { p.offset(0f, 590f) }
                        .graphicsLayer { alpha = frac(p.q(), 0.6f, 1f); translationY = (1f - p.q()) * 40f * p.px },
                )
            }

        }
    }
}

private const val NOTIF = "notif"
/** Scroll distance, in u, over which a card sinks one layer deeper into the stack. */
private const val STACK_STEP = 90f
/** How far, in u, each layer's edge shows below the one in front of it. */
private const val STACK_PEEK = 7f
/** Extra list height below the screen, in u, so the cards behind the stack get composed. */
private const val STACK_OVERSCAN = 240f

private class StackMetrics(val step: Float, val peek: Float, val gap: Float, val overscan: Float)

/** Where the front card's bottom rests (item bottom, gap included), in list coordinates. */
private fun stackLine(info: LazyListLayoutInfo, m: StackMetrics) =
    info.viewportEndOffset - info.afterContentPadding - 2 * m.peek

/**
 * OxygenOS stacks the notifications that run off the bottom of the list. A card whose bottom
 * crosses the stack line stops there and the cards behind it pile up under it, each showing only a
 * narrower edge [StackMetrics.peek] lower; two layers show, deeper ones fade in as they come up.
 * Scrolling peels cards off the top of the stack, so nothing jumps. Reads the layout in the draw
 * phase only.
 */
private fun Modifier.stacked(state: LazyListState, key: Any, m: StackMetrics) = graphicsLayer {
    val info = state.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return@graphicsLayer
    val line = stackLine(info, m)
    val bottom = (item.offset + item.size).toFloat()
    if (bottom <= line) return@graphicsLayer
    // A card taller than a stack step would vanish under the one before it while its top is still
    // in view; its top never rises above a step over the line, so it is held there instead.
    val room = (item.offset - (line - m.step)).coerceAtLeast(0f)
    val depth = minOf((bottom - line) / m.step, room / m.step)
    val layer = depth.coerceAtMost(2f)
    translationY = maxOf(line + m.peek * layer - bottom, -room)
    val sc = 1f - 0.045f * layer
    scaleX = sc
    scaleY = sc
    transformOrigin = TransformOrigin(0.5f, 1f)
    alpha = (1f - 0.15f * (depth - 1f).coerceIn(0f, 1f)) * (1f - (depth - 2f).coerceIn(0f, 1f))
}

/**
 * Makes the list [extra] px taller than its slot, hanging below the screen, and clips what is
 * drawn there: the cards behind the stack then exist to be drawn. The bottom content padding grows
 * by the same amount, so the list still ends where it did.
 */
private fun Modifier.stackOverscan(extra: Float) = layout { m, c ->
    val e = extra.roundToInt()
    val p = m.measure(c.copy(minHeight = c.maxHeight + e, maxHeight = c.maxHeight + e))
    layout(p.width, c.maxHeight) { p.place(0, 0) }
}.drawWithContent { clipRect(bottom = size.height - extra) { this@drawWithContent.drawContent() } }

/**
 * The stack is the handle, as on OxygenOS: a push up that starts on it closes the shade instead of
 * scrolling, and a tap pulls cards out of it. A pull down still scrolls the list back, and sideways
 * swipes still reach the card. Watches on the initial pass, so the cards never see a gesture it
 * takes, not even as a press.
 */
private fun Modifier.stackHandle(
    state: LazyListState,
    controller: ShadeController,
    maxStage: Float,
    m: StackMetrics,
    onTap: () -> Unit,
) = pointerInput(state, controller, maxStage) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val info = state.layoutInfo
        val line = stackLine(info, m)
        val stacked = info.visibleItemsInfo.any { it.contentType == NOTIF && it.offset + it.size > line }
        // A little above the front card's bottom edge too, so the handle is easy to hit.
        if (!stacked || down.position.y < line - m.gap - m.peek * 2) return@awaitEachGesture
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var dragging = false
        while (true) {
            val ch = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            tracker.addPosition(ch.uptimeMillis, ch.position)
            val total = ch.position - down.position
            if (!ch.pressed) {
                if (dragging) controller.settle(tracker.calculateVelocity().y, maxStage)
                else if (total.getDistance() < slop) onTap()
                ch.consume()
                break
            }
            if (dragging) {
                controller.dragBy(ch.position.y - ch.previousPosition.y, maxStage)
                ch.consume()
            } else if (abs(total.y) > slop && abs(total.y) > abs(total.x)) {
                if (total.y > 0) break
                dragging = true
                controller.dragBy(total.y + slop, maxStage)
                ch.consume()
            } else if (abs(total.x) > slop) break
        }
    }
}

/** Closes the shade on a tap that no child (tile, card) handled. */
private fun Modifier.tapToClose(controller: ShadeController) = pointerInput(controller) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val up = waitForUpOrCancellation()
        if (up != null && (up.position - down.position).getDistance() < 20f) controller.close()
    }
}

/** Vertical drags anywhere on the panel that no child claims: pull down expands, push up collapses. */
private fun Modifier.panelDrag(controller: ShadeController, maxStage: Float = 2f) = pointerInput(controller, maxStage) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var started = false
        val slopChange = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
            change.consume()
            controller.dragBy(over, maxStage)
            started = true
        }
        if (slopChange != null || started) {
            verticalDrag(slopChange?.id ?: down.id) { change ->
                tracker.addPosition(change.uptimeMillis, change.position)
                controller.dragBy(change.position.y - change.previousPosition.y, maxStage)
                change.consume()
            }
            controller.settle(tracker.calculateVelocity().y, maxStage)
        }
    }
}

/**
 * Lets the notification list hand over to the panel: pulling down at the top of the list
 * expands to the control center, pushing up when the list can't scroll closes the shade.
 * Only a gesture that starts at the list's edge moves the panel: if this gesture scrolled the
 * list, reaching the end stops there instead of carrying on into closing the shade.
 */
private fun shadeNestedScroll(controller: ShadeController, maxStage: Float) = object : NestedScrollConnection {
    private var draggingPanel = false
    private var scrolledList = false

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        val e = controller.expansion.value
        // While the panel is between stages, it keeps the gesture.
        if (draggingPanel || (e > 1.001f && available.y < 0) || (e < 0.999f)) {
            draggingPanel = true
            controller.dragBy(available.y, maxStage)
            return available
        }
        return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        if (consumed.y != 0f) scrolledList = true
        if (available.y == 0f || scrolledList) return Offset.Zero
        draggingPanel = true
        controller.dragBy(available.y, maxStage)
        return available
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        scrolledList = false
        if (draggingPanel) {
            draggingPanel = false
            controller.settle(available.y, maxStage)
            return available
        }
        return Velocity.Zero
    }
}

@Composable
private fun IconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, position: Density.() -> IntOffset, onClick: () -> Unit) {
    Box(
        Modifier
            .offset(position)
            .size(36.u)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = P.text, modifier = Modifier.size(26.u))
    }
}

@Composable
private fun rememberNow(): Date {
    val now by produceState(Date()) {
        while (true) {
            value = Date()
            delay(1000L * (60 - (System.currentTimeMillis() / 1000 % 60)).coerceAtLeast(1))
        }
    }
    return now
}

/**
 * Clock and date. Small and on one line in the notification stage; in the control center the
 * clock grows and the date drops below it.
 */
@Composable
private fun ClockAndDate(p: Progress) {
    val ctx = LocalContext.current
    val now = rememberNow()
    val is24 = android.text.format.DateFormat.is24HourFormat(ctx)
    val time = SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.getDefault()).format(now)
    val date = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(now)
    val big = 31f
    val small = 17f
    var clockWidthPx by remember { mutableIntStateOf(0) }

    Text(
        time,
        color = P.text, fontSize = big.us, lineHeight = (big * 1.15f).us, fontFamily = Op.Display, fontWeight = FontWeight.Medium,
        maxLines = 1, softWrap = false,
        onTextLayout = { clockWidthPx = it.size.width },
        modifier = Modifier
            .offset { p.offset(24f, lerp(36.5f, 62f, p.q())) }
            .graphicsLayer {
                val s = lerp(small / big, 1f, p.q())
                scaleX = s; scaleY = s; transformOrigin = TransformOrigin(0f, 0f)
            },
    )
    Text(
        date, color = P.text, fontSize = 17.us, fontFamily = Op.Display, fontWeight = FontWeight.Medium, maxLines = 1,
        modifier = Modifier.offset {
            val q = p.q()
            val inlineX = 24f + clockWidthPx / p.px * small / big + 6f
            p.offset(lerp(inlineX, 24f, q), lerp(37f, 98f, q))
        },
    )
}

@Composable
private fun StatusRow(modifier: Modifier) {
    val ctx = LocalContext.current
    val tick = rememberNow()
    val carrier = remember(tick) {
        if (Demo.enabled(ctx)) Demo.CARRIER else ctx.getSystemService(TelephonyManager::class.java)?.networkOperatorName.orEmpty()
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(carrier, color = P.text, fontSize = 13.us, fontFamily = Op.Body, maxLines = 1, modifier = Modifier.weight(1f))
        SystemStatusIcons(P.text)
    }
}

private fun batteryInfo(ctx: Context): Pair<Int, Boolean> {
    val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 0 to false
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
    val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    return level to plugged
}

@Composable
private fun Sliders(modifier: Modifier) {
    val ctx = LocalContext.current
    val tick = rememberNow()
    var bright by remember { mutableFloatStateOf(Brightness.read(ctx)) }
    var auto by remember { mutableStateOf(Brightness.isAuto(ctx)) }
    var vol by remember { mutableFloatStateOf(MediaVolume.read(ctx)) }
    var muted by remember { mutableStateOf(MediaVolume.isMuted(ctx)) }
    // Re-read when the shade reopens or values change elsewhere.
    LaunchedEffect(tick) {
        while (true) {
            bright = Brightness.read(ctx); auto = Brightness.isAuto(ctx)
            vol = MediaVolume.read(ctx); muted = MediaVolume.isMuted(ctx)
            delay(1500)
        }
    }
    Box(modifier) {
        VerticalSlider(
            value = bright,
            onValueChange = { bright = it; Brightness.write(ctx, it) },
            icon = if (auto) Icons.Rounded.BrightnessAuto else Icons.Rounded.LightMode,
            iconTint = P.onActive,
            onIconClick = { auto = !auto; Brightness.setAuto(ctx, auto) },
            modifier = Modifier.size(66.u, 148.u),
        )
        VerticalSlider(
            value = if (muted) 0f else vol,
            onValueChange = { vol = it; muted = false; MediaVolume.write(ctx, it) },
            icon = if (muted || vol == 0f) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
            iconTint = P.onActive,
            onIconClick = { MediaVolume.toggleMute(ctx); muted = MediaVolume.isMuted(ctx) },
            steps = MediaVolume.steps(ctx),
            modifier = Modifier.offset(x = 82.u).size(66.u, 148.u),
        )
    }
}

/**
 * Tiles. In the notification stage the first five sit in a row of 50dp circles (QQS); in the
 * control center they form 4x3 pages of 52dp circles with labels, swiped sideways when there are
 * more than twelve. The first five travel to their grid cells; the rest fade in row by row
 * (OplusQSAnimator's delays: row 2 from 0.15, row 3 from 0.55 of the expansion).
 */
@Composable
private fun TileGrid(
    p: Progress,
    controller: ShadeController,
    tiles: TileRepository,
    grid: List<Tile>,
    states: Map<String, TileUi>,
    host: ShadeService,
) {
    val pages = ((grid.size + PAGE - 1) / PAGE).coerceAtLeast(1)
    val page = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val atFullStage by remember(controller, p) { derivedStateOf { p.q() > 0.99f } }
    // Back to the first page whenever the control center folds up, so the quick row shows page 1.
    LaunchedEffect(atFullStage) { if (!atFullStage) page.snapTo(0f) }
    LaunchedEffect(pages) { if (page.value > pages - 1) page.snapTo((pages - 1).toFloat()) }
    val widthU = 360f

    Box(
        Modifier
            .offset { p.offset(0f, lerp(247f, 299f, p.q())) }
            .fillMaxWidth()
            // In the quick row stage this box only covers the row, so it can't catch touches meant for the list.
            .layout { m, c ->
                val h = (lerp(54f, 280f, p.q()) * p.px).roundToInt()
                val pl = m.measure(c.copy(minHeight = h, maxHeight = h))
                layout(pl.width, h) { pl.place(0, 0) }
            }
            .pointerInput(pages) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (pages <= 1 || p.q() < 0.99f) return@awaitEachGesture
                    val tracker = VelocityTracker()
                    var drag = awaitHorizontalTouchSlopOrCancellation(down.id) { ch, over ->
                        ch.consume()
                        scope.launch { page.snapTo((page.value - over / (widthU * p.px)).coerceIn(0f, pages - 1f)) }
                    } ?: return@awaitEachGesture
                    horizontalDrag(drag.id) { ch ->
                        tracker.addPosition(ch.uptimeMillis, ch.position)
                        val dx = ch.position.x - ch.previousPosition.x
                        ch.consume()
                        scope.launch { page.snapTo((page.value - dx / (widthU * p.px)).coerceIn(0f, pages - 1f)) }
                    }
                    val v = tracker.calculateVelocity().x
                    val target = when {
                        v < -600f -> kotlin.math.ceil(page.value)
                        v > 600f -> kotlin.math.floor(page.value)
                        else -> kotlin.math.round(page.value)
                    }.coerceIn(0f, pages - 1f)
                    scope.launch { page.animateTo(target, spring(dampingRatio = 1f, stiffness = 500f)) }
                }
            },
    ) {
        grid.forEachIndexed { i, tile ->
            val s = states[tile.id] ?: return@forEachIndexed
            val onPage = i / PAGE
            val slot = i % PAGE
            val col = slot % 4
            val row = slot / 4
            val gridX = 10f + 85f * col + 16.5f
            val gridY = 88f * row
            val inQqs = i < 5
            val alphaOf: (Float) -> Float = { q ->
                when {
                    inQqs -> 1f
                    onPage > 0 -> frac(q, 0.55f, 1f)
                    row == 1 -> frac(q, 0.15f, 0.6f)
                    else -> frac(q, 0.55f, 1f)
                }
            }
            // Hidden tiles must not take taps meant for the notifications underneath.
            val interactive by remember(controller, i, p) {
                derivedStateOf { alphaOf(p.q()) > 0.5f }
            }
            val pageShift = { (onPage - page.value) * widthU }
            Box(
                Modifier
                    .offset {
                        val q = p.q()
                        if (inQqs) p.offset(lerp(24f + 65.33f * i - 1f, gridX + pageShift(), q), lerp(0f, gridY, q))
                        else p.offset(gridX + pageShift(), lerp(gridY - 60f - 20f * row, gridY, q))
                    }
                    .graphicsLayer {
                        val q = p.q()
                        alpha = alphaOf(q)
                        // Drawn at the grid size (52dp) and scaled down to the quick row's 50dp.
                        val sc = lerp(50f / 52f, 1f, q)
                        scaleX = sc; scaleY = sc; transformOrigin = TransformOrigin(0f, 0f)
                    },
            ) {
                CircleTile(
                    s, 52.u,
                    enabled = interactive,
                    onClick = { tiles.click(tile, host) },
                    onLongClick = { tile.longClick(host) },
                )
            }
            TileLabel(
                s.label,
                Modifier
                    .offset { p.offset(gridX - 16.5f + pageShift(), gridY + 56f) }
                    .width(85.u)
                    .graphicsLayer { val q = p.q(); alpha = alphaOf(q) * frac(q, 0.5f, 1f) },
            )
        }
        if (pages > 1) {
            PageDots(
                pages, { page.value },
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (88f * 3 + 8f).u)
                    .graphicsLayer { alpha = frac(p.q(), 0.7f, 1f) },
            )
        }
    }
}

private const val PAGE = 12

@Composable
private fun PageDots(count: Int, position: () -> Float, modifier: Modifier) {
    val on = P.text
    val off = P.text.copy(alpha = 0.3f)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.u)) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(6.u)
                    .graphicsLayer { alpha = 1f }
                    .drawBehind {
                        val d = (1f - kotlin.math.abs(position() - i)).coerceIn(0f, 1f)
                        drawCircle(androidx.compose.ui.graphics.lerp(off, on, d))
                    },
            )
        }
    }
}
