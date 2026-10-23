package com.juggling.tracker.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The history graph used to floor the touch position, so a point's touch area
 * ran from that point to the next one and the newest point on the right edge
 * could hardly be hit. Each point's area is now centred on it.
 */
class NearestPointIndexTest {

    // Five points at x = 0, 100, 200, 300, 400.
    private val width = 400f
    private val count = 5

    @Test
    fun touchJustLeftOfNewestPointSelectsIt() {
        assertEquals(4, nearestPointIndex(360f, width, count))
    }

    @Test
    fun touchJustLeftOfAPointSelectsThatPoint() {
        assertEquals(2, nearestPointIndex(160f, width, count))
        assertEquals(1, nearestPointIndex(140f, width, count))
    }

    @Test
    fun touchOutsideTheGraphIsClamped() {
        assertEquals(0, nearestPointIndex(-20f, width, count))
        assertEquals(4, nearestPointIndex(450f, width, count))
    }

    @Test
    fun singlePointIsAlwaysSelected() {
        assertEquals(0, nearestPointIndex(10f, width, 1))
        assertEquals(0, nearestPointIndex(390f, width, 1))
    }
}
