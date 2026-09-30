package com.juggling.tracker.logic

import org.junit.Assert.assertEquals
import org.junit.Test

/** How the Data Layer's answers become the Wear OS card's state. */
class WearConnectionStatusTest {
    @Test
    fun `a reachable watch app is ready`() {
        assertEquals(WearConnectionStatus.READY, WearConnectionStatus.classify(connectedWatches = 1, watchesWithApp = 1))
    }

    @Test
    fun `no connected watch is reported as such`() {
        assertEquals(WearConnectionStatus.NO_WATCH, WearConnectionStatus.classify(connectedWatches = 0, watchesWithApp = 0))
    }

    @Test
    fun `a connected watch without the app asks for the app`() {
        assertEquals(
            WearConnectionStatus.WATCH_APP_MISSING,
            WearConnectionStatus.classify(connectedWatches = 1, watchesWithApp = 0),
        )
    }

    @Test
    fun `a Data Layer that cannot be asked is unavailable`() {
        assertEquals(WearConnectionStatus.UNAVAILABLE, WearConnectionStatus.classify(connectedWatches = null, watchesWithApp = 0))
    }
}
