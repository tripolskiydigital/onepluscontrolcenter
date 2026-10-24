package digital.tripolskiy.opshade

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Drawable
import android.media.session.MediaSession
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
    /** Sent by us when an ongoing card is swiped away, e.g. stops a running timer. */
    val deleteIntent: PendingIntent? = null,
    val channelId: String? = null,
    /** Non-empty for a group: the individual notifications, newest first. */
    val children: List<NotifItem> = emptyList(),
    /** BigPictureStyle image. */
    val picture: ImageBitmap? = null,
    /** MessagingStyle history, oldest first: sender to text. */
    val messages: List<Pair<String, String>> = emptyList(),
    /** Progress bar: 0..1, or -1 for indeterminate; null when there is none. */
    val progress: Float? = null,
) {
    /**
     * Whether swiping the card away does something: the system cancels it, or, for an ongoing one
     * the system won't cancel, the app gets its delete intent (a Clocks timer stops).
     */
    val dismissable: Boolean
        get() = clearable || deleteIntent != null || children.any { it.dismissable }

    /** Whether expanding shows more than the collapsed card (the text may also just be ellipsized). */
    val hasMore: Boolean
        get() = children.isNotEmpty() || bigText != null || picture != null || messages.size > 1 || actions.isNotEmpty()
}

class NotifListener : NotificationListenerService() {

    companion object {
        @Volatile var instance: NotifListener? = null
            private set
        private val _items = MutableStateFlow<List<NotifItem>>(emptyList())
        val items: StateFlow<List<NotifItem>> = _items

        fun component(ctx: Context) = ComponentName(ctx, NotifListener::class.java)

        // Notification.FLAG_PROMOTED_ONGOING and EXTRA_REQUEST_PROMOTED_ONGOING (API 36), not in our SDK yet.
        private const val FLAG_PROMOTED_ONGOING = 0x00040000
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
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
        if (item.children.isEmpty()) dismissOne(item)
        else {
            item.children.forEach(::dismissOne)
            cancelNotification(item.key)
        }
    }

    /**
     * The system ignores cancels of ongoing notifications from a listener, and only sends the delete
     * intent for ones it cancels itself. So for an ongoing card send it ourselves, as Pixel's shade
     * does when such a card is swiped.
     */
    private fun dismissOne(item: NotifItem) {
        if (item.clearable) cancelNotification(item.key)
        else try { item.deleteIntent?.send() } catch (_: Exception) {}
    }

    fun dismissAll() = cancelAllNotifications()

    /** "Remind me": hides the notification and brings it back after [millis]. */
    fun snooze(item: NotifItem, millis: Long) {
        (item.children.map { it.key } + item.key).distinct().forEach {
            try { snoozeNotification(it, millis) } catch (_: Exception) {}
        }
    }

    /**
     * The tap target of the Live Update chip in the status bar (Android 16+ promoted ongoing
     * notifications). SystemUI chips only the notifications it actually promoted; apps that merely
     * asked for it count when nothing was promoted. With several, the most recently posted wins:
     * that is the one SystemUI puts first, next to the clock.
     */
    fun promotedContentIntent(): PendingIntent? {
        val active = try { activeNotifications } catch (_: Exception) { null } ?: return null
        val ongoing = active.filter { it.isOngoing && it.notification.contentIntent != null }
        val promoted = ongoing.filter { it.notification.flags and FLAG_PROMOTED_ONGOING != 0 }
            .ifEmpty { ongoing.filter { it.notification.extras.getBoolean(EXTRA_REQUEST_PROMOTED_ONGOING) } }
        return promoted.maxByOrNull { it.postTime }?.notification?.contentIntent
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
        val ranked = active
            .filter { it.packageName != packageName || it.notification.channelId == TestNotifications.CHANNEL }
            .sortedBy { order[it.key] ?: Int.MAX_VALUE }

        // Media notifications become players in the carousel, as on Pixel, and leave the list.
        val media = ranked.mapNotNull { sbn ->
            mediaToken(sbn)?.let { MediaRepo.Source(sbn.key, it, sbn.notification.contentIntent, sbn.notification.actions?.toList().orEmpty()) }
        }
        MediaRepo.update(this, media)
        val mediaKeys = media.map { it.key }.toSet()
        val sorted = ranked.filter { it.key !in mediaKeys }

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
        val history = messages.orEmpty().mapNotNull { m ->
            val b = m as? Bundle ?: return@mapNotNull null
            val body = b.getCharSequence("text")?.toString() ?: return@mapNotNull null
            @Suppress("DEPRECATION")
            val who = (b.getParcelable<android.app.Person>("sender_person"))?.name ?: b.getCharSequence("sender")
            (who?.toString() ?: "") to body
        }
        if (history.isNotEmpty()) text = history.last().second
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
            deleteIntent = n.deleteIntent,
            channelId = n.channelId,
            picture = picture(n),
            messages = history.takeLast(6),
            progress = progress(ex),
        )
    }

    private fun picture(n: Notification): ImageBitmap? = try {
        val ex = n.extras
        val bmp = ex.getParcelable(Notification.EXTRA_PICTURE, android.graphics.Bitmap::class.java)
            ?: ex.getParcelable(Notification.EXTRA_PICTURE_ICON, android.graphics.drawable.Icon::class.java)
                ?.loadDrawable(this)?.let { d -> d.toBitmap(d.intrinsicWidth.coerceIn(1, 1080), d.intrinsicHeight.coerceIn(1, 1080)) }
        bmp?.let { b ->
            // Keep memory sane: big pictures can be full-resolution photos.
            val f = (720f / maxOf(b.width, b.height)).coerceAtMost(1f)
            android.graphics.Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true).asImageBitmap()
        }
    } catch (_: Exception) {
        null
    }

    private fun progress(ex: Bundle): Float? {
        val max = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val indeterminate = ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        return when {
            indeterminate -> -1f
            max > 0 -> (ex.getInt(Notification.EXTRA_PROGRESS, 0).toFloat() / max).coerceIn(0f, 1f)
            else -> null
        }
    }

    /** The action [index] of the notification [key] (searched in groups too), for inline reply. */
    fun action(key: String, index: Int): Notification.Action? {
        val all = _items.value.flatMap { listOf(it) + it.children }
        return all.firstOrNull { it.key == key }?.actions?.getOrNull(index)
    }

    private fun mediaToken(sbn: StatusBarNotification): MediaSession.Token? = try {
        sbn.notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
    } catch (_: Exception) {
        null
    }

    private fun appIcon(pkg: String): ImageBitmap? = iconCache.getOrPut(pkg) {
        try { bitmapOf(packageManager.getApplicationIcon(pkg), 144) } catch (_: Exception) { null }
    }

    private fun bitmapOf(d: Drawable, px: Int): ImageBitmap = d.toBitmap(px, px).asImageBitmap()
}
