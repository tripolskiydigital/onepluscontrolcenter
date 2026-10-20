package digital.tripolskiy.opshade

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Tile editor, opened from the "more" button in the control center. Changes are saved at once and
 * the shade picks them up live: hold and drag to reorder, "−" removes, "+" adds, tap a wide tile
 * to swap it, and any app can be added as a shortcut tile.
 */
class TileEditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalPalette provides ShadePalette.from(this)) { Editor() }
        }
    }

    @Composable
    private fun Editor() {
        val ctx = LocalContext.current
        val layout by TileLayoutStore.layout(ctx).collectAsState()
        val tiles = remember { HashMap<String, Tile?>() }
        fun ui(id: String): TileUi? = (tiles.getOrPut(id) { TileCatalog.create(id, ctx) })?.let {
            try { it.read(ctx) } catch (_: Exception) { null }
        }
        var pickHighlight by remember { mutableStateOf(-1) }
        var pickApp by remember { mutableStateOf(false) }

        Column(
            Modifier
                .fillMaxSize()
                .background(P.scrimTop)
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = P.text,
                    modifier = Modifier.size(40.dp).clip(CircleShape).clickable { finish() }.padding(8.dp),
                )
                Text("Edit tiles", color = P.text, fontSize = 22.sp, fontFamily = Op.Display, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f).padding(start = 8.dp))
                Text(
                    "Reset", color = P.text, fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { TileLayoutStore.save(ctx, TileLayoutStore.DEFAULT) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Icon(
                    Icons.Rounded.Tune, "Setup", tint = P.text,
                    modifier = Modifier.size(40.dp).clip(CircleShape)
                        .clickable { startActivity(Intent(ctx, SetupActivity::class.java)) }.padding(8.dp),
                )
            }

            Section("Large tiles", "Tap to choose which two tiles sit next to the sliders.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                layout.highlight.take(2).forEachIndexed { i, id ->
                    ui(id)?.let { s ->
                        HighlightTile(s, onClick = { pickHighlight = i }, onDetails = { pickHighlight = i },
                            modifier = Modifier.weight(1f).height(64.dp))
                    }
                }
            }

            Section(
                "Your tiles",
                "Hold and drag to reorder. The first five also show in the quick row; " +
                    "after twelve, tiles continue on the next page.",
            )
            ReorderGrid(
                ids = layout.grid,
                ui = ::ui,
                onReorder = { TileLayoutStore.save(ctx, layout.copy(grid = it)) },
                badge = Icons.Rounded.Remove,
                onBadge = { id -> TileLayoutStore.save(ctx, layout.copy(grid = layout.grid - id)) },
            )

            Section("Add tiles", "Tap a tile to add it. Tiles marked S switch directly only with Shizuku.")
            val unused = layout.unused()
            StaticGrid(unused, ::ui, needsShizuku = { tiles[it]?.needsShizuku == true }) { id ->
                TileLayoutStore.save(ctx, layout.copy(grid = layout.grid + id))
            }

            Section("App shortcuts", "Opens an app. Other apps' own quick-settings tiles can't be hosted outside the system.")
            Text(
                "Add app", color = P.onActive, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .padding(top = 4.dp, bottom = 32.dp)
                    .clip(RoundedCornerShape(50))
                    .background(P.active)
                    .clickable { pickApp = true }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }

        if (pickHighlight >= 0) {
            val slot = pickHighlight
            val other = layout.highlight.getOrNull(1 - slot)
            ChoiceDialog(
                title = "Large tile",
                options = TileCatalog.highlightable.filter { it != other },
                label = { ui(it)?.label ?: it },
                onDismiss = { pickHighlight = -1 },
            ) { id ->
                val old = layout.highlight[slot]
                val h = layout.highlight.toMutableList().also { it[slot] = id }
                // The replaced wide tile goes back among the round ones so nothing silently disappears.
                val g = (layout.grid - id).let { if (old != id && old !in it) it + old else it }
                TileLayoutStore.save(ctx, TileLayout(h, g))
                pickHighlight = -1
            }
        }
        if (pickApp) {
            AppPicker(onDismiss = { pickApp = false }) { pkg ->
                val id = AppTile.PREFIX + pkg
                if (id !in layout.grid) TileLayoutStore.save(ctx, layout.copy(grid = layout.grid + id))
                pickApp = false
            }
        }
    }
}

@Composable
private fun Section(title: String, hint: String) {
    Text(title, color = P.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 20.dp))
    Text(hint, color = P.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
}

@Composable
private fun TileCell(s: TileUi, badge: androidx.compose.ui.graphics.vector.ImageVector?, mark: Boolean, onBadge: (() -> Unit)?) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            CircleTile(s, 52.u, onClick = {}, onLongClick = {}, enabled = false)
            if (badge != null) {
                Icon(
                    badge, null, tint = P.onActive,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-4).dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(P.active)
                        .then(if (onBadge != null) Modifier.clickable { onBadge() } else Modifier)
                        .padding(3.dp),
                )
            }
            if (mark) {
                Text(
                    "S", color = P.textSecondary, fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 2.dp),
                )
            }
        }
        Text(
            s.label, color = P.text, fontSize = 12.sp, lineHeight = 14.sp, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
    }
}

private const val COLS = 4
private val CELL_H = 100.dp

