package digital.tripolskiy.opshade

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * Every size in the UI is written in "OnePlus dp": measurements taken from the OnePlus 12's
 * 360dp-wide screen. [LocalUnit] scales them to the current width, so proportions match on any phone.
 */
val LocalUnit = compositionLocalOf { 1f }

val Number.u: Dp
    @Composable @ReadOnlyComposable get() = (toFloat() * LocalUnit.current).dp

/** Text sized like [u], ignoring the system font scale so labels fit their tiles exactly as on OxygenOS. */
val Number.us: TextUnit
    @Composable @ReadOnlyComposable get() = with(LocalDensity.current) { (toFloat() * LocalUnit.current).dp.toSp() }

/**
 * Colours follow the Pixel's Material You theme (wallpaper colours, light/dark), mapped to the
 * roles Pixel's own shade uses: active tiles and slider fill = primary with on-primary content,
 * inactive tiles = surface container high, notification cards = surface container, and the
 * backdrop = surface dim over the blur.
 */
@Immutable
data class ShadePalette(
    val dark: Boolean,
    val active: Color,
    val onActive: Color,
    val inactive: Color,
    val onInactive: Color,
    val card: Color,
    val chip: Color,
    val sliderTrack: Color,
    val text: Color,
    val textSecondary: Color,
    val handle: Color,
    val clearAll: Color,
    val scrimTop: Color,
    val scrimBottom: Color,
    /** Delete affordances (swipe-to-dismiss past the threshold). */
    val danger: Color,
    val onDanger: Color,
) {
    companion object {
        fun from(ctx: Context): ShadePalette {
            val dark = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val s = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            return ShadePalette(
                dark = dark,
                active = s.primary,
                onActive = s.onPrimary,
                inactive = s.surfaceContainerHigh,
                onInactive = s.onSurface,
                card = s.surfaceContainer,
                chip = s.surfaceBright,
                sliderTrack = s.surfaceContainerHigh,
                text = s.onSurface,
                textSecondary = s.onSurfaceVariant.copy(alpha = 0.78f),
                handle = s.onSurfaceVariant.copy(alpha = 0.4f),
                clearAll = s.surfaceContainerHigh,
                scrimTop = s.surfaceDim,
                scrimBottom = s.surfaceContainerLow,
                danger = s.error,
                onDanger = s.onError,
            )
        }
    }
}

val LocalPalette = staticCompositionLocalOf<ShadePalette> { error("No palette") }

/** The current shade colours. */
val P: ShadePalette
    @Composable @ReadOnlyComposable get() = LocalPalette.current

object Op {
    /** OxygenOS tile colour transition: 200 ms, PathInterpolator(0.33, 0, 0.67, 1). */
    val ColorEasing = CubicBezierEasing(0.33f, 0f, 0.67f, 1f)

    // Pixel ships Google Sans; it is the closest system font to OnePlus Sans.
    val Display: FontFamily = FontFamily(Typeface.create("google-sans", Typeface.NORMAL))
    val Body: FontFamily = FontFamily(Typeface.create("google-sans-text", Typeface.NORMAL))
}
