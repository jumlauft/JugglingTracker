package com.juggling.tracker.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsManagerTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `the watch type defaults to Garmin and survives a restart`() {
        val settings = SettingsManager(context)
        assertEquals(WatchType.GARMIN, settings.watchType)

        settings.updateWatchType(WatchType.WEAR_OS)

        assertEquals(WatchType.WEAR_OS, settings.watchType)
        assertEquals(WatchType.WEAR_OS, SettingsManager(context).watchType)
    }
}