/** Grid whose cells can be picked up with a long press and dropped on another slot. */
@Composable
private fun ReorderGrid(
    ids: List<String>,
    ui: (String) -> TileUi?,
    onReorder: (List<String>) -> Unit,
    badge: androidx.compose.ui.graphics.vector.ImageVector,
    onBadge: (String) -> Unit,
) {
    val view = LocalView.current
    var order by remember { mutableStateOf(ids) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(ids) { if (dragging == null) order = ids }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cellW = maxWidth / COLS
        val cwPx = with(LocalDensity.current) { cellW.toPx() }
        val chPx = with(LocalDensity.current) { CELL_H.toPx() }
        val rows = ((order.size + COLS - 1) / COLS).coerceAtLeast(1)
        Box(Modifier.fillMaxWidth().height(CELL_H * rows)) {
            order.forEach { id ->
                val s = ui(id)
                if (s != null) key(id) {
                    val idx = order.indexOf(id)
                    val slot = IntOffset(((idx % COLS) * cwPx).roundToInt(), ((idx / COLS) * chPx).roundToInt())
                    val anim by animateIntOffsetAsState(slot, spring(Spring.DampingRatioNoBouncy, 600f), label = "slot")
                    val isDrag = dragging == id
                    Box(
                        Modifier
                            .offset { if (isDrag) IntOffset(dragPos.x.roundToInt(), dragPos.y.roundToInt()) else anim }
                            .width(cellW).height(CELL_H)
                            .zIndex(if (isDrag) 1f else 0f)
                            .graphicsLayer { val sc = if (isDrag) 1.1f else 1f; scaleX = sc; scaleY = sc }
                            .pointerInput(id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        val i = order.indexOf(id)
                                        dragPos = Offset((i % COLS) * cwPx, (i / COLS) * chPx)
                                        dragging = id
                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    },
                                    onDrag = { ch, d ->
                                        ch.consume()
                                        dragPos += d
                                        val r = ((order.size + COLS - 1) / COLS).coerceAtLeast(1)
                                        val col = ((dragPos.x + cwPx / 2) / cwPx).toInt().coerceIn(0, COLS - 1)
                                        val row = ((dragPos.y + chPx / 2) / chPx).toInt().coerceIn(0, r - 1)
                                        val to = (row * COLS + col).coerceIn(0, order.size - 1)
                                        val from = order.indexOf(id)
                                        if (to != from) {
                                            order = order.toMutableList().apply { add(to, removeAt(from)) }
                                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        }
                                    },
                                    onDragEnd = { dragging = null; onReorder(order) },
                                    onDragCancel = { dragging = null; onReorder(order) },
                                )
                            },
                    ) {
                        TileCell(s, badge, mark = false) { onBadge(id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StaticGrid(ids: List<String>, ui: (String) -> TileUi?, needsShizuku: (String) -> Boolean, onTap: (String) -> Unit) {
    Column {
        ids.chunked(COLS).forEach { rowIds ->
            Row(Modifier.fillMaxWidth()) {
                rowIds.forEach { id ->
                    val s = ui(id)
                    Box(Modifier.weight(1f).height(CELL_H).clickable(enabled = s != null) { onTap(id) }) {
                        if (s != null) TileCell(s, Icons.Rounded.Add, mark = needsShizuku(id) && !Shell.available(), onBadge = null)
                    }
                }
                repeat(COLS - rowIds.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ChoiceDialog(title: String, options: List<String>, label: (String) -> String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(28.dp)).background(P.card).padding(vertical = 16.dp),
        ) {
            Text(title, color = P.text, fontSize = 20.sp, fontFamily = Op.Display, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            options.forEach { id ->
                Text(
                    label(id), color = P.text, fontSize = 16.sp,
                    modifier = Modifier.fillMaxWidth().clickable { onPick(id) }.padding(horizontal = 24.dp, vertical = 14.dp),
                )
            }
        }
    }
}

private data class AppEntry(val pkg: String, val label: String, val icon: ImageBitmap)

@Composable
private fun AppPicker(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val apps by produceState<List<AppEntry>?>(null) { value = withContext(Dispatchers.IO) { launchableApps(ctx) } }
    var query by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(28.dp)).background(P.card).padding(vertical = 16.dp).heightIn(max = 560.dp)) {
            Text("Add app", color = P.text, fontSize = 20.sp, fontFamily = Op.Display, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            BasicTextField(
                query, { query = it }, singleLine = true,
                textStyle = TextStyle(color = P.text, fontSize = 16.sp),
                cursorBrush = SolidColor(P.text),
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(P.inactive)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search", color = P.textSecondary, fontSize = 16.sp)
                    inner()
                },
            )
            val list = apps
            if (list == null) {
                Text("Loading…", color = P.textSecondary, modifier = Modifier.padding(24.dp))
            } else {
                LazyColumn {
                    items(list.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }, key = { it.pkg }) { a ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(a.pkg) }.padding(horizontal = 24.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(a.icon, null, modifier = Modifier.size(36.dp))
                            Text(a.label, color = P.text, fontSize = 16.sp, modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun launchableApps(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(main, 0)
        .map { it.activityInfo.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != ctx.packageName }
        .map { AppEntry(it.packageName, pm.getApplicationLabel(it).toString(), pm.getApplicationIcon(it).toBitmap(96, 96).asImageBitmap()) }
        .sortedBy { it.label.lowercase() }
}
