package digital.tripolskiy.opshade

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Which tiles are in the shade: two wide tiles on top, then the round tiles in order. */
data class TileLayout(val highlight: List<String>, val grid: List<String>) {
    /** Tiles not placed anywhere, for the editor's "available" list. */
    fun unused(): List<String> = TileCatalog.builtIn.filter { it !in highlight && it !in grid }
}

/** Every tile the shade knows, by id. App shortcuts ("app:<package>") are created on demand. */
object TileCatalog {
    /** Tiles that can be wide: they have an on/off state and a details screen. */
    val highlightable = listOf("wifi", "bt", "cell", "nfc", "location", "dnd", "darkmode", "airplane")

    val builtIn = listOf(
        "wifi", "bt", "cell", "flashlight", "airplane", "rotation", "quickshare", "night", "battery",
        "darkmode", "screenshot", "hotspot", "dnd", "location", "nfc", "datasaver", "camera_access",
        "mic_access", "caffeine", "extradim", "invert", "wallet", "alarm", "timer", "calculator", "camera",
    )

    fun create(id: String, ctx: Context): Tile? = when {
        id.startsWith(AppTile.PREFIX) -> AppTile(id.removePrefix(AppTile.PREFIX), ctx)
        else -> when (id) {
            "wifi" -> WifiTile()
            "bt" -> BluetoothTile()
            "cell" -> MobileDataTile()
            "flashlight" -> FlashlightTile(ctx)
            "airplane" -> AirplaneTile()
            "rotation" -> RotationTile()
            "quickshare" -> QuickShareTile()
            "night" -> EyeComfortTile()
            "battery" -> BatterySaverTile()
            "darkmode" -> DarkModeTile()
            "screenshot" -> ScreenshotTile()
            "hotspot" -> HotspotTile()
            "dnd" -> DndTile()
            "location" -> LocationTile()
            else -> extraTile(id)
        }
    }
}

/** Saves the layout in SharedPreferences and shares it between the shade and the editor. */
object TileLayoutStore {
    // Order copied from the OnePlus 12's sysui_qs_tiles, with Pixel equivalents for OnePlus-only tiles.
    val DEFAULT = TileLayout(
        highlight = listOf("wifi", "bt"),
        grid = listOf(
            "cell", "flashlight", "airplane", "rotation", "quickshare", "night", "battery", "darkmode",
            "screenshot", "hotspot", "dnd", "location",
        ),
    )

    private var flow: MutableStateFlow<TileLayout>? = null

    @Synchronized
    fun layout(ctx: Context): StateFlow<TileLayout> = flow ?: MutableStateFlow(load(ctx)).also { flow = it }

    fun save(ctx: Context, layout: TileLayout) {
        val clean = layout.copy(highlight = layout.highlight.take(2), grid = layout.grid.distinct().filter { it !in layout.highlight })
        prefs(ctx).edit()
            .putString("highlight", clean.highlight.joinToString(","))
            .putString("grid", clean.grid.joinToString(","))
            .apply()
        (layout(ctx) as MutableStateFlow).value = clean
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("tiles", Context.MODE_PRIVATE)

    private fun load(ctx: Context): TileLayout {
        val p = prefs(ctx)
        val h = p.getString("highlight", null)?.split(",")?.filter { it.isNotBlank() }
        val g = p.getString("grid", null)?.split(",")?.filter { it.isNotBlank() }
        return if (h == null || g == null) DEFAULT else TileLayout(h, g)
    }
}
