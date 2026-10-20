package digital.tripolskiy.opshade

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/**
 * Screenshot mode: sample notifications and neutral Wi-Fi / carrier names instead of the
 * owner's real ones, so screenshots can be published. Turn it on or off with
 * `adb shell am start -n digital.tripolskiy.opshade/.SetupActivity --ez demo true|false`.
 */
object Demo {
    private const val PREFS = "demo"

    fun enabled(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", false)

    fun set(ctx: Context, on: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
        NotifListener.instance?.refresh()
        ShadeService.running?.tiles?.refresh()
    }

    const val WIFI = "Home Wi-Fi"
    const val CARRIER = "Carrier"

    fun notifications(ctx: Context): List<NotifItem> {
        val now = System.currentTimeMillis()
        val min = 60_000L
        fun icon(pkg: String): ImageBitmap? = try {
            ctx.packageManager.getApplicationIcon(pkg).toBitmap(144, 144).asImageBitmap()
        } catch (_: Exception) {
            null
        }
        fun item(key: String, pkg: String, header: String, title: String, text: String, ago: Long, big: String? = null) = NotifItem(
            key = "demo:$key", pkg = pkg, header = header, title = title, text = text, bigText = big,
            time = now - ago * min, clearable = true, appIcon = icon(pkg), largeIcon = null,
            actions = emptyList(), contentIntent = null, autoCancel = false,
        )
        val messages = "com.google.android.apps.messaging"
        return listOf(
            item("cal", "com.google.android.calendar", "alex@example.com", "Design review", "10:00 – 10:45", 5),
            item(
                "msg", messages, "Messages", "Anna", "See you at 7?", 12,
            ).copy(
                children = listOf(
                    item("msg1", messages, "Messages", "Anna", "See you at 7?", 12),
                    item("msg2", messages, "Messages", "Max", "Sent the photos", 26),
                    item("msg3", messages, "Messages", "Kate", "Thanks!", 41),
                ),
            ),
            item("mail", "com.google.android.gm", "alex@example.com", "Your order has shipped", "Arriving Thursday. Track your package in the app.", 33),
            item("photos", "com.google.android.apps.photos", "Photos", "Memories from last year", "Take a look back at your trip", 58),
        )
    }
}
