package com.juggling.tracker.wear.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun `SHAPE-4 the screen shows a percentage or a dash`() {
        assertEquals("-", Format.percentOrDash(-1))
        assertEquals("83%", Format.percentOrDash(83))
    }
}
