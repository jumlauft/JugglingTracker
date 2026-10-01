package com.juggling.tracker.wear.platform

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.juggling.tracker.wear.logic.WatchEffects

/** Vibration, and quitting through [onExit]. */
class AndroidWatchEffects(
    private val context: Context,
    private val onExit: () -> Unit,
) : WatchEffects {
    companion object {
        // The Garmin buzz: VibeProfile(50, 200), half strength for 200 ms.
        private const val VIBRATE_MS = 200L
        private const val VIBRATE_AMPLITUDE = 128
    }

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }

    override fun vibrate() {
        vibrator?.vibrate(VibrationEffect.createOneShot(VIBRATE_MS, VIBRATE_AMPLITUDE))
    }

    override fun exit() = onExit()
}
