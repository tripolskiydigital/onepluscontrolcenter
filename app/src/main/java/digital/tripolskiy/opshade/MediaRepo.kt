package digital.tripolskiy.opshade

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow

data class MediaAction(val action: String, val name: String, val icon: ImageBitmap?, val extras: Bundle?)

/** One player in the carousel, built from a media notification's session, as Pixel's SystemUI does. */
data class MediaItem(
    val key: String,
    val token: MediaSession.Token,
    val pkg: String,
    val appIcon: ImageBitmap?,
    val title: String,
    val artist: String,
    val art: ImageBitmap?,
    /** Strongest colour of the artwork, for the play button. */
    val accent: Color?,
    val duration: Long,
    val state: Int,
    val position: Long,
    /** elapsedRealtime of [position]; while playing the position advances from here at [speed]. */
    val positionTime: Long,
    val speed: Float,
    val actions: Long,
    val custom: List<MediaAction>,
    val openIntent: PendingIntent?,
) {
    val playing get() = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
    fun has(action: Long) = actions and action != 0L

    fun positionAt(now: Long): Long {
        if (!playing || positionTime <= 0) return position
        val p = position + ((now - positionTime) * speed).toLong()
        return if (duration > 0) p.coerceIn(0, duration) else p.coerceAtLeast(0)
    }
}

/**
 * Keeps a MediaController per media notification and publishes players in notification order.
 * Fed by [NotifListener]; controllers report changes through callbacks on the main thread.
 */
object MediaRepo {
    private class Entry(
        val controller: MediaController,
        val callback: MediaController.Callback,
        var key: String,
        var intent: PendingIntent?,
        var actions: List<Notification.Action>,
    )

    private val main = Handler(Looper.getMainLooper())
    private val entries = LinkedHashMap<MediaSession.Token, Entry>()
    private val artCache = HashMap<String, Pair<ImageBitmap?, Color?>>()
    private val iconCache = HashMap<String, ImageBitmap?>()
    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
    val items: StateFlow<List<MediaItem>> = _items
    private var appContext: Context? = null

    data class Source(
        val key: String,
        val token: MediaSession.Token,
        val contentIntent: PendingIntent?,
        /** The media notification's own buttons, the fallback when the session ignores us. */
        val actions: List<Notification.Action>,
    )

