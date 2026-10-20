package digital.tripolskiy.opshade

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Brightness as the system slider sees it: the slider position is "gamma" space and the
 * stored setting is linear, converted with the same HLG curve AOSP's BrightnessUtils uses.
 */
object Brightness {
    private const val R = 0.5f
    private const val A = 0.17883277f
    private const val B = 0.28466892f
    private const val C = 0.55991073f
    private const val MIN = 1
    private const val MAX = 255

    private fun linearToGamma(v: Float): Float {
        val n = v * 12f
        return if (n <= 1f) sqrt(n) * R else A * ln(n - B) + C
    }

    private fun gammaToLinear(g: Float): Float {
        val n = if (g <= R) (g / R) * (g / R) else exp((g - C) / A) + B
        return (n / 12f).coerceIn(0f, 1f)
    }

    fun canWrite(ctx: Context) = Settings.System.canWrite(ctx)

    /** Slider position 0..1. */
    fun read(ctx: Context): Float {
        val raw = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        return linearToGamma((raw - MIN).toFloat() / (MAX - MIN)).coerceIn(0f, 1f)
    }

    fun write(ctx: Context, position: Float) {
        if (!canWrite(ctx)) return
        val raw = (MIN + gammaToLinear(position.coerceIn(0f, 1f)) * (MAX - MIN)).toInt().coerceIn(MIN, MAX)
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, raw)
    }

    fun isAuto(ctx: Context) = Settings.System.getInt(
        ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0
    ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC

    fun setAuto(ctx: Context, auto: Boolean) {
        if (!canWrite(ctx)) return
        Settings.System.putInt(
            ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            if (auto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
    }
}

/** The control center's volume slider drives media volume, like OxygenOS. */
object MediaVolume {
    private fun am(ctx: Context) = ctx.getSystemService(AudioManager::class.java)

    fun read(ctx: Context): Float {
        val am = am(ctx)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    fun steps(ctx: Context) = am(ctx).getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    fun write(ctx: Context, v: Float) {
        val am = am(ctx)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val index = kotlin.math.round(v.coerceIn(0f, 1f) * max).toInt()
        // setStreamVolume leaves a muted stream muted, so raising the slider has to unmute first.
        if (index > 0 && am.isStreamMute(AudioManager.STREAM_MUSIC)) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        }
        am.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
    }

    fun isMuted(ctx: Context) = am(ctx).isStreamMute(AudioManager.STREAM_MUSIC)

    fun toggleMute(ctx: Context) {
        am(ctx).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
    }
}
