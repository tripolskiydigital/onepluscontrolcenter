package digital.tripolskiy.opshade

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Hosts the replacement shade. A thin window over the status bar catches the pull-down
 * (accessibility overlays sit above the system status bar, so the system shade never sees it),
 * and a full-screen window draws the OxygenOS-style panel.
 *
 * On the lock screen the catcher is removed, so the system shade keeps working there and
 * notification contents stay protected.
 */
class ShadeService : AccessibilityService(), TileHost {

    companion object {
        @Volatile var running: ShadeService? = null
            private set
    }

    private lateinit var wm: WindowManager
    private val scope = CoroutineScope(SupervisorJob() + AndroidUiDispatcher.Main)
    private val owner = OverlayOwner()

    private lateinit var controller: ShadeController
    lateinit var tiles: TileRepository
        private set

    private lateinit var catcher: View
    private lateinit var catcherParams: WindowManager.LayoutParams
    private lateinit var shadeRoot: FrameLayout
    private lateinit var shadeParams: WindowManager.LayoutParams
    private var blurEnabled = false
    private var refreshJob: Job? = null

    override val context: Context get() = this

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_OFF) controller.closeNow()
            updateCatcher()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = this
        wm = getSystemService(WindowManager::class.java)
        owner.start()
        tiles = TileRepository(this)
        blurEnabled = wm.isCrossWindowBlurEnabled

        val dm = resources.displayMetrics
        // Layouts are proportional to the OnePlus 12's 360dp width, so distances scale the same way.
        val unit = dm.density * (dm.widthPixels / dm.density / 360f)
        controller = ShadeController(
            scope,
            openDistance = 150f * unit,
            expandDistance = 220f * unit,
            flingVelocity = 250f * dm.density,
            onShownChange = ::setShadeShown,
        )

        addShadeWindow()
        addCatcherWindow()

        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        updateCatcher()

        scope.launch {
            snapshotFlow { controller.expansion.value }.collect { applyBlur(it) }
        }
    }

    private var takeoverJob: Job? = null

    private fun takeOverFromSystemShade(stage: Float) {
        tiles.refresh()
        controller.open(stage)
        takeoverJob?.cancel()
        takeoverJob = scope.launch {
            // The finger is still dragging the system shade, so it can reopen; keep closing it
            // until the gesture has surely ended.
            for (d in longArrayOf(0, 120, 250, 400, 650, 1000, 1500)) {
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                delay(if (d == 0L) 120 else d / 2)
            }
        }
    }

    // While SystemUI opens a dialog we asked for (the output switcher), don't treat it as the system shade.
    private var allowSystemUiUntil = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == "com.android.systemui" && controller.isOpen && android.os.SystemClock.uptimeMillis() > allowSystemUiUntil &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || pkg == packageName || pkg == "com.android.systemui") return
        if (!controller.isOpen) {
            // Remember what is on screen under the shade.
            frontPkg = pkg
            return
        }
        // Another app took over (home gesture, an app opened from a tile): get out of the way. But
        // the app that was already under the shade also reports a window change when it gets focus
        // back, e.g. after the system shade we dismissed closes; that must not close ours.
        val sinceOpen = android.os.SystemClock.uptimeMillis() - openedAt
        if (pkg == frontPkg) return
        if (sinceOpen < REFOCUS_GRACE_MS) {
            // While we are still dismissing the system shade (takeOverFromSystemShade), a window change
            // is the app under the shade getting focus back; adopt it as the front app.
            android.util.Log.d("OpShade", "ignored window change from $pkg (${sinceOpen}ms after open)")
            frontPkg = pkg
            return
        }
        android.util.Log.d("OpShade", "closing: $pkg came to front")
        frontPkg = pkg
        controller.close()
    }

    private var frontPkg: String? = null
    private var openedAt = 0L
    /** Covers the dismiss loop in takeOverFromSystemShade, which runs for about 1.5 s. */
    private val REFOCUS_GRACE_MS = 1600L

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = null
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        try { wm.removeView(catcher) } catch (_: Exception) {}
        try { wm.removeView(shadeRoot) } catch (_: Exception) {}
        owner.destroy()
        scope.cancel()
        super.onDestroy()
    }

    // region windows

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else (24 * resources.displayMetrics.density).roundToInt()
    }

    private fun baseParams(height: Int, flags: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addCatcherWindow() {
        catcher = View(this)
        catcherParams = baseParams(statusBarHeight(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            .apply { title = "OpShadeCatcher" }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downY = 0f
        var lastY = 0f
        var dragging = false
        var twoFinger = false
        var tracker: VelocityTracker? = null

        catcher.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY; lastY = e.rawY; dragging = false; twoFinger = false
                    tracker?.recycle(); tracker = VelocityTracker.obtain()
                    tracker?.addMovement(e)
                    tiles.refresh()
                }
                MotionEvent.ACTION_POINTER_DOWN -> twoFinger = true
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(e)
                    if (!dragging && e.rawY - downY > slop) {
                        dragging = true
                        lastY = e.rawY
                    }
                    if (dragging) {
                        // A one-finger pull stops at the notification stage, as on OxygenOS;
                        // two fingers go straight through to the control center.
                        controller.dragBy(e.rawY - lastY, maxStage = if (twoFinger) 2f else 1f)
                        lastY = e.rawY
                    }
                }
                MotionEvent.ACTION_UP -> {
                    tracker?.addMovement(e)
                    tracker?.computeCurrentVelocity(1000)
                    val vy = tracker?.yVelocity ?: 0f
                    tracker?.recycle(); tracker = null
                    if (dragging) controller.settle(vy, maxStage = if (twoFinger) 2f else 1f)
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    // SystemUI watches top-edge swipes with a spy window and pilfers the pointer once
                    // it recognises one, which cancels our stream. Take over: open our shade (it is
                    // layered above the system one) and dismiss the system shade underneath.
                    tracker?.recycle(); tracker = null
                    dragging = false
                    takeOverFromSystemShade(if (twoFinger) 2f else 1f)
                }
            }
            true
        }
        wm.addView(catcher, catcherParams)
    }

    private fun addShadeWindow() {
        shadeRoot = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) {
                        if (controller.expansion.value > 1.01f) controller.open(1f) else controller.close()
                    }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        owner.attach(shadeRoot)
        val compose = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                ShadePanel(
                    controller = controller,
                    tiles = tiles,
                    host = this@ShadeService,
                    blurEnabled = blurEnabled,
                )
            }
        }
        shadeRoot.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        shadeRoot.visibility = View.GONE
        shadeParams = baseParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        ).apply {
            title = "OpShade"
            if (blurEnabled) flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            blurBehindRadius = 0
        }
        wm.addView(shadeRoot, shadeParams)
    }

    private fun setShadeShown(shown: Boolean) {
        if (shown) openedAt = android.os.SystemClock.uptimeMillis()
        val f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        shadeParams.flags = if (shown) shadeParams.flags and f.inv() else shadeParams.flags or f
        if (!shown) shadeParams.blurBehindRadius = 0
        shadeRoot.visibility = if (shown) View.VISIBLE else View.GONE
        // Pixel's adaptive refresh rate maps the "high" category to 90 Hz on this panel,
        // so ask for the panel's full 120 Hz explicitly while the shade is on screen.
        shadeParams.preferredRefreshRate = if (shown) maxRefreshRate else 0f
        setFrameRateVote(shadeRoot, if (shown) maxRefreshRate else View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT)
        wm.updateViewLayout(shadeRoot, shadeParams)

        refreshJob?.cancel()
        if (shown) {
            refreshJob = scope.launch {
                while (isActive) {
                    tiles.refresh()
                    delay(1000)
                }
            }
        }
    }

    private val maxRefreshRate: Float by lazy {
        @Suppress("DEPRECATION")
        wm.defaultDisplay.supportedModes.maxOf { it.refreshRate }
    }

    private fun setFrameRateVote(v: View, rate: Float) {
        if (Build.VERSION.SDK_INT >= 35) v.requestedFrameRate = rate
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) setFrameRateVote(v.getChildAt(i), rate)
    }

    private fun applyBlur(expansion: Float) {
        if (!blurEnabled || shadeRoot.visibility != View.VISIBLE) return
        // OxygenOS ramps the blur in over the first third of the pull (blur fraction = f / 0.33).
        val max = (120 * resources.displayMetrics.density).roundToInt()
        val r = ((expansion / 0.33f).coerceIn(0f, 1f) * max).roundToInt()
        if (abs(r - shadeParams.blurBehindRadius) >= 6 || (r == 0) != (shadeParams.blurBehindRadius == 0)) {
            shadeParams.blurBehindRadius = r
            wm.updateViewLayout(shadeRoot, shadeParams)
        }
    }

    private fun updateCatcher() {
        if (!::catcher.isInitialized) return
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        catcher.visibility = if (locked) View.GONE else View.VISIBLE
        val f = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        catcherParams.flags = if (locked) catcherParams.flags or f else catcherParams.flags and f.inv()
        wm.updateViewLayout(catcher, catcherParams)
    }

    // endregion

    // region TileHost

    override fun launch(intent: Intent) {
        controller.close()
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    override fun collapse() = controller.close()

    override fun globalAction(action: Int) {
        performGlobalAction(action)
    }

    // endregion

    fun sendPendingIntent(pi: PendingIntent?, closeShade: Boolean = true) {
        if (closeShade) controller.close()
        pi ?: return
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= 34) {
            // Notification click intents are started from the background; lend them our
            // accessibility-service start privilege, as SystemUI does with its own.
            @Suppress("DEPRECATION")
            options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
        try {
            pi.send(this, 0, null, null, null, null, options.toBundle())
        } catch (_: Exception) {
        }
    }

    fun openShade() {
        tiles.refresh()
        controller.open(1f)
    }

    /** Inline reply for the notification action [index] of [key]. */
    fun openReply(key: String, index: Int) {
        launch(
            Intent(this, ReplyActivity::class.java)
                .putExtra(ReplyActivity.EXTRA_KEY, key)
                .putExtra(ReplyActivity.EXTRA_INDEX, index),
        )
    }

    /** Pixel's own output switcher (devices, cast, volume) for one player. */
    fun openOutputSwitcher(pkg: String, token: android.media.session.MediaSession.Token) {
        allowSystemUiUntil = android.os.SystemClock.uptimeMillis() + 2000
        controller.close()
        sendBroadcast(
            Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
                .setComponent(android.content.ComponentName("com.android.systemui", "com.android.systemui.media.dialog.MediaOutputDialogReceiver"))
                .putExtra("package_name", pkg)
                .putExtra("key_media_session_token", token),
        )
    }

    fun openSystemShade() {
        controller.closeNow()
        performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }
}
