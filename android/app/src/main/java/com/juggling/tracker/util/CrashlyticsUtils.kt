package com.juggling.tracker.util

import com.google.firebase.crashlytics.FirebaseCrashlytics

object CrashlyticsUtils {
    fun recordException(e: Throwable) {
        try {
            FirebaseCrashlytics.getInstance().recordException(e)
        } catch (ignored: IllegalStateException) {
            // Firebase not initialized, likely in a unit test
        }
    }
}
