package com.juggling.tracker.logic

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.analytics.FirebaseAnalytics
import com.juggling.tracker.backup.SessionBackup
import com.juggling.tracker.data.SessionRepository
import com.juggling.tracker.data.SettingsManager
import com.juggling.tracker.data.SettingsValues
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
 *
 * This is where the Compose state lives: [SettingsManager] only stores. The
 * state follows every stored change, including those the backup worker makes.
 */
class SettingsModel(
    private val settings: SettingsManager? = null,
    private val history: HistoryModel,
    private val repository: SessionRepository? = null,
    private val analytics: FirebaseAnalytics? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var values by mutableStateOf(settings?.read() ?: SettingsValues())

    private val listener = settings?.addListener(::reload)

    /** Stops following the stored settings; the view model calls this when it goes. */
    fun close() {
        listener?.let { settings?.removeListener(it) }
    }

    /** Re-reads what is stored. */
    private fun reload() {
        settings?.let { values = it.read() }
    }

    /** Stores a change, then shows it at once. */
    private inline fun change(write: SettingsManager.() -> Unit) {
        val settings = settings
        if (settings != null) {
            settings.write()
            reload()
        }
    }

    val isAnalyticsEnabled get() = values.isAnalyticsEnabled
    val isVoiceEnabled get() = values.isVoiceEnabled
    val voiceInterval get() = values.voiceInterval
    val watchType: WatchType get() = values.watchType

    fun setWatchType(type: WatchType) = change { updateWatchType(type) }

    val jugglerName get() = values.jugglerName
    val watchHand get() = values.watchHand
    val firstThrowHand get() = values.firstThrowHand

    /**
     * Who is juggling now, once a name and both hands have been entered. New
     * recordings are saved with it, and an export tags older runs that have
     * none with it.
     */
    val currentJuggler: Juggler? get() = values.currentJuggler

    /** Remember who juggles, the watch wrist and the first-throw hand for the next recordings. */
    fun setExportDetails(name: String, hand: String, firstThrow: String) =
        change { updateExportDetails(name.trim(), hand, firstThrow) }

    fun toggleAnalytics(enabled: Boolean) {
        change { updateAnalyticsEnabled(enabled) }
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

    fun toggleVoice(enabled: Boolean) = change { updateVoiceEnabled(enabled) }

    fun setVoiceInterval(interval: Int) = change { updateVoiceInterval(interval) }

    /** Whether a voice announcement is due at [count] catches. */
    fun shouldAnnounce(count: Int): Boolean =
        isVoiceEnabled && voiceInterval > 0 && count > 0 && count % voiceInterval == 0

    // ── Backup (see SessionBackup) ──────────────────────────────────────

    val isBackupEnabled get() = values.isBackupEnabled
    val backupUri get() = values.backupUri
    val backupFileName get() = values.backupFileName
    val lastBackupMillis get() = values.lastBackupMillis
    val lastBackupFailed get() = values.lastBackupFailed

    /** Use [uri] as the backup file; false when the app may not write there. */
    fun connectBackupFile(context: Context, uri: Uri): Boolean {
        val settings = settings ?: return false
        return SessionBackup.connect(context, uri, settings).also { reload() }
    }

    fun setBackupEnabled(context: Context, enabled: Boolean) {
        val settings = settings ?: return
        SessionBackup.setEnabled(context, settings, enabled)
        reload()
    }

    /** Writes the backup file now, off the main thread. */
    suspend fun backUpNow(context: Context): SessionBackup.Result {
        val repository = repository ?: return SessionBackup.Result.FAILED
        val settings = settings ?: return SessionBackup.Result.FAILED
        return withContext(ioDispatcher) { SessionBackup.backUp(context, repository, settings) }
            .also { reload() }
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
