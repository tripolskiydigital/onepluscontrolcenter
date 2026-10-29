package digital.tripolskiy.opshade

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.app.UiModeManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AirplanemodeActive
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Share
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class TileUi(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val active: Boolean,
    /** Tiles that only open something have no on/off state and always draw inactive. */
    val stateless: Boolean = false,
    /** App shortcut tiles draw the app's own icon instead of [icon]. */
    val bitmap: ImageBitmap? = null,
)

/** What a tile may ask the shade to do. */
interface TileHost {
    val context: Context
    /** Collapses the shade and starts the activity or settings panel. */
    fun launch(intent: Intent)
    fun collapse()
    fun globalAction(action: Int)
}

abstract class Tile(val id: String) {
    abstract fun read(ctx: Context): TileUi
    /** Slow state reads (shell commands) go here; called off the main thread before [read]. */
    open suspend fun prefetch(ctx: Context) {}
    /** Shown in the editor: this tile only switches directly with Shizuku. */
    open val needsShizuku: Boolean = false
    /** Returns the expected new "active" value for an optimistic update, or null to wait for a refresh. */
    abstract suspend fun click(host: TileHost): Boolean?
    open fun longClick(host: TileHost) {}
}

internal fun settingsIntent(action: String) = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

class WifiTile : Tile("wifi") {
    override val needsShizuku = true

    @Suppress("DEPRECATION")
    override fun read(ctx: Context): TileUi {
        val wm = ctx.getSystemService(WifiManager::class.java)
        val on = wm.isWifiEnabled
        val ssid = if (on) wm.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } else null
        return TileUi(id, if (ssid != null && Demo.enabled(ctx)) Demo.WIFI else ssid ?: "Wi-Fi", Icons.Rounded.Wifi, on)
    }

    override suspend fun click(host: TileHost): Boolean? {
        val on = read(host.context).active
        if (Shell.run("svc wifi " + if (on) "disable" else "enable")) return !on
        host.launch(settingsIntent(Settings.Panel.ACTION_WIFI))
        return null
    }

    override fun longClick(host: TileHost) = host.launch(settingsIntent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
}

class BluetoothTile : Tile("bt") {
    override val needsShizuku = true

    private fun adapter(ctx: Context): BluetoothAdapter? = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    override fun read(ctx: Context): TileUi {
        val on = try { adapter(ctx)?.isEnabled == true } catch (_: SecurityException) { false }
        return TileUi(id, "Bluetooth", Icons.Rounded.Bluetooth, on)
    }

    override suspend fun click(host: TileHost): Boolean? {
        val on = read(host.context).active
        if (Shell.run("svc bluetooth " + if (on) "disable" else "enable")) return !on
        host.launch(settingsIntent(if (on) Settings.ACTION_BLUETOOTH_SETTINGS else BluetoothAdapter.ACTION_REQUEST_ENABLE))
        return null
    }

    override fun longClick(host: TileHost) = host.launch(settingsIntent(Settings.ACTION_BLUETOOTH_SETTINGS))
}

class MobileDataTile : Tile("cell") {
    override val needsShizuku = true

    override fun read(ctx: Context): TileUi {
        // On multi-SIM phones the switch lives in mobile_data<subId> of the default data SIM;
        // the plain mobile_data key is a stale legacy copy.
        val sub = SubscriptionManager.getDefaultDataSubscriptionId()
        val valid = sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID
        val tm = ctx.getSystemService(TelephonyManager::class.java)?.let { if (valid) it.createForSubscriptionId(sub) else it }
        val name = tm?.simOperatorName?.takeIf { it.isNotBlank() } ?: "Mobile data"
        val key = if (valid) "mobile_data$sub" else "mobile_data"
        val on = Settings.Global.getInt(ctx.contentResolver, key, Settings.Global.getInt(ctx.contentResolver, "mobile_data", 0)) == 1
        return TileUi(id, if (Demo.enabled(ctx)) Demo.CARRIER else name, Icons.Rounded.SwapVert, on)
    }

    override suspend fun click(host: TileHost): Boolean? {
        val on = read(host.context).active
        if (Shell.run("svc data " + if (on) "disable" else "enable")) return !on
        host.launch(settingsIntent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
        return null
    }

    override fun longClick(host: TileHost) = host.launch(settingsIntent(Settings.ACTION_DATA_USAGE_SETTINGS))
}

class FlashlightTile(ctx: Context) : Tile("flashlight") {
    private val cm = ctx.getSystemService(CameraManager::class.java)
    private val cameraId: String? = cm.cameraIdList.firstOrNull {
        cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    }
    @Volatile private var on = false

