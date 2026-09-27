package com.juggling.tracker.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlin.math.max
import kotlin.math.min

internal val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

internal val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

/**
 * Status colours the Garmin header needs and Material 3 does not define.
 *
 * Its failure states use `colorScheme.errorContainer`, which already adapts;
 * "ready" and "receiving" have no Material token, so they come from here rather
 * than from literals at the call site, which is how they ended up pinned to
 * light-mode values while the rest of the app followed the theme.
 */
@Immutable
data class StatusColors(
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

internal val LightStatusColors = StatusColors(
    successContainer = SuccessContainerLight,
    onSuccessContainer = OnSuccessContainerLight,
    warningContainer = WarningContainerLight,
    onWarningContainer = OnWarningContainerLight,
)

internal val DarkStatusColors = StatusColors(
    successContainer = SuccessContainerDark,
    onSuccessContainer = OnSuccessContainerDark,
    warningContainer = WarningContainerDark,
    onWarningContainer = OnWarningContainerDark,
)

/**
 * Picked from the theme's own `darkTheme`, not from `isSystemInDarkTheme()` at
 * the call site, so an explicit override -- a preview, a test -- gets the
 * matching palette instead of the system's.
 */
internal fun statusColorsFor(darkTheme: Boolean): StatusColors =
    if (darkTheme) DarkStatusColors else LightStatusColors

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

/** WCAG 2.x contrast ratio between two opaque colours, from 1.0 to 21.0. */
internal fun contrastRatio(a: Color, b: Color): Float {
    val lighter = max(a.luminance(), b.luminance())
    val darker = min(a.luminance(), b.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}

/**
 * Whether `isAppearanceLightStatusBars` should be set for a status bar sitting
 * on [behindStatusBar].
 *
 * The flag names the *bar*, not the icons: true means "the bar is light, so
 * draw dark icons on it". Deriving it from the colour actually behind the bar
 * keeps it right for the fixed palettes and for the dynamic ones, where the
 * wallpaper picks the tones.
 *
 * The Android Studio template shipped `= darkTheme`, which happens to suit
 * `primary` -- Material inverts primary's lightness between the light and dark
 * schemes -- but is backwards for a bar over `background`, which tracks the
 * theme instead of inverting with it.
 *
 * @return true when the bar is light enough that dark icons read better on it.
 */
internal fun needsDarkStatusBarIcons(behindStatusBar: Color): Boolean {
    // Compare the contrast the bar would give each of the two icon colours the
    // system offers and take the better one. The break-even point is near
    // luminance 0.18, not the 0.5 a plain lightness test assumes, so a mid-tone
    // bar still gets the readable icons -- worth having because dynamic colour
    // lets the wallpaper choose `primary`.
    return contrastRatio(behindStatusBar, Color.Black) >
        contrastRatio(behindStatusBar, Color.White)
}

@Composable
fun JugglingTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window

            // Up to Android 14 the system fills the status bar with
            // statusBarColor. From Android 15 on, targeting SDK 35+ forces
            // edge-to-edge and the setter is ignored, so MainActivity's root
            // Surface -- colorScheme.background -- shows through it instead.
            val systemPaintsStatusBar =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM
            if (systemPaintsStatusBar) {
                @Suppress("DEPRECATION")
                window.statusBarColor = colorScheme.primary.toArgb()
            }
            val behindStatusBar =
                if (systemPaintsStatusBar) colorScheme.primary else colorScheme.background

            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                needsDarkStatusBarIcons(behindStatusBar)
        }
    }

    CompositionLocalProvider(LocalStatusColors provides statusColorsFor(darkTheme)) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
