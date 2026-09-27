package com.juggling.tracker.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The status bar icons have to contrast with whatever is behind them, which is
 * `statusBarColor` up to Android 14 and the app's own background from Android 15
 * on. `JugglingTrackerTheme` used the template's `isAppearanceLightStatusBars =
 * darkTheme`, which is right for the first case only by coincidence and wrong
 * for the second -- white icons on a white background in the light theme.
 */
class StatusBarIconContrastTest {

    // ── Up to Android 14: the bar is filled with colorScheme.primary ─────

    @Test
    fun `dark light-theme primary takes light icons`() {
        assertFalse(needsDarkStatusBarIcons(LightColorScheme.primary))
    }

    @Test
    fun `light dark-theme primary takes dark icons`() {
        assertTrue(needsDarkStatusBarIcons(DarkColorScheme.primary))
    }

    // ── Android 15+: the bar sits over colorScheme.background ────────────

    @Test
    fun `light theme background takes dark icons`() {
        // What the old `= darkTheme` got backwards: it yielded false here, so
        // the icons were drawn light on a near-white background.
        assertTrue(needsDarkStatusBarIcons(LightColorScheme.background))
    }

    @Test
    fun `dark theme background takes light icons`() {
        assertFalse(needsDarkStatusBarIcons(DarkColorScheme.background))
    }

    // ── Mid-tone bars, which dynamic colour can produce ─────────────────

    @Test
    fun `a mid-tone bar takes dark icons`() {
        // Luminance 0.25: below the 0.5 a plain lightness test would use, but
        // well above the ~0.18 where the two icon colours read equally well.
        // Dark icons give 6.0:1 here against 3.5:1 for light ones.
        val midTone = Color(0xFF8C8C8C)
        assertTrue(midTone.luminance() < 0.5f)
        assertTrue(needsDarkStatusBarIcons(midTone))
    }

    @Test
    fun `a bar just below the break-even point takes light icons`() {
        // Luminance ~0.13, under the ~0.18 crossover.
        val deepTone = Color(0xFF6E6E6E)
        assertFalse(needsDarkStatusBarIcons(deepTone))
    }

    // ── The property that makes the rule correct rather than tuned ───────

    @Test
    fun `the choice follows the colour behind the bar, not the theme`() {
        // Both schemes are covered above; stated as one invariant, a light
        // surface always takes dark icons and vice versa, whichever theme and
        // whichever Android version put that colour behind the bar.
        listOf(
            LightColorScheme.primary to LightColorScheme.background,
            DarkColorScheme.primary to DarkColorScheme.background,
        ).forEach { (primary, background) ->
            assertTrue(
                "primary and background differ in lightness within a scheme",
                needsDarkStatusBarIcons(primary) != needsDarkStatusBarIcons(background),
            )
        }
    }
}
