package digital.tripolskiy.opshade

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.nfc.NfcAdapter
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.DataSaverOn
import androidx.compose.material.icons.rounded.InvertColors
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.NoPhotography
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Wallet
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.graphics.drawable.toBitmap

class NfcTile : Tile("nfc") {
    override val needsShizuku = true

    override fun read(ctx: Context) = TileUi(id, "NFC", Icons.Rounded.Nfc, NfcAdapter.getDefaultAdapter(ctx)?.isEnabled == true)

    override suspend fun click(host: TileHost): Boolean? {
        val on = read(host.context).active
        if (Shell.run("svc nfc " + if (on) "disable" else "enable")) return !on
        host.launch(settingsIntent(Settings.ACTION_NFC_SETTINGS))
        return null
    }

    override fun longClick(host: TileHost) = host.launch(settingsIntent(Settings.ACTION_NFC_SETTINGS))
}

class DataSaverTile : Tile("datasaver") {
    override val needsShizuku = true

    override fun read(ctx: Context) = TileUi(
        id, "Data saver", Icons.Rounded.DataSaverOn,
        ctx.getSystemService(ConnectivityManager::class.java).restrictBackgroundStatus ==
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
    )

    override suspend fun click(host: TileHost): Boolean? {
        val on = read(host.context).active
        if (Shell.run("cmd netpolicy set restrict-background " + !on)) return !on
        host.launch(settingsIntent(Settings.ACTION_DATA_USAGE_SETTINGS))
        return null
    }
}

/**
 * Camera or microphone access (Android's sensor privacy toggles). "Active" means access is
 * allowed, as on Pixel's own tiles. There is no public API to read the state, so it is parsed
 * from `dumpsys sensor_privacy` through Shizuku.
 */
class SensorAccessTile(id: String, private val sensor: Int, private val name: String) : Tile(id) {
    override val needsShizuku = true
    @Volatile private var blocked = false

    override suspend fun prefetch(ctx: Context) {
        val dump = Shell.output("dumpsys sensor_privacy") ?: return
        blocked = parseBlocked(dump, sensor)
    }

    override fun read(ctx: Context): TileUi {
        val camera = sensor == CAMERA
        val icon: ImageVector = when {
            camera && blocked -> Icons.Rounded.NoPhotography
            camera -> Icons.Rounded.PhotoCamera
            blocked -> Icons.Rounded.MicOff
            else -> Icons.Rounded.Mic
        }
        return TileUi(id, name, icon, !blocked)
    }

    override suspend fun click(host: TileHost): Boolean? {
        val target = if (sensor == CAMERA) "camera" else "microphone"
        if (Shell.run("cmd sensor_privacy " + (if (blocked) "disable" else "enable") + " 0 " + target)) {
            blocked = !blocked
            return !blocked
        }
        host.launch(settingsIntent(Settings.ACTION_PRIVACY_SETTINGS))
        return null
    }

    companion object {
        const val MICROPHONE = 1
        const val CAMERA = 2

        /** Finds `sensor=N` and its software toggle (toggle_type=1); state_type=1 means privacy is on. */
        fun parseBlocked(dump: String, sensor: Int): Boolean {
            val start = Regex("sensor=$sensor\\b").find(dump) ?: return false
            val rest = dump.substring(start.range.last)
            val next = Regex("sensor=\\d").find(rest, 1)?.range?.first ?: rest.length
            val block = rest.substring(0, next)
            val toggle = Regex("toggle_type=1\\s+state_type=(\\d)").find(block) ?: return false
            return toggle.groupValues[1] == "1"
        }
    }
}

/** Keeps the screen on by raising the screen timeout; the previous timeout is restored after. */
class KeepScreenOnTile : Tile("caffeine") {
    private val prefs = "caffeine"

    override fun read(ctx: Context): TileUi {
        val on = ctx.getSharedPreferences(prefs, Context.MODE_PRIVATE).contains("previous")
        return TileUi(id, "Keep screen on", Icons.Rounded.Coffee, on)
    }

    override suspend fun click(host: TileHost): Boolean? {
        val ctx = host.context
        if (!Settings.System.canWrite(ctx)) {
            host.launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).setData(android.net.Uri.parse("package:" + ctx.packageName)))
            return null
        }
        val sp = ctx.getSharedPreferences(prefs, Context.MODE_PRIVATE)
        val cr = ctx.contentResolver
        return if (sp.contains("previous")) {
            Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, sp.getInt("previous", 30_000))
            sp.edit().remove("previous").apply()
            false
        } else {
            sp.edit().putInt("previous", Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 30_000)).apply()
            Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, Int.MAX_VALUE)
            true
        }
    }
}