    init {
        cm.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(id: String, enabled: Boolean) {
                if (id == cameraId) on = enabled
            }
        }, Handler(Looper.getMainLooper()))
    }

    override fun read(ctx: Context) = TileUi(id, "Flashlight", Icons.Rounded.FlashlightOn, on)

    override suspend fun click(host: TileHost): Boolean? {
        val id = cameraId ?: return null
        return try {
            cm.setTorchMode(id, !on); !on
        } catch (_: Exception) {
            null
        }
    }
}

class AirplaneTile : Tile("airplane") {
    override val needsShizuku = true

    override fun read(ctx: Context) = TileUi(
        id, "Airplane mode", Icons.Rounded.AirplanemodeActive,
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    )

    override suspend fun click(host: TileHost): Boolean? {
        val on = read(host.context).active
        if (Shell.run("cmd connectivity airplane-mode " + if (on) "disable" else "enable")) return !on
        host.launch(settingsIntent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
        return null
    }
}

class RotationTile : Tile("rotation") {
    override fun read(ctx: Context) = TileUi(
        id, "Auto-rotate", Icons.Rounded.ScreenRotation,
        Settings.System.getInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
    )

    override suspend fun click(host: TileHost): Boolean? {
        val ctx = host.context
        val on = read(ctx).active
        if (Settings.System.canWrite(ctx)) {
            Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 0 else 1)
            return !on
        }
        host.launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).setData(android.net.Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return null
    }
}

class QuickShareTile : Tile("quickshare") {
    override fun read(ctx: Context) = TileUi(id, "Quick Share", Icons.Rounded.Share, false, stateless = true)

