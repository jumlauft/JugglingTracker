package com.juggling.tracker

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.juggling.tracker.data.RecordingRepository
import com.juggling.tracker.data.SessionRepository
import com.juggling.tracker.data.SettingsManager
import com.juggling.tracker.logic.WatchInbox

class JugglingTrackerApplication : Application() {
    // One of each per process, shared by the screens, the Wear OS listener
    // service and the Garmin link. The repositories cache what they store, so
    // two instances would each miss what the other wrote.
    val sessionRepository: SessionRepository by lazy { SessionRepository(this) }
    val recordingRepository: RecordingRepository by lazy { RecordingRepository(this) }
    val settingsManager: SettingsManager by lazy { SettingsManager(this) }

    // The user's analytics choice is applied on first use, whichever part of
    // the app gets here first: the screen, or the Wear OS listener service
    // syncing with no screen open.
    val analytics: FirebaseAnalytics by lazy {
        val isAnalyticsEnabled = settingsManager.isAnalyticsEnabled
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(isAnalyticsEnabled)
        FirebaseAnalytics.getInstance(this).apply { setAnalyticsCollectionEnabled(isAnalyticsEnabled) }
    }

    val watchInbox: WatchInbox by lazy { WatchInbox(sessionRepository, recordingRepository, analytics) { settingsManager.currentJuggler } }
    val garminLink: GarminLink by lazy { GarminLink(this, watchInbox) }

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
    }
}
