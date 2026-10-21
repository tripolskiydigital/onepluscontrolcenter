package digital.tripolskiy.opshade

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Icon
import android.util.Log

/**
 * Sample notifications for checking the cards: a conversation with Reply and Mark as read, a big
 * picture, a download with progress, long text with buttons, and a plain one with nothing to expand.
 * Replies and buttons land in [TestReceiver], never in a real app.
 *
 * `adb shell am start -n digital.tripolskiy.opshade/.SetupActivity --ez testnotifs true|false`
 */
object TestNotifications {
    const val CHANNEL = "opshade_test"
    private const val CHAT = 9001
    private const val PICTURE = 9002
    private const val DOWNLOAD = 9003
    private const val LONG = 9004
    private const val PLAIN = 9005
    private val ids = listOf(CHAT, PICTURE, DOWNLOAD, LONG, PLAIN)
    internal const val REPLY_KEY = "reply"
    private val me = Person.Builder().setName("You").build()
    private val anna = Person.Builder().setName("Anna").build()
    private val chat = mutableListOf("Are you coming tonight?" to anna, "We start at 7" to anna, "Bring the slides!" to anna)

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    fun post(ctx: Context) {
        nm(ctx).createNotificationChannel(NotificationChannel(CHANNEL, "Test notifications", NotificationManager.IMPORTANCE_LOW))
        postChat(ctx)
        notify(ctx, PICTURE, builder(ctx, "Photo shared", "Max shared a photo with you")
            .setStyle(Notification.BigPictureStyle().bigPicture(samplePicture()).setSummaryText("Max shared a photo with you")))
        notify(ctx, DOWNLOAD, builder(ctx, "Downloading report.pdf", "4.2 MB of 10 MB").setProgress(100, 42, false).setOngoing(true))
        notify(ctx, LONG, builder(ctx, "Weekly summary", "You walked 42 km this week, 12% more than last week. Your longest walk was on Saturday.")
            .setStyle(Notification.BigTextStyle().bigText(
                "You walked 42 km this week, 12% more than last week. Your longest walk was on Saturday: 11 km in 2 h 05 min. " +
                    "Keep it up to reach your monthly goal of 160 km. Tap to see the full report with daily charts and routes.",
            ))
            .addAction(action(ctx, "Archive", LONG))
            .addAction(action(ctx, "Share", LONG)))
        notify(ctx, PLAIN, builder(ctx, "Backup complete", "All photos are safe"))
    }

    fun cancel(ctx: Context) = ids.forEach { nm(ctx).cancel(it) }

    internal fun postChat(ctx: Context) {
        val style = Notification.MessagingStyle(me).setConversationTitle("Anna")
        chat.forEachIndexed { i, (text, who) -> style.addMessage(text, System.currentTimeMillis() - (chat.size - i) * 60_000L, who) }
        val reply = Notification.Action.Builder(
            Icon.createWithResource(ctx, R.drawable.ic_launcher), "Reply",
            PendingIntent.getBroadcast(
                ctx, CHAT, Intent(ctx, TestReceiver::class.java).setAction("reply"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            ),
        ).addRemoteInput(RemoteInput.Builder(REPLY_KEY).setLabel("Message Anna").build()).build()
        notify(ctx, CHAT, builder(ctx, "Anna", chat.last().first).setStyle(style).addAction(reply).addAction(action(ctx, "Mark as read", CHAT)))
    }

    internal fun addReply(text: String) { chat += text to me }

    private fun builder(ctx: Context, title: String, text: String) = Notification.Builder(ctx, CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle(title)
        .setContentText(text)
        .setOnlyAlertOnce(true)

    private fun action(ctx: Context, title: String, id: Int) = Notification.Action.Builder(
        Icon.createWithResource(ctx, R.drawable.ic_launcher), title,
        PendingIntent.getBroadcast(
            ctx, id * 10 + title.length, Intent(ctx, TestReceiver::class.java).setAction("dismiss").putExtra("id", id).putExtra("title", title),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ),
    ).build()

    private fun notify(ctx: Context, id: Int, b: Notification.Builder) = nm(ctx).notify(id, b.build())

    private fun samplePicture(): Bitmap {
        val bmp = Bitmap.createBitmap(720, 400, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 720f, 400f, 0xFF3D5AFE.toInt(), 0xFFFF7043.toInt(), Shader.TileMode.CLAMP) })
        c.drawCircle(560f, 120f, 70f, Paint().apply { color = 0xCCFFF59D.toInt() })
        return bmp
    }
}

class TestReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            "reply" -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(TestNotifications.REPLY_KEY)?.toString() ?: return
                Log.d("OpTest", "reply: $text")
                TestNotifications.addReply(text)
                TestNotifications.postChat(ctx)
            }
            "dismiss" -> {
                Log.d("OpTest", "action: ${intent.getStringExtra("title")}")
                ctx.getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra("id", 0))
            }
        }
    }
}
