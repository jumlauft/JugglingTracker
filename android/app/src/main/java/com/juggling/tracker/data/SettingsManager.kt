package com.juggling.tracker.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Which watch the user records with; it decides what the home screen's watch card shows. */
enum class WatchType { GARMIN, WEAR_OS }

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("juggling_settings", Context.MODE_PRIVATE)

    var isAnalyticsEnabled: Boolean by mutableStateOf(prefs.getBoolean(KEY_ANALYTICS, true))
        private set

    var isVoiceEnabled: Boolean by mutableStateOf(prefs.getBoolean(KEY_VOICE, true))
        private set

    var voiceInterval: Int by mutableIntStateOf(prefs.getInt(KEY_VOICE_INTERVAL, 10))
        private set

    var watchType: WatchType by mutableStateOf(
        WatchType.entries.firstOrNull { it.name == prefs.getString(KEY_WATCH_TYPE, null) } ?: WatchType.GARMIN
    )
        private set

    fun updateWatchType(type: WatchType) {
        watchType = type
        prefs.edit().putString(KEY_WATCH_TYPE, type.name).apply()
    }

    /** Who juggles, as last entered in Settings or when exporting; blank until then. */
    var jugglerName: String by mutableStateOf(prefs.getString(KEY_JUGGLER_NAME, null) ?: "")
        private set

    /** The wrist the watch is on, as last picked; null until then. */
    var watchHand: String? by mutableStateOf(prefs.getString(KEY_WATCH_HAND, null))
        private set

    /** The hand that made the first throw, as last picked; null until then. */
    var firstThrowHand: String? by mutableStateOf(prefs.getString(KEY_FIRST_THROW, null))
        private set

    /** Who is juggling, once a name and both hands have been entered; null until then. */
    val currentJuggler: RecordingRepository.Juggler?
        get() {
            val name = jugglerName.takeIf { it.isNotBlank() } ?: return null
            return RecordingRepository.Juggler(name, watchHand ?: return null, firstThrowHand ?: return null)
        }

    fun updateExportDetails(name: String, hand: String, firstThrow: String) {
        jugglerName = name
        watchHand = hand
        firstThrowHand = firstThrow
        prefs.edit()
            .putString(KEY_JUGGLER_NAME, name)
            .putString(KEY_WATCH_HAND, hand)
            .putString(KEY_FIRST_THROW, firstThrow)
            .apply()
    }

    /** Whether the weekly backup is on. It writes to [backupUri], which the user picked. */
    var isBackupEnabled: Boolean by mutableStateOf(prefs.getBoolean(KEY_BACKUP_ENABLED, false))
        private set

    /** The backup file the user picked (usually in Google Drive), as a document URI; null until then. */
    var backupUri: String? by mutableStateOf(prefs.getString(KEY_BACKUP_URI, null))
        private set

    /** The backup file's name as the picker showed it, for Settings to display. */
    var backupFileName: String? by mutableStateOf(prefs.getString(KEY_BACKUP_FILE_NAME, null))
        private set

    /** When the last backup was written, in epoch ms; 0 for never. */
    var lastBackupMillis: Long by mutableStateOf(prefs.getLong(KEY_LAST_BACKUP, 0L))
        private set

    /** True when the last backup attempt could not write the file. */
    var lastBackupFailed: Boolean by mutableStateOf(prefs.getBoolean(KEY_LAST_BACKUP_FAILED, false))
        private set

    fun updateBackupFile(uri: String, fileName: String?) {
        backupUri = uri
        backupFileName = fileName
        lastBackupFailed = false
        prefs.edit()
            .putString(KEY_BACKUP_URI, uri)
            .putString(KEY_BACKUP_FILE_NAME, fileName)
            .putBoolean(KEY_LAST_BACKUP_FAILED, false)
            .apply()
    }

    fun updateBackupEnabled(enabled: Boolean) {
        isBackupEnabled = enabled
        prefs.edit().putBoolean(KEY_BACKUP_ENABLED, enabled).apply()
    }

    fun recordBackupResult(succeeded: Boolean, atMillis: Long = System.currentTimeMillis()) {
        lastBackupFailed = !succeeded
        val editor = prefs.edit().putBoolean(KEY_LAST_BACKUP_FAILED, !succeeded)
        if (succeeded) {
            lastBackupMillis = atMillis
            editor.putLong(KEY_LAST_BACKUP, atMillis)
        }
        editor.apply()
    }

    fun updateAnalyticsEnabled(enabled: Boolean) {
        isAnalyticsEnabled = enabled
        prefs.edit().putBoolean(KEY_ANALYTICS, enabled).apply()
    }

    fun updateVoiceEnabled(enabled: Boolean) {
        isVoiceEnabled = enabled
        prefs.edit().putBoolean(KEY_VOICE, enabled).apply()
    }

    fun updateVoiceInterval(interval: Int) {
        voiceInterval = interval
        prefs.edit().putInt(KEY_VOICE_INTERVAL, interval).apply()
    }

    companion object {
        private const val KEY_ANALYTICS = "analytics_enabled"
        private const val KEY_VOICE = "voice_enabled"
        private const val KEY_VOICE_INTERVAL = "voice_interval"
        private const val KEY_WATCH_TYPE = "watch_type"
        private const val KEY_JUGGLER_NAME = "juggler_name"
        private const val KEY_WATCH_HAND = "watch_hand"
        private const val KEY_FIRST_THROW = "first_throw_hand"
        private const val KEY_BACKUP_ENABLED = "backup_enabled"
        private const val KEY_BACKUP_URI = "backup_uri"
        private const val KEY_BACKUP_FILE_NAME = "backup_file_name"
        private const val KEY_LAST_BACKUP = "last_backup_millis"
        private const val KEY_LAST_BACKUP_FAILED = "last_backup_failed"
    }
}
