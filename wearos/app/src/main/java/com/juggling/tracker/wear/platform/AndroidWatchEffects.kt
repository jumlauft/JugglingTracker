package com.juggling.tracker.wear.platform

import android.app.Activity
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.juggling.tracker.wear.logic.WatchEffects

/** Vibration and quitting for a running activity. */
class AndroidWatchEffects(private val activity: Activity) : WatchEffects {
    companion object {
        // The Garmin buzz: VibeProfile(50, 200), half strength for 200 ms.
        private const val VIBRATE_MS = 200L
        private const val VIBRATE_AMPLITUDE = 128
    }

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            activity.getSystemService(Vibrator::class.java)
        }
    }

    override fun vibrate() {
        vibrator?.vibrate(VibrationEffect.createOneShot(VIBRATE_MS, VIBRATE_AMPLITUDE))
    }

    override fun exit() {
        activity.finish()
    }
}
