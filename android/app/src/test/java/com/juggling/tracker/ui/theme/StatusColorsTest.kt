package com.juggling.tracker.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Garmin header's "ready" and "receiving" colours were literal light-mode
 * tints, so in dark mode the card stayed pale while the rest of the app went
 * dark. They now come from [StatusColors], one pair per theme, and these tests
 * hold both pairs to WCAG AA -- which is also how the light warning foreground
 * turned out to need replacing: the pale orange it used sat at 2.8:1.
 */
class StatusColorsTest {

    /** WCAG AA for body text. Bold 14sp would allow 3.0, but see the 80% case. */
    private val minimumContrast = 4.5f

    private fun assertReadable(label: String, container: Color, on: Color) {
        val ratio = contrastRatio(container, on)
        assertTrue(
            "$label contrast is $ratio, below $minimumContrast:1",
            ratio >= minimumContrast,
        )
    }

    /**
     * The RECEIVING branch draws its secondary line at 80% alpha over the
     * container, so that composite -- not just the flat pair -- has to clear AA.
     */
    private fun assertReadableAtEightyPercent(label: String, container: Color, on: Color) {
        val faded = on.copy(alpha = 0.8f).compositeOver(container)
        val ratio = contrastRatio(container, faded)
        assertTrue(
            "$label at 80% alpha contrast is $ratio, below $minimumContrast:1",
            ratio >= minimumContrast,
        )
    }

    // ── Both palettes are legible ────────────────────────────────────────

    @Test
    fun `light status colours meet AA`() {
        assertReadable(
            "light success",
            LightStatusColors.successContainer,
            LightStatusColors.onSuccessContainer,
        )
        assertReadable(
            "light warning",
            LightStatusColors.warningContainer,
            LightStatusColors.onWarningContainer,
        )
    }

    @Test
    fun `dark status colours meet AA`() {
        assertReadable(
            "dark success",
            DarkStatusColors.successContainer,
            DarkStatusColors.onSuccessContainer,
        )
        assertReadable(
            "dark warning",
            DarkStatusColors.warningContainer,
            DarkStatusColors.onWarningContainer,
        )
    }

    @Test
    fun `the receiving line stays legible at eighty percent alpha`() {
        assertReadableAtEightyPercent(
            "light warning",
            LightStatusColors.warningContainer,
            LightStatusColors.onWarningContainer,
        )
        assertReadableAtEightyPercent(
            "dark warning",
            DarkStatusColors.warningContainer,
            DarkStatusColors.onWarningContainer,
        )
    }

    // ── The palettes actually differ, and follow the theme ───────────────

    @Test
    fun `the dark palette is not the light one`() {
        // The defect was one palette used for both themes; assert they diverge
        // rather than trusting that two names mean two values.
        assertNotEquals(LightStatusColors, DarkStatusColors)
        assertNotEquals(
            LightStatusColors.successContainer,
            DarkStatusColors.successContainer,
        )
        assertNotEquals(
            LightStatusColors.warningContainer,
            DarkStatusColors.warningContainer,
        )
    }

    @Test
    fun `each palette's containers suit its own surface`() {
        // A container belongs to the theme whose surface it sits on: light
        // containers are lighter than the light surface's text, dark containers
        // darker. Pinning the direction catches a pair pasted into the wrong
        // palette, which is the mistake being fixed here.
        assertTrue(
            needsDarkStatusBarIcons(LightStatusColors.successContainer),
        )
        assertTrue(
            needsDarkStatusBarIcons(LightStatusColors.warningContainer),
        )
        assertTrue(
            !needsDarkStatusBarIcons(DarkStatusColors.successContainer),
        )
        assertTrue(
            !needsDarkStatusBarIcons(DarkStatusColors.warningContainer),
        )
    }

    @Test
    fun `statusColorsFor follows the theme it is given`() {
        assertEquals(DarkStatusColors, statusColorsFor(darkTheme = true))
        assertEquals(LightStatusColors, statusColorsFor(darkTheme = false))
    }

    // ── The error states lean on Material, which already adapts ──────────

    @Test
    fun `Material's error container differs between the schemes`() {
        // The header's failure states use colorScheme.errorContainer rather
        // than a literal, so they follow the theme without a pair here. That
        // only holds while Material keeps the two schemes distinct.
        assertNotEquals(
            lightColorScheme().errorContainer,
            darkColorScheme().errorContainer,
        )
        assertReadable(
            "Material light error",
            lightColorScheme().errorContainer,
            lightColorScheme().onErrorContainer,
        )
        assertReadable(
            "Material dark error",
            darkColorScheme().errorContainer,
            darkColorScheme().onErrorContainer,
        )
    }
}
