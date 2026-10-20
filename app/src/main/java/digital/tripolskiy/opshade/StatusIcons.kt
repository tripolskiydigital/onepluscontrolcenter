package digital.tripolskiy.opshade

import android.app.AlarmManager
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay

/** What the real status bar would show on the right, read from the same system state. */
data class StatusState(
    val alarm: Boolean,
    val vpn: Boolean,
    val bluetooth: Boolean,
    val ringer: Int,
    val airplane: Boolean,
    /** 0..4, or null when there is no mobile service. */
    val mobileLevel: Int?,
    /** 0..3 (SystemUI's ic_wifi_0..3), or null when not connected. */
    val wifiLevel: Int?,
    val battery: Int,
    val charging: Boolean,
    val powerSave: Boolean,
)

object StatusIcons {
    private const val SYSTEMUI = "com.android.systemui"
    private var res: Resources? = null
    private val cache = HashMap<String, ImageBitmap?>()

    /**
     * The status bar's own vector icons, loaded from SystemUI's resources so the shade header
     * matches the real status bar exactly. Null if a name is missing on this build.
     */
    fun icon(ctx: Context, name: String, maxHeightPx: Int): ImageBitmap? = cache.getOrPut("$name@$maxHeightPx") {
        try {
            val r = res ?: ctx.packageManager.getResourcesForApplication(SYSTEMUI).also { res = it }
            val id = r.getIdentifier(name, "drawable", SYSTEMUI)
            if (id == 0) return@getOrPut null
            val d = r.getDrawable(id, null)
            // The status bar draws these at their own size (14x8dp key, 17x12dp signal...);
            // only the few large ones (alarm, ringer) are scaled down to the row height.
            val scale = (maxHeightPx.toFloat() / d.intrinsicHeight).coerceAtMost(1f)
            d.toBitmap((d.intrinsicWidth * scale).toInt().coerceAtLeast(1), (d.intrinsicHeight * scale).toInt().coerceAtLeast(1)).asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    fun read(ctx: Context): StatusState {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val vpn = try { cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true } } catch (_: Exception) { false }
        val airplane = Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

        val wm = ctx.getSystemService(WifiManager::class.java)
        val wifiOnNetwork = try {
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                (wm.isWifiEnabled && wm.connectionInfo?.networkId != -1)
        } catch (_: Exception) { false }
        val wifiLevel = if (!wifiOnNetwork) null else {
            val max = wm.maxSignalLevel.coerceAtLeast(1)
            ((wm.calculateSignalLevel(wm.connectionInfo.rssi) * 3f / max) + 0.5f).toInt().coerceIn(0, 3)
        }

        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val mobileLevel = if (airplane) null else try {
            tm?.signalStrength?.level?.takeIf { tm.simState == TelephonyManager.SIM_STATE_READY }
        } catch (_: Exception) { null }

        val bt = try {
            val a = ctx.getSystemService(BluetoothManager::class.java)?.adapter
            a != null && a.isEnabled && listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET, 22 /* LE_AUDIO */)
                .any { a.getProfileConnectionState(it) == BluetoothProfile.STATE_CONNECTED }
        } catch (_: Exception) { false }

        val batt = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batt?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: 0
        val status = batt?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        return StatusState(
            alarm = ctx.getSystemService(AlarmManager::class.java)?.nextAlarmClock != null,
            vpn = vpn,
            bluetooth = bt,
            ringer = ctx.getSystemService(AudioManager::class.java).ringerMode,
            airplane = airplane,
            mobileLevel = mobileLevel,
            wifiLevel = wifiLevel,
            battery = level,
            charging = charging,
            powerSave = ctx.getSystemService(PowerManager::class.java).isPowerSaveMode,
        )
    }
}

/**
 * Right side of the shade header, drawn like Pixel's status bar: alarm, VPN, Bluetooth,
 * ringer, airplane, mobile signal, Wi-Fi, then the battery.
 */
@Composable
fun SystemStatusIcons(tint: Color, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf(StatusIcons.read(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            state = StatusIcons.read(ctx)
        }
    }
    val density = LocalDensity.current
    val iconPx = with(density) { 15.dp.roundToPx() }
    // Pixel hides the vibrate icon unless "Show vibrate icon in status bar" is on.
    val showVibrate = remember {
        try { Settings.Secure.getInt(ctx.contentResolver, "status_bar_show_vibrate_icon", 0) == 1 } catch (_: Exception) { false }
    }
    val names = buildList {
        if (state.alarm) add("stat_sys_alarm")
        if (state.vpn) add("stat_sys_vpn_ic")
        if (state.bluetooth) add("stat_sys_data_bluetooth_connected")
        when (state.ringer) {
            AudioManager.RINGER_MODE_VIBRATE -> if (showVibrate) add("stat_sys_ringer_vibrate")
            AudioManager.RINGER_MODE_SILENT -> add("stat_sys_ringer_silent")
        }
        if (state.airplane) add("stat_sys_airplane_mode")
        state.mobileLevel?.let { add("ic_mobile_${it}_4_bar") }
        state.wifiLevel?.let { add("ic_wifi_$it") }
    }
    val filter = ColorFilter.tint(tint)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        names.forEach { n ->
            StatusIcons.icon(ctx, n, iconPx)?.let { bmp ->
                Image(bmp, null, colorFilter = filter, modifier = Modifier.size(with(density) { bmp.width.toDp() }, with(density) { bmp.height.toDp() }))
            }
        }
        BatteryPill(state, tint)
    }
}

/**
 * Pixel's battery: a rounded pill filled to the charge level (green while charging, amber in
 * power saving, red when low), with a bolt beside it while charging.
 */
@Composable
private fun BatteryPill(s: StatusState, tint: Color) {
    val fill = when {
        s.charging -> Color(0xFF34C759)
        s.powerSave -> Color(0xFFFFC233)
        s.battery <= 15 -> Color(0xFFFF5449)
        else -> tint
    }
    // Measured on the Pixel 10 Pro's status bar: a 21.7x13dp pill, then a 1.1dp gap and an 8x9dp bolt.
    Canvas(Modifier.size(if (s.charging) 30.8.dp else 21.7.dp, 13.dp)) {
        val bodyW = 21.7.dp.toPx()
        val h = size.height
        val r = CornerRadius(4.dp.toPx())
        drawRoundRect(tint.copy(alpha = 0.4f), size = Size(bodyW, h), cornerRadius = r)
        clipRect(right = bodyW * s.battery / 100f) {
            drawRoundRect(fill, size = Size(bodyW, h), cornerRadius = r)
        }
        if (s.charging) {
            val x = bodyW + 1.1.dp.toPx()
            val w = size.width - x
            val bh = 9.dp.toPx()
            val top = (h - bh) / 2f
            val bolt = Path().apply {
                moveTo(x + w * 0.62f, top)
                lineTo(x, top + bh * 0.58f)
                lineTo(x + w * 0.45f, top + bh * 0.58f)
                lineTo(x + w * 0.35f, top + bh)
                lineTo(x + w, top + bh * 0.42f)
                lineTo(x + w * 0.55f, top + bh * 0.42f)
                close()
            }
            drawPath(bolt, tint)
        }
    }
}
