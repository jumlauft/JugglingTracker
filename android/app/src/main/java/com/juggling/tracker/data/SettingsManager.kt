package com.juggling.tracker.data

import android.content.Context
import android.content.SharedPreferences
import com.juggling.tracker.shared.Juggler

/** Which watch the user records with; it decides what the home screen's watch card shows. */
enum class WatchType { GARMIN, WEAR_OS }

/** Every stored setting at one moment, with the defaults a fresh install has. */
data class SettingsValues(
    val isAnalyticsEnabled: Boolean = true,
    val isVoiceEnabled: Boolean = true,
    val voiceInterval: Int = 10,
    val watchType: WatchType = WatchType.GARMIN,
    /** Who juggles, as last entered in Settings or when exporting; blank until then. */
    val jugglerName: String = "",
    /** The wrist the watch is on, as last picked; null until then. */
    val watchHand: String? = null,
    /** The hand that made the first throw, as last picked; null until then. */
    val firstThrowHand: String? = null,
    /** Whether the weekly backup is on. It writes to [backupUri], which the user picked. */
    val isBackupEnabled: Boolean = false,
    /** The backup file the user picked (usually in Google Drive), as a document URI; null until then. */
    val backupUri: String? = null,
    /** The backup file's name as the picker showed it, for Settings to display. */
    val backupFileName: String? = null,
    /** When the last backup was written, in epoch ms; 0 for never. */
    val lastBackupMillis: Long = 0L,
    /** True when the last backup attempt could not write the file. */
    val lastBackupFailed: Boolean = false,
) {
    /** Who is juggling, once a name and both hands have been entered; null until then. */
    val currentJuggler: Juggler?
        get() {
            val name = jugglerName.takeIf { it.isNotBlank() } ?: return null
            return Juggler(name, watchHand ?: return null, firstThrowHand ?: return null)
        }
}

/**
 * The settings in SharedPreferences. Plain storage with no Compose state, so
 * the backup worker and the inbox can use it with no screen open;
 * SettingsModel holds what the Settings screen observes and follows every
 * change through [addListener].
 */
class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("juggling_settings", Context.MODE_PRIVATE)

    // SharedPreferences keeps its listeners weakly, so they are held here.
    private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    /** What is stored now. */
    fun read(): SettingsValues = SettingsValues(
        isAnalyticsEnabled = prefs.getBoolean(KEY_ANALYTICS, true),
        isVoiceEnabled = prefs.getBoolean(KEY_VOICE, true),
        voiceInterval = prefs.getInt(KEY_VOICE_INTERVAL, 10),
        watchType = WatchType.entries.firstOrNull { it.name == prefs.getString(KEY_WATCH_TYPE, null) } ?: WatchType.GARMIN,
        jugglerName = prefs.getString(KEY_JUGGLER_NAME, null) ?: "",
        watchHand = prefs.getString(KEY_WATCH_HAND, null),
        firstThrowHand = prefs.getString(KEY_FIRST_THROW, null),
        isBackupEnabled = prefs.getBoolean(KEY_BACKUP_ENABLED, false),
        backupUri = prefs.getString(KEY_BACKUP_URI, null),
        backupFileName = prefs.getString(KEY_BACKUP_FILE_NAME, null),
        lastBackupMillis = prefs.getLong(KEY_LAST_BACKUP, 0L),
        lastBackupFailed = prefs.getBoolean(KEY_LAST_BACKUP_FAILED, false),
    )

    /**
     * Calls [onChange] on the main thread after every change to a stored
     * setting, from anywhere in the app, until [removeListener] is called with
     * what this returns.
     */
    fun addListener(onChange: () -> Unit): Any {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
        listeners.add(listener)
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun removeListener(handle: Any) {
        val listener = handle as? SharedPreferences.OnSharedPreferenceChangeListener ?: return
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        listeners.remove(listener)
    }

    val isAnalyticsEnabled: Boolean get() = prefs.getBoolean(KEY_ANALYTICS, true)
    val watchType: WatchType get() = read().watchType
    val isBackupEnabled: Boolean get() = prefs.getBoolean(KEY_BACKUP_ENABLED, false)
    val backupUri: String? get() = prefs.getString(KEY_BACKUP_URI, null)
    val currentJuggler: Juggler? get() = read().currentJuggler

    fun updateWatchType(type: WatchType) {
        prefs.edit().putString(KEY_WATCH_TYPE, type.name).apply()
    }

    fun updateExportDetails(name: String, hand: String, firstThrow: String) {
        prefs.edit()
            .putString(KEY_JUGGLER_NAME, name)
            .putString(KEY_WATCH_HAND, hand)
            .putString(KEY_FIRST_THROW, firstThrow)
            .apply()
    }

    fun updateBackupFile(uri: String, fileName: String?) {
        prefs.edit()
            .putString(KEY_BACKUP_URI, uri)
            .putString(KEY_BACKUP_FILE_NAME, fileName)
            .putBoolean(KEY_LAST_BACKUP_FAILED, false)
            .apply()
    }

    fun updateBackupEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BACKUP_ENABLED, enabled).apply()
    }

    fun recordBackupResult(succeeded: Boolean, atMillis: Long = System.currentTimeMillis()) {
        val editor = prefs.edit().putBoolean(KEY_LAST_BACKUP_FAILED, !succeeded)
        if (succeeded) editor.putLong(KEY_LAST_BACKUP, atMillis)
        editor.apply()
    }

    fun updateAnalyticsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ANALYTICS, enabled).apply()
    }

    fun updateVoiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VOICE, enabled).apply()
    }

    fun updateVoiceInterval(interval: Int) {
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
