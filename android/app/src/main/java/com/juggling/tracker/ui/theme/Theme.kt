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
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

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
    // Compare the WCAG contrast ratio the bar would give each of the two icon
    // colours the system offers and take the better one. The break-even point
    // is near luminance 0.18, not the 0.5 a plain lightness test assumes, so a
    // mid-tone bar still gets the readable icons -- worth having because
    // dynamic colour lets the wallpaper choose `primary`.
    val luminance = behindStatusBar.luminance()
    val contrastWithDarkIcons = (luminance + 0.05f) / 0.05f
    val contrastWithLightIcons = 1.05f / (luminance + 0.05f)
    return contrastWithDarkIcons > contrastWithLightIcons
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

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
