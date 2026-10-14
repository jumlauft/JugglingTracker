package com.juggling.tracker.data

import android.os.Looper
import com.juggling.tracker.logic.HistoryModel
import com.juggling.tracker.logic.SettingsModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
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

    // SettingsManager only stores; the Settings screen observes SettingsModel,
    // which must also see what the backup worker stores with no screen open.
    @Test
    fun `the settings model follows changes stored elsewhere`() {
        val settings = SettingsManager(context)
        val model = SettingsModel(settings, HistoryModel(null))

        settings.recordBackupResult(succeeded = true, atMillis = 1_234L)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1_234L, model.lastBackupMillis)

        model.toggleVoice(false)
        assertFalse(model.isVoiceEnabled)
        assertFalse(SettingsManager(context).read().isVoiceEnabled)
        model.close()
    }
}
