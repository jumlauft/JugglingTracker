package com.juggling.tracker.backup

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.juggling.tracker.JugglingTrackerApplication
import com.juggling.tracker.data.SessionCsv
import com.juggling.tracker.data.SessionRepository
import com.juggling.tracker.data.SettingsManager
import com.juggling.tracker.util.CrashlyticsUtils
import java.util.concurrent.TimeUnit

/**
 * The weekly backup of the session history to a CSV file the user picked.
 *
 * The file is picked with the system file picker (Storage Access Framework),
 * normally in Google Drive, so the app needs no Google sign-in of its own:
 * Drive's document provider uploads what is written here. The app keeps a
 * persistable permission to that one file and overwrites it on every backup.
 */
object SessionBackup {
    private const val TAG = "SessionBackup"
    private const val WORK_NAME = "weekly_session_backup"

    /** The name the file picker suggests for a new backup file. */
    const val SUGGESTED_FILE_NAME = "juggling_tracker_backup.csv"

    enum class Result { SAVED, NOTHING_TO_SAVE, NO_FILE, FAILED }

    /**
     * Keep the permission to [uri], just picked, and remember it as the backup
     * file. Returns false when the location cannot be kept across restarts,
     * which an automatic backup needs.
     */
    fun connect(context: Context, uri: Uri, settings: SettingsManager): Boolean {
        return try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            settings.updateBackupFile(uri.toString(), displayName(context.contentResolver, uri))
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Backup location gives no lasting permission", e)
            false
        }
    }

    /** Turn the weekly backup on or off. */
    fun setEnabled(context: Context, settings: SettingsManager, enabled: Boolean) {
        settings.updateBackupEnabled(enabled)
        val workManager = WorkManager.getInstance(context)
        if (enabled) {
            val request = PeriodicWorkRequestBuilder<BackupWorker>(7, TimeUnit.DAYS)
                .setConstraints(
                    // Drive may need to fetch the file before it can be written.
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        } else {
            workManager.cancelUniqueWork(WORK_NAME)
        }
    }

    /** Write the whole history to the backup file now. Blocks; call off the main thread. */
    fun backUp(context: Context, repository: SessionRepository, settings: SettingsManager): Result {
        val uri = settings.backupUri?.let(Uri::parse) ?: return Result.NO_FILE
        val sessions = repository.getSessions()
        // An empty history is never written: after a reinstall it would wipe
        // the backup the user is about to restore from.
        if (sessions.isEmpty()) return Result.NOTHING_TO_SAVE
        return try {
            writeDocument(context.contentResolver, uri, SessionCsv.write(sessions).toByteArray())
            settings.recordBackupResult(succeeded = true)
            Result.SAVED
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed", e)
            CrashlyticsUtils.recordException(e)
            settings.recordBackupResult(succeeded = false)
            Result.FAILED
        }
    }

    /** The CSV text of a backup file the user picked. Blocks; call off the main thread. */
    fun read(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            ?: throw java.io.IOException("Cannot open $uri")

    /**
     * Replace the file's contents with [bytes]. "wt" truncates; a provider
     * that refuses it gets "w", and the file is cut to the new length
     * afterwards so an older, longer backup leaves no rows behind.
     */
    private fun writeDocument(resolver: ContentResolver, uri: Uri, bytes: ByteArray) {
        val descriptor = try {
            resolver.openFileDescriptor(uri, "wt")
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            resolver.openFileDescriptor(uri, "w")
        } ?: throw java.io.IOException("Cannot open $uri")
        // Closing the stream closes the descriptor, which is when Drive
        // takes the new contents.
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { out ->
            out.write(bytes)
            out.flush()
            try {
                out.channel.truncate(bytes.size.toLong())
            } catch (e: Exception) {
                // A pipe cannot be truncated; it holds only what was written.
            }
        }
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }
}

/** Runs [SessionBackup.backUp] once a week, scheduled by [SessionBackup.setEnabled]. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as JugglingTrackerApplication
        if (!app.settingsManager.isBackupEnabled) return Result.success()
        return when (SessionBackup.backUp(app, app.sessionRepository, app.settingsManager)) {
            SessionBackup.Result.SAVED, SessionBackup.Result.NOTHING_TO_SAVE, SessionBackup.Result.NO_FILE -> Result.success()
            // Settings shows the failure; next week's run tries again.
            SessionBackup.Result.FAILED -> Result.failure()
        }
    }
}
