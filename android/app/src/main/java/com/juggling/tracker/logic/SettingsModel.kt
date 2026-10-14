package com.juggling.tracker.logic

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import com.juggling.tracker.backup.SessionBackup
import com.juggling.tracker.data.SessionRepository
import com.juggling.tracker.data.SettingsManager
import com.juggling.tracker.data.WatchType
import com.juggling.tracker.shared.Juggler
import com.juggling.tracker.shared.SessionSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything the Settings screen changes: the watch, voice, privacy, the
 * juggler and the backup. The screen talks only to this and to the
 * recordings (RawRecordingModel). Counterpart of the iPhone app's
 * SettingsModel.
 */
class SettingsModel(
    private val settings: SettingsManager? = null,
    private val history: HistoryModel,
    private val repository: SessionRepository? = null,
    private val analytics: FirebaseAnalytics? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val isAnalyticsEnabled get() = settings?.isAnalyticsEnabled ?: true
    val isVoiceEnabled get() = settings?.isVoiceEnabled ?: true
    val voiceInterval get() = settings?.voiceInterval ?: 10
    val watchType get() = settings?.watchType ?: WatchType.GARMIN

    fun setWatchType(type: WatchType) {
        settings?.updateWatchType(type)
    }

    val jugglerName get() = settings?.jugglerName ?: ""
    val watchHand get() = settings?.watchHand
    val firstThrowHand get() = settings?.firstThrowHand

    /**
     * Who is juggling now, once a name and both hands have been entered. New
     * recordings are saved with it, and an export tags older runs that have
     * none with it.
     */
    val currentJuggler: Juggler? get() = settings?.currentJuggler

    /** Remember who juggles, the watch wrist and the first-throw hand for the next recordings. */
    fun setExportDetails(name: String, hand: String, firstThrow: String) {
        settings?.updateExportDetails(name.trim(), hand, firstThrow)
    }

    fun toggleAnalytics(enabled: Boolean) {
        settings?.updateAnalyticsEnabled(enabled)
        analytics?.setAnalyticsCollectionEnabled(enabled)
        // JugglingTrackerApplication applies the saved choice at start-up;
        // this applies a change at once.
        try {
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(enabled)
        } catch (e: IllegalStateException) {
            // Firebase is not initialised (unit tests).
            Log.w(TAG, "Cannot change Crashlytics collection", e)
        }
    }

    fun toggleVoice(enabled: Boolean) {
        settings?.updateVoiceEnabled(enabled)
    }

    fun setVoiceInterval(interval: Int) {
        settings?.updateVoiceInterval(interval)
    }

    /** Whether a voice announcement is due at [count] catches. */
    fun shouldAnnounce(count: Int): Boolean =
        isVoiceEnabled && voiceInterval > 0 && count > 0 && count % voiceInterval == 0

    // ── Backup (see SessionBackup) ──────────────────────────────────────

    val isBackupEnabled get() = settings?.isBackupEnabled ?: false
    val backupUri get() = settings?.backupUri
    val backupFileName get() = settings?.backupFileName
    val lastBackupMillis get() = settings?.lastBackupMillis ?: 0L
    val lastBackupFailed get() = settings?.lastBackupFailed ?: false

    /** Use [uri] as the backup file; false when the app may not write there. */
    fun connectBackupFile(context: Context, uri: Uri): Boolean {
        val settings = settings ?: return false
        return SessionBackup.connect(context, uri, settings)
    }

    fun setBackupEnabled(context: Context, enabled: Boolean) {
        val settings = settings ?: return
        SessionBackup.setEnabled(context, settings, enabled)
    }

    /** Writes the backup file now, off the main thread. */
    suspend fun backUpNow(context: Context): SessionBackup.Result {
        val repository = repository ?: return SessionBackup.Result.FAILED
        val settings = settings ?: return SessionBackup.Result.FAILED
        return withContext(ioDispatcher) { SessionBackup.backUp(context, repository, settings) }
    }

    /**
     * Add a backup's sessions to the history, skipping those already here.
     * Returns how many were added.
     */
    fun restoreSessions(backup: List<SessionSummary>): Int = history.restore(backup)

    private companion object {
        const val TAG = "SettingsModel"
    }
}