/** A Secure on/off setting written with WRITE_SECURE_SETTINGS (granted over adb), or Shizuku as a fallback. */
open class SecureSettingTile(
    id: String,
    private val key: String,
    private val name: String,
    private val icon: ImageVector,
    private val settingsAction: String,
) : Tile(id) {
    // Since Android 12 apps may not read most @hide settings (extra dim is one); then the value
    // comes from Shizuku, or from what this tile last wrote.
    @Volatile private var known: Boolean? = null

    override suspend fun prefetch(ctx: Context) {
        if (readDirect(ctx) == null) Shell.output("settings get secure $key")?.trim()?.let { known = it == "1" }
    }

    private fun readDirect(ctx: Context): Boolean? = try {
        Settings.Secure.getInt(ctx.contentResolver, key, 0) == 1
    } catch (_: SecurityException) {
        null
    }

    override fun read(ctx: Context) = TileUi(id, name, icon, readDirect(ctx) ?: known ?: false)

    override suspend fun click(host: TileHost): Boolean? {
        val ctx = host.context
        val on = read(ctx).active
        val ok = try {
            Settings.Secure.putInt(ctx.contentResolver, key, if (on) 0 else 1)
        } catch (_: SecurityException) {
            Shell.run("settings put secure $key " + if (on) 0 else 1)
        }
        if (ok) known = !on
        if (ok) return !on
        host.launch(settingsIntent(settingsAction))
        return null
    }

    override fun longClick(host: TileHost) = host.launch(settingsIntent(settingsAction))
}

class ExtraDimTile : SecureSettingTile(
    "extradim", "reduce_bright_colors_activated", "Extra dim", Icons.Rounded.BrightnessLow, Settings.ACTION_ACCESSIBILITY_SETTINGS,
)

class InversionTile : SecureSettingTile(
    "invert", "accessibility_display_inversion_enabled", "Color inversion", Icons.Rounded.InvertColors, Settings.ACTION_ACCESSIBILITY_SETTINGS,
)

/** Opens something; no state. */
class LaunchTile(
    id: String,
    private val name: String,
    private val icon: ImageVector,
    private val intent: (Context) -> Intent?,
) : Tile(id) {
    override fun read(ctx: Context) = TileUi(id, name, icon, false, stateless = true)

    override suspend fun click(host: TileHost): Boolean? {
        intent(host.context)?.let { host.launch(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return null
    }
}

/**
 * Shortcut to any app (id "app:<package>"). Other apps' own quick-settings tiles can't be hosted:
 * binding a TileService needs a permission only the system holds.
 */
class AppTile(val pkg: String, ctx: Context) : Tile(PREFIX + pkg) {
    private val label: String
    private val iconBitmap: ImageBitmap?

    init {
        val pm = ctx.packageManager
        val info = try { pm.getApplicationInfo(pkg, 0) } catch (_: Exception) { null }
        label = info?.let { pm.getApplicationLabel(it).toString() } ?: pkg
        iconBitmap = info?.let { try { pm.getApplicationIcon(it).toBitmap(128, 128).asImageBitmap() } catch (_: Exception) { null } }
    }

    override fun read(ctx: Context) = TileUi(id, label, Icons.Rounded.Apps, false, stateless = true, bitmap = iconBitmap)

    override suspend fun click(host: TileHost): Boolean? {
        host.context.packageManager.getLaunchIntentForPackage(pkg)?.let { host.launch(it) }
        return null
    }

    override fun longClick(host: TileHost) = host.launch(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(android.net.Uri.parse("package:$pkg")),
    )

    companion object {
        const val PREFIX = "app:"
    }
}

internal fun extraTile(id: String): Tile? = when (id) {
    "nfc" -> NfcTile()
    "datasaver" -> DataSaverTile()
    "camera_access" -> SensorAccessTile(id, SensorAccessTile.CAMERA, "Camera access")
    "mic_access" -> SensorAccessTile(id, SensorAccessTile.MICROPHONE, "Mic access")
    "caffeine" -> KeepScreenOnTile()
    "extradim" -> ExtraDimTile()
    "invert" -> InversionTile()
    "wallet" -> LaunchTile(id, "Wallet", Icons.Rounded.Wallet) { it.packageManager.getLaunchIntentForPackage("com.google.android.apps.walletnfcrel") }
    "alarm" -> LaunchTile(id, "Alarm", Icons.Rounded.Alarm) { Intent(AlarmClock.ACTION_SHOW_ALARMS) }
    "timer" -> LaunchTile(id, "Timer", Icons.Rounded.Timer) { Intent(AlarmClock.ACTION_SHOW_TIMERS) }
    "calculator" -> LaunchTile(id, "Calculator", Icons.Rounded.Calculate) {
        Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR)
    }
    "camera" -> LaunchTile(id, "Camera", Icons.Rounded.PhotoCamera) { Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA) }
    else -> null
}