    /** Called with the media notifications currently posted, newest ranking first. */
    fun update(ctx: Context, sources: List<Source>) {
        appContext = ctx.applicationContext
        val wanted = sources.associateBy { it.token }
        (entries.keys - wanted.keys).forEach { t -> entries.remove(t)?.let { it.controller.unregisterCallback(it.callback) } }
        sources.forEach { s ->
            val e = entries[s.token]
            if (e != null) {
                e.key = s.key; e.intent = s.contentIntent; e.actions = s.actions
            } else {
                val c = try { MediaController(ctx, s.token) } catch (_: Exception) { return@forEach }
                val cb = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
                    override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
                    override fun onSessionDestroyed() = publish()
                }
                c.registerCallback(cb, main)
                entries[s.token] = Entry(c, cb, s.key, s.contentIntent, s.actions)
            }
        }
        // Keep the notification order.
        val ordered = LinkedHashMap<MediaSession.Token, Entry>()
        sources.forEach { s -> entries[s.token]?.let { ordered[s.token] = it } }
        entries.clear(); entries.putAll(ordered)
        publish()
    }

    fun controller(token: MediaSession.Token): MediaController? = entries[token]?.controller

    enum class Cmd { PLAY, PAUSE, PREV, NEXT }

    private const val TAG = "OpMedia"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private data class Snap(val state: Int?, val title: String?, val updated: Long?)

    private fun snap(c: MediaController) = Snap(
        c.playbackState?.state,
        c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
        c.playbackState?.lastPositionUpdateTime,
    )

    private fun worked(cmd: Cmd?, before: Snap, after: Snap): Boolean = when (cmd) {
        Cmd.PLAY -> after.state == PlaybackState.STATE_PLAYING || after.state == PlaybackState.STATE_BUFFERING
        Cmd.PAUSE -> after.state != PlaybackState.STATE_PLAYING && after.state != PlaybackState.STATE_BUFFERING
        else -> after != before
    }

    /**
     * Play, pause, previous, next. Media3 apps (Litres, for one) only give full commands to
     * SystemUI; other controllers have play/pause "omitted". So each command is tried as a
     * transport control, then as a media button (like a headset), then by pressing the matching
     * button of the app's own media notification, stopping as soon as the player reacts.
     */
    fun command(token: MediaSession.Token, cmd: Cmd) {
        val e = entries[token] ?: return
        val c = e.controller
        val before = snap(c)
        val tc = c.transportControls
        val code = when (cmd) {
            Cmd.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            Cmd.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            Cmd.PREV -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            Cmd.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
        }
        fun mediaButton() {
            c.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            c.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        when (cmd) {
            // Play and pause are idempotent, so send both ways at once and the button reacts
            // immediately whichever one the app accepts.
            Cmd.PLAY -> { tc.play(); mediaButton() }
            Cmd.PAUSE -> { tc.pause(); mediaButton() }
            Cmd.PREV -> tc.skipToPrevious()
            Cmd.NEXT -> tc.skipToNext()
        }
        val idempotent = cmd == Cmd.PLAY || cmd == Cmd.PAUSE
        scope.launch {
            delay(700)
            if (worked(cmd, before, snap(c))) { Log.d(TAG, "${c.packageName} $cmd: ok"); return@launch }
            if (!idempotent) {
                // Skips aren't idempotent: only try the media button once the transport control is known to have failed.
                mediaButton()
                delay(700)
            }
            if (worked(cmd, before, snap(c))) { Log.d(TAG, "${c.packageName} $cmd: media button"); return@launch }
            val a = notificationAction(e, cmd)
            Log.d(TAG, "${c.packageName} $cmd: notification button ${a?.title}")
            a?.let { send(it) }
        }
    }

    /** App-defined actions (e.g. -15 / +30 s), with the same fallback to the notification's button. */
    fun custom(token: MediaSession.Token, a: MediaAction) {
        val e = entries[token] ?: return
        val c = e.controller
        val before = snap(c)
        c.transportControls.sendCustomAction(a.action, a.extras)
        scope.launch {
            delay(700)
            if (worked(null, before, snap(c))) { Log.d(TAG, "${c.packageName} ${a.name}: custom action"); return@launch }
            Log.d(TAG, "${c.packageName} ${a.name}: notification button")
            e.actions.firstOrNull { it.title?.toString() == a.name }?.let { send(it) }
        }
    }

    private fun send(a: Notification.Action) {
        try { a.actionIntent?.send() } catch (_: Exception) {}
    }

    /** Finds the notification button for [cmd] by its icon's resource name, then by its title. */
    private fun notificationAction(e: Entry, cmd: Cmd): Notification.Action? {
        val ctx = appContext ?: return null
        fun name(a: Notification.Action): String {
            val icon = a.getIcon()
            val res = if (icon != null && icon.type == Icon.TYPE_RESOURCE) try {
                ctx.packageManager.getResourcesForApplication(icon.resPackage).getResourceEntryName(icon.resId)
            } catch (_: Exception) { "" } else ""
            return (res + " " + a.title).lowercase()
        }
        val named = e.actions.map { it to name(it) }
        return when (cmd) {
            Cmd.PLAY -> named.firstOrNull { "play" in it.second && "pause" !in it.second }?.first
                ?: named.firstOrNull { "play" in it.second }?.first
            Cmd.PAUSE -> named.firstOrNull { "pause" in it.second }?.first
            Cmd.PREV -> named.firstOrNull { "prev" in it.second }?.first
            Cmd.NEXT -> named.firstOrNull { "next" in it.second }?.first
        }
    }

    private fun publish() {
        val ctx = appContext ?: return
        _items.value = entries.values.mapNotNull { build(ctx, it) }
    }

    private fun build(ctx: Context, e: Entry): MediaItem? {
        val c = e.controller
        val md = c.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val st = c.playbackState
        val pkg = c.packageName
        val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val artKey = "$pkg|$title|${bmp?.generationId}|${bmp?.width}"
        val (art, accent) = artCache.getOrPut(artKey) { bmp?.let { prepareArt(it) } ?: (null to null) }
        if (artCache.size > 20) artCache.keys.take(artCache.size - 20).forEach { artCache.remove(it) }

        val res = try { ctx.packageManager.getResourcesForApplication(pkg) } catch (_: Exception) { null }
        val custom = st?.customActions.orEmpty().take(3).map { a ->
            val icon = iconCache.getOrPut("$pkg#${a.icon}") {
                try { res?.getDrawable(a.icon, null)?.toBitmap(72, 72)?.asImageBitmap() } catch (_: Exception) { null }
            }
            MediaAction(a.action, a.name.toString(), icon, a.extras)
        }
        val appIcon = iconCache.getOrPut("app:$pkg") {
            try { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() } catch (_: Exception) { null }
        }
        return MediaItem(
            key = e.key,
            token = c.sessionToken,
            pkg = pkg,
            appIcon = appIcon,
            title = title,
            artist = artist,
            art = art,
            accent = accent,
            duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
            state = st?.state ?: PlaybackState.STATE_NONE,
            position = st?.position ?: 0,
            positionTime = st?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            speed = st?.playbackSpeed ?: 1f,
            actions = st?.actions ?: 0,
            custom = custom,
            openIntent = c.sessionActivity ?: e.intent,
        )
    }

    /** Downscales the artwork and picks its most vivid colour (Pixel tints the player from the art). */
    private fun prepareArt(src: Bitmap): Pair<ImageBitmap, Color?> {
        val max = 512
        val scaled = if (src.width > max || src.height > max) {
            val f = max.toFloat() / maxOf(src.width, src.height)
            src.scale((src.width * f).toInt().coerceAtLeast(1), (src.height * f).toInt().coerceAtLeast(1))
        } else src
        val tiny = src.scale(24, 24)
        var best: Int? = null
        var bestScore = 0f
        val hsv = FloatArray(3)
        for (y in 0 until 24) for (x in 0 until 24) {
            val p = tiny.getPixel(x, y)
            android.graphics.Color.colorToHSV(p, hsv)
            val score = hsv[1] * hsv[2]
            if (score > bestScore) { bestScore = score; best = p }
        }
        return scaled.asImageBitmap() to best?.takeIf { bestScore > 0.15f }?.let { Color(it) }
    }
}
