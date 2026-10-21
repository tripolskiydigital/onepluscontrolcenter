package digital.tripolskiy.opshade

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku

/** Lists what the shade needs and opens the right system screen for each missing piece. */
class SetupActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, _ -> tick++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDemoExtra(intent)
        try { Shizuku.addRequestPermissionResultListener(shizukuListener) } catch (_: Throwable) {}
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides ShadePalette.from(this)) { Screen(tick) }
        }
    }

    // The setup screen is often already on top when the command arrives; handle it here too.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyDemoExtra(intent)
        tick++
    }

    private fun applyDemoExtra(i: Intent?) {
        if (i?.hasExtra("demo") == true) Demo.set(this, i.getBooleanExtra("demo", false))
        if (i?.hasExtra("testnotifs") == true) {
            if (i.getBooleanExtra("testnotifs", false)) TestNotifications.post(this) else TestNotifications.cancel(this)
        }
    }

    override fun onResume() {
        super.onResume()
        tick++
    }

    override fun onDestroy() {
        try { Shizuku.removeRequestPermissionResultListener(shizukuListener) } catch (_: Throwable) {}
        super.onDestroy()
    }

    private fun open(action: String, pkgUri: Boolean = false) {
        val i = Intent(action)
        if (pkgUri) i.data = Uri.parse("package:$packageName")
        try { startActivity(i) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    @Composable
    private fun Screen(@Suppress("UNUSED_PARAMETER") tick: Int) {
        val ctx: Context = this
        val nm = getSystemService(NotificationManager::class.java)
        val a11y = ShadeService.running != null
        val listener = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(NotifListener.component(ctx).flattenToString()) == true
        val shizuku = Shell.installedAndRunning()

        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF161616))
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("OnePlus Control", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Medium, fontFamily = Op.Display)
            Text(
                "OxygenOS-style notification shade and control center. Pull down from the status bar to open it; " +
                    "pull again (or use two fingers) for the full control center.",
                color = P.textSecondary, fontSize = 14.sp,
            )
            Spacer(Modifier.height(8.dp))
            Item("Accessibility service", "Catches the pull-down and draws the shade.", a11y) {
                open(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }
            Item("Notification access", "Shows, opens and clears notifications.", listener) {
                open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }
            Item("Modify system settings", "Brightness slider and auto-rotate.", Settings.System.canWrite(ctx)) {
                open(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri = true)
            }
            Item("Do Not Disturb access", "Do Not Disturb tile.", nm.isNotificationPolicyAccessGranted) {
                open(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            }
            Item(
                "Location and nearby devices", "Wi-Fi network name and Bluetooth state.",
                granted(Manifest.permission.ACCESS_FINE_LOCATION) && granted(Manifest.permission.BLUETOOTH_CONNECT),
            ) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH_CONNECT), 1)
            }
            Item(
                "Secure settings (adb)", "Eye Comfort tile. Grant once: adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS",
                granted(Manifest.permission.WRITE_SECURE_SETTINGS), null,
            )
            Item(
                "Shizuku", if (!shizuku) "Optional. Install and start Shizuku to switch Wi-Fi, Bluetooth, mobile data, airplane mode, " +
                    "dark mode and power saving directly. Without it those tiles open the system panel."
                else "Lets tiles switch radios and modes directly.",
                Shell.available(),
            ) { if (shizuku) Shell.requestPermission() else open(Settings.ACTION_SETTINGS) }

            if (Demo.enabled(ctx)) {
                // Shown only while it's on, so sample notifications can't be left on by accident.
                Item("Screenshot mode is on", "The shade shows sample notifications and neutral names. Tap to turn it off.", false) {
                    Demo.set(ctx, false); this@SetupActivity.tick++
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Edit tiles", color = P.text, fontSize = 16.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF242424))
                    .clickable { startActivity(Intent(ctx, TileEditActivity::class.java)) }
                    .padding(horizontal = 22.dp, vertical = 12.dp),
            )
            Text(
                "Open shade", color = P.onActive, fontSize = 16.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(P.active)
                    .clickable { ShadeService.running?.let { finish(); it.openShade() } }
                    .padding(horizontal = 22.dp, vertical = 12.dp),
            )
        }
    }

    @Composable
    private fun Item(title: String, sub: String, ok: Boolean, onFix: (() -> Unit)?) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF242424))
                .then(if (!ok && onFix != null) Modifier.clickable { onFix() } else Modifier)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(sub, color = P.textSecondary, fontSize = 13.sp)
            }
            Spacer(Modifier.size(12.dp))
            Text(
                if (ok) "On" else if (onFix != null) "Set up" else "Off",
                color = if (ok) P.onActive else Color.Black, fontSize = 13.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (ok) P.active else Color(0xFFD9D9D9))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