    override suspend fun click(host: TileHost): Boolean? {
        host.launch(Intent().setComponent(ComponentName("com.google.android.gms", "com.google.android.gms.nearby.sharing.main.MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return null
    }
}

/** OxygenOS calls Night Light "Eye Comfort". */
class EyeComfortTile : Tile("night") {
    override fun read(ctx: Context) = TileUi(
        id, "Eye Comfort", Icons.Rounded.Visibility,
        Settings.Secure.getInt(ctx.contentResolver, "night_display_activated", 0) == 1
    )

    override suspend fun click(host: TileHost): Boolean? {
        val ctx = host.context
        val on = read(ctx).active
        val ok = try {
            Settings.Secure.putInt(ctx.contentResolver, "night_display_activated", if (on) 0 else 1)
        } catch (_: SecurityException) {
            Shell.run("settings put secure night_display_activated " + if (on) 0 else 1)
        }
        if (ok) return !on
        host.launch(settingsIntent(Settings.ACTION_NIGHT_DISPLAY_SETTINGS))
        return null
    }

    override fun longClick(host: TileHost) = host.launch(settingsIntent(Settings.ACTION_NIGHT_DISPLAY_SETTINGS))
}

class BatterySaverTile : Tile("battery") {
    override val needsShizuku = true

    override fun read(ctx: Context) = TileUi(
        id, "Power saving mode", Icons.Rounded.BatterySaver,
        ctx.getSystemService(PowerManager::class.java).isPowerSaveMode
    )

    override suspend fun click(host: TileHost): Boolean? {
        val on = read(host.context).active
        if (Shell.run("settings put global low_power " + if (on) 0 else 1)) return !on
        host.launch(settingsIntent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
        return null
    }

    override fun longClick(host: TileHost) = host.launch(settingsIntent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
}

class DarkModeTile : Tile("darkmode") {
    override val needsShizuku = true

    override fun read(ctx: Context): TileUi {
        val night = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val um = ctx.getSystemService(UiModeManager::class.java)
        return TileUi(id, "Dark mode", Icons.Rounded.Contrast, um.nightMode == UiModeManager.MODE_NIGHT_YES || (um.nightMode != UiModeManager.MODE_NIGHT_NO && night))
    }

    override suspend fun click(host: TileHost): Boolean? {
        val on = read(host.context).active
        if (Shell.run("cmd uimode night " + if (on) "no" else "yes")) return !on
        host.launch(settingsIntent(Settings.ACTION_DISPLAY_SETTINGS))
        return null
    }
}

class ScreenshotTile : Tile("screenshot") {
    override fun read(ctx: Context) = TileUi(id, "Screenshot", Icons.Rounded.Screenshot, false, stateless = true)

    override suspend fun click(host: TileHost): Boolean? {
        host.collapse()
        // Let the collapse animation finish so the shade isn't in the picture.
        delay(450)
        host.globalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
        return null
    }
}

class HotspotTile : Tile("hotspot") {
    override fun read(ctx: Context) = TileUi(id, "Personal hotspot", Icons.Rounded.WifiTethering, false, stateless = true)

    override suspend fun click(host: TileHost): Boolean? {
        host.launch(Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return null
    }
}

class DndTile : Tile("dnd") {
    override fun read(ctx: Context): TileUi {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        return TileUi(id, "Do Not Disturb", Icons.Rounded.DoNotDisturbOn, nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    override suspend fun click(host: TileHost): Boolean? {
        val nm = host.context.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            host.launch(settingsIntent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return null
        }
        val on = read(host.context).active
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        return !on
    }

    override fun longClick(host: TileHost) = host.launch(settingsIntent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS))
}

class LocationTile : Tile("location") {
    override val needsShizuku = true

    override fun read(ctx: Context) = TileUi(
        id, "Location", Icons.Rounded.LocationOn,
        ctx.getSystemService(LocationManager::class.java).isLocationEnabled
    )

    override suspend fun click(host: TileHost): Boolean? {
        val on = read(host.context).active
        if (Shell.run("cmd location set-location-enabled " + !on)) return !on
        host.launch(settingsIntent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        return null
    }
}

/** The tiles currently placed in the shade, in order. */
data class PlacedTiles(val highlight: List<Tile>, val grid: List<Tile>)

/** Holds the placed tiles (following [TileLayoutStore]) and the latest state the UI draws from. */
class TileRepository(private val ctx: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cache = HashMap<String, Tile>()

    private fun tile(id: String): Tile? = cache[id] ?: TileCatalog.create(id, ctx)?.also { cache[id] = it }

    private fun place(layout: TileLayout) = PlacedTiles(
        highlight = layout.highlight.mapNotNull(::tile),
        grid = layout.grid.mapNotNull(::tile),
    )

    private val _placed = MutableStateFlow(place(TileLayoutStore.layout(ctx).value))
    val placed: StateFlow<PlacedTiles> = _placed

    private val _state = MutableStateFlow<Map<String, TileUi>>(emptyMap())
    val state: StateFlow<Map<String, TileUi>> = _state
    private var refreshJob: Job? = null

    init {
        scope.launch {
            TileLayoutStore.layout(ctx).collect {
                _placed.value = place(it)
                refresh()
            }
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            val p = _placed.value
            val all = (p.highlight + p.grid).distinctBy { it.id }
            withContext(Dispatchers.IO) { all.forEach { t -> try { t.prefetch(ctx) } catch (_: Exception) {} } }
            _state.value = all.associate { t ->
                t.id to try { t.read(ctx) } catch (_: Exception) { _state.value[t.id] ?: TileUi(t.id, t.id, Icons.Rounded.Share, false) }
            }
        }
    }

    fun click(tile: Tile, host: TileHost) {
        // Without Shizuku these tiles can only open the system panel; say why, or it looks broken.
        if (tile.needsShizuku && !Shell.available()) {
            Toast.makeText(host.context, "Shizuku isn't running, so this opens settings. Start Shizuku to switch it here.", Toast.LENGTH_LONG).show()
        }
        scope.launch {
            val expected = tile.click(host)
            if (expected != null) {
                _state.value[tile.id]?.let { _state.value = _state.value + (tile.id to it.copy(active = expected)) }
            }
            // System state lands asynchronously (radios take a moment); re-read a few times.
            for (d in longArrayOf(400, 1200, 2500)) {
                delay(d)
                refreshJob?.join()
                refresh()
                refreshJob?.join()
                if (expected != null) {
                    // Keep the optimistic value until the system catches up, so the tile doesn't flicker back.
                    val now = _state.value[tile.id]
                    if (now != null && now.active != expected && d < 2500) {
                        _state.value = _state.value + (tile.id to now.copy(active = expected))
                    }
                }
            }
        }
    }
}
