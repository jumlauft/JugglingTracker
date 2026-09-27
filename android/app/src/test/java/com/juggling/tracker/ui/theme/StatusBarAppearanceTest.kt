package com.juggling.tracker.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Closes the gap left open when the status bar icon colour was fixed: the
 * `SideEffect` that applies `isAppearanceLightStatusBars` had no test, because
 * there was no Compose harness to run it in. StatusBarIconContrastTest covers
 * the decision function; this covers the theme applying it to a real window, on
 * both sides of the Android 15 change.
 *
 * Up to Android 14 the bar is filled with `colorScheme.primary`, which Material
 * inverts in lightness between the schemes. From Android 15 the setter is a
 * no-op and the bar sits over `colorScheme.background`, which does not invert.
 * So the correct flag is *opposite* between SDK 34 and 35 for the same theme,
 * which is exactly what the template's `= darkTheme` got wrong.
 *
 * `dynamicColor = false` pins the palette: with it on, the wallpaper would pick
 * the colours and the expected values would depend on the host.
 */
@RunWith(RobolectricTestRunner::class)
class StatusBarAppearanceTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    /** Renders the theme, then reads the flag back off the activity's window. */
    private fun appliedFlagFor(darkTheme: Boolean): Boolean {
        composeTestRule.setContent {
            JugglingTrackerTheme(darkTheme = darkTheme, dynamicColor = false) {
                Text("content behind the status bar")
            }
        }
        composeTestRule.waitForIdle()
        val window = composeTestRule.activity.window
        return WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars
    }

    // ── Up to Android 14: the bar is colorScheme.primary ─────────────────

    @Test
    @Config(sdk = [34])
    fun `on Android 14 the light theme's dark primary takes light icons`() {
        assertEquals(false, appliedFlagFor(darkTheme = false))
    }

    @Test
    @Config(sdk = [34])
    fun `on Android 14 the dark theme's light primary takes dark icons`() {
        assertEquals(true, appliedFlagFor(darkTheme = true))
    }

    // ── Android 15+: the bar sits over colorScheme.background ────────────

    @Test
    @Config(sdk = [35])
    fun `on Android 15 the light theme's pale background takes dark icons`() {
        // The case that was broken: the old `= darkTheme` produced false here,
        // drawing light icons on a near-white background.
        assertEquals(true, appliedFlagFor(darkTheme = false))
    }

    @Test
    @Config(sdk = [35])
    fun `on Android 15 the dark theme's dark background takes light icons`() {
        assertEquals(false, appliedFlagFor(darkTheme = true))
    }
}
