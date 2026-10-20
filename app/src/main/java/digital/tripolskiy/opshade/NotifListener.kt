package digital.tripolskiy.opshade

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class NotifItem(
    val key: String,
    val pkg: String,
    /** Account or sub-text line above the title; the app name when there is none. */
    val header: String,
    val title: String,
    val text: String,
    val bigText: String?,
    val time: Long,
    val clearable: Boolean,
    val appIcon: ImageBitmap?,
    val largeIcon: ImageBitmap?,
    val actions: List<Notification.Action>,
    val contentIntent: PendingIntent?,
    val autoCancel: Boolean,
    val channelId: String? = null,
    /** Non-empty for a group: the individual notifications, newest first. */
    val children: List<NotifItem> = emptyList(),
)

class NotifListener : NotificationListenerService() {

    companion object {
        @Volatile var instance: NotifListener? = null
            private set
        private val _items = MutableStateFlow<List<NotifItem>>(emptyList())
        val items: StateFlow<List<NotifItem>> = _items

        fun component(ctx: Context) = ComponentName(ctx, NotifListener::class.java)
    }

    private val iconCache = HashMap<String, ImageBitmap?>()

    override fun onListenerConnected() {
        instance = this
        rebuild()
    }

    override fun onListenerDisconnected() {
        instance = null
        _items.value = emptyList()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) = rebuild()
    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) = rebuild()
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = rebuild()

    fun dismiss(item: NotifItem) {
        if (item.children.isEmpty()) cancelNotification(item.key)
        else (item.children.map { it.key } + item.key).forEach { cancelNotification(it) }
    }

    fun dismissAll() = cancelAllNotifications()

    /** "Remind me": hides the notification and brings it back after [millis]. */
    fun snooze(item: NotifItem, millis: Long) {
        (item.children.map { it.key } + item.key).distinct().forEach {
            try { snoozeNotification(it, millis) } catch (_: Exception) {}
        }
    }

    /** Re-reads the list, e.g. after screenshot mode was switched. */
    fun refresh() = rebuild()

    private fun rebuild() {
        if (Demo.enabled(this)) {
            _items.value = Demo.notifications(this)
            return
        }
        val active = try { activeNotifications } catch (_: Exception) { null } ?: return
        val order = currentRanking?.orderedKeys?.withIndex()?.associate { it.value to it.index } ?: emptyMap()
        val sorted = active
            .filter { it.packageName != packageName }
            .sortedBy { order[it.key] ?: Int.MAX_VALUE }

        val byGroup = sorted.groupBy { it.groupKey }
        val out = ArrayList<NotifItem>()
        val seenGroups = HashSet<String>()
        for (sbn in sorted) {
            val members = byGroup[sbn.groupKey].orEmpty()
            val isGroup = sbn.isGroup && members.size > 1
            if (!isGroup) {
                toItem(sbn)?.let(out::add)
                continue
            }
            if (!seenGroups.add(sbn.groupKey)) continue
            val summary = members.firstOrNull { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 }
            val kids = members.filter { it !== summary }
                .sortedByDescending { it.notification.`when` }
                .mapNotNull { toItem(it) }
            if (kids.size <= 1) {
                // A group of one reads better as a plain card.
                (kids.firstOrNull() ?: summary?.let { toItem(it) })?.let(out::add)
                continue
            }
            val head = (summary?.let { toItem(it) } ?: kids.first())
            out += head.copy(
                key = summary?.key ?: kids.first().key,
                clearable = kids.any { it.clearable },
                children = kids,
                time = kids.maxOf { it.time },
            )
        }
        _items.value = out
    }

    private fun toItem(sbn: StatusBarNotification): NotifItem? {
        val n = sbn.notification
        val ex: Bundle = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: ex.getCharSequence(Notification.EXTRA_TITLE_BIG)
            ?: ex.getCharSequence(Notification.EXTRA_TITLE)
        var text = ex.getCharSequence(Notification.EXTRA_TEXT)
        @Suppress("DEPRECATION")
        val messages = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (messages != null && messages.isNotEmpty()) {
            val last = messages.last() as? Bundle
            last?.getCharSequence("text")?.let { text = it }
        }
        val bigText = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
        if (title.isNullOrBlank() && text.isNullOrBlank()) return null

        val appLabel = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (_: Exception) {
            sbn.packageName
        }
        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        return NotifItem(
            key = sbn.key,
            pkg = sbn.packageName,
            header = sub?.takeIf { it.isNotBlank() } ?: appLabel,
            title = title?.toString().orEmpty(),
            text = text?.toString().orEmpty(),
            bigText = bigText?.toString()?.takeIf { it != text?.toString() },
            time = if (n.`when` > 0) n.`when` else sbn.postTime,
            clearable = sbn.isClearable,
            appIcon = appIcon(sbn.packageName),
            largeIcon = n.getLargeIcon()?.loadDrawable(this)?.let { bitmapOf(it, 96) },
            actions = n.actions?.toList().orEmpty(),
            contentIntent = n.contentIntent,
            autoCancel = n.flags and Notification.FLAG_AUTO_CANCEL != 0,
            channelId = n.channelId,
        )
    }

    private fun appIcon(pkg: String): ImageBitmap? = iconCache.getOrPut(pkg) {
        try { bitmapOf(packageManager.getApplicationIcon(pkg), 144) } catch (_: Exception) { null }
    }

    private fun bitmapOf(d: Drawable, px: Int): ImageBitmap = d.toBitmap(px, px).asImageBitmap()
}
