package digital.tripolskiy.opshade

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Expansion of the shade: 0 = closed, 1 = notification shade with quick tiles,
 * 2 = full control center. This mirrors OxygenOS's combined (non-separate) mode,
 * where a first pull shows notifications and a second pull expands the tiles.
 *
 * [scope] must run on AndroidUiDispatcher.Main so animations have a frame clock.
 */
class ShadeController(
    private val scope: CoroutineScope,
    /** Finger travel, in px, that takes the shade from closed to the notification stage. */
    private val openDistance: Float,
    /** Finger travel, in px, from the notification stage to the full control center. */
    private val expandDistance: Float,
    /** Velocity, in px/s, above which a release counts as a fling. */
    private val flingVelocity: Float,
    private val onShownChange: (Boolean) -> Unit,
) {
    val expansion = Animatable(0f)
    private var job: Job? = null
    private var shown = false
    // Drag positions are applied by coroutines that may be cancelled before they run,
    // so the logical position during a drag is kept here, synchronously.
    private var dragValue = -1f

    val isOpen: Boolean get() = shown

    private fun show(v: Boolean) {
        if (v != shown) {
            shown = v
            onShownChange(v)
        }
    }

    /** Drag by [dy] px; positive pulls the shade further open. [maxStage] caps how far a drag may go. */
    fun dragBy(dy: Float, maxStage: Float = 2f) {
        job?.cancel()
        val cur = if (dragValue >= 0f) dragValue else expansion.value
        val dist = if (cur < 1f || (cur == 1f && dy < 0)) openDistance else expandDistance
        var next = cur + dy / dist
        // Crossing stage 1 switches to the other distance; split the step so the rate stays right.
        if (cur < 1f && next > 1f) next = 1f + (next - 1f) * openDistance / expandDistance
        if (cur > 1f && next < 1f) next = 1f - (1f - next) * expandDistance / openDistance
        next = next.coerceIn(0f, maxStage)
        dragValue = next
        if (next > 0f) show(true)
        job = scope.launch { expansion.snapTo(next) }
    }

    /** Release after a drag. [velocity] is px/s, positive meaning "more open". */
    fun settle(velocity: Float, maxStage: Float = 2f) {
        val cur = if (dragValue >= 0f) dragValue else expansion.value
        val stageVel = velocity / (if (cur < 1f) openDistance else expandDistance)
        val target = when {
            velocity > flingVelocity -> ceil(cur + 0.001f)
            velocity < -flingVelocity -> floor(cur - 0.001f)
            else -> cur.roundToInt().toFloat()
        }.coerceIn(0f, maxStage)
        animateTo(target, stageVel, from = cur)
    }

    fun open(stage: Float = 1f) = animateTo(stage, 0f)

    fun close() = animateTo(0f, 0f)

    fun closeNow() {
        job?.cancel()
        dragValue = -1f
        job = scope.launch {
            expansion.snapTo(0f)
            show(false)
        }
    }

    private fun animateTo(target: Float, velocity: Float, from: Float = -1f) {
        job?.cancel()
        dragValue = -1f
        if (target > 0f) show(true)
        // OxygenOS drives the panel with critically damped springs; closing is a little softer.
        val stiffness = if (target >= expansion.value) 420f else 320f
        job = scope.launch {
            if (from >= 0f) expansion.snapTo(from)
            expansion.animateTo(
                target,
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = stiffness, visibilityThreshold = 0.001f),
                initialVelocity = velocity,
            )
            if (target == 0f) show(false)
        }
    }

    fun isSettled(): Boolean = !expansion.isRunning && abs(expansion.value - expansion.value.roundToInt()) < 0.001f
}
