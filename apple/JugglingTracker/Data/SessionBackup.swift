import BackgroundTasks
import Foundation
import JugglingCore

/// The weekly backup of the session history to a CSV file in a folder the
/// user picked, normally in iCloud Drive. Counterpart of the Android app's
/// `backup/SessionBackup.kt`, writing the same CSV (`SessionCSV`), so a backup
/// made on either phone restores on the other.
///
/// iOS gives an app lasting access to a folder picked in the Files picker
/// through a security-scoped bookmark; the app keeps one and overwrites
/// `fileName` inside it on every backup.
///
/// iOS runs background refreshes when it sees fit, so on top of the weekly
/// `BGAppRefreshTask` the app backs up whenever it comes to the foreground and
/// the last backup is a week old.
enum SessionBackup {
    static let fileName = "juggling_tracker_backup.csv"
    static let taskIdentifier = "com.juggling.tracker.backup"
    static let interval: TimeInterval = 7 * 24 * 60 * 60

    enum Result: Equatable {
        case saved
        case nothingToSave
        case noFolder
        case failed
    }

    /// Remembers `folder`, just picked, as the backup location. Returns false
    /// when the app cannot keep access to it.
    static func connect(folder: URL, settings: AppSettings) -> Bool {
        let accessing = folder.startAccessingSecurityScopedResource()
        defer { if accessing { folder.stopAccessingSecurityScopedResource() } }
        guard let bookmark = try? folder.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil) else {
            return false
        }
        settings.updateBackupFolder(bookmark: bookmark, name: folder.lastPathComponent)
        return true
    }

    /// The backup folder from the stored bookmark, refreshing a stale one.
    static func folder(settings: AppSettings) -> URL? {
        guard let bookmark = settings.backupBookmark else { return nil }
        var stale = false
        guard let url = try? URL(resolvingBookmarkData: bookmark, options: [], relativeTo: nil, bookmarkDataIsStale: &stale) else {
            return nil
        }
        if stale, let fresh = try? url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil) {
            settings.updateBackupFolder(bookmark: fresh, name: settings.backupFolderName)
        }
        return url
    }

    /// Writes the whole history to the backup file now.
    static func backUp(sessions: [SessionSummary], settings: AppSettings) -> Result {
        guard let folder = folder(settings: settings) else { return .noFolder }
        // An empty history is never written: after a reinstall it would wipe
        // the backup the user is about to restore from.
        guard !sessions.isEmpty else { return .nothingToSave }
        let accessing = folder.startAccessingSecurityScopedResource()
        defer { if accessing { folder.stopAccessingSecurityScopedResource() } }
        do {
            let file = folder.appendingPathComponent(fileName)
            try write(Data(SessionCSV.write(sessions).utf8), to: file)
            settings.recordBackupResult(succeeded: true)
            return .saved
        } catch {
            Telemetry.shared.record(error)
            settings.recordBackupResult(succeeded: false)
            return .failed
        }
    }

    /// Replaces the file's contents, coordinated so iCloud Drive picks up the
    /// new version.
    private static func write(_ data: Data, to file: URL) throws {
        var coordinationError: NSError?
        var writeError: Error?
        NSFileCoordinator().coordinate(writingItemAt: file, options: .forReplacing, error: &coordinationError) { url in
            do {
                try data.write(to: url, options: .atomic)
            } catch {
                writeError = error
            }
        }
        if let error = coordinationError ?? writeError { throw error }
    }

    /// The text of a backup or export the user picked to restore from.
    static func read(_ url: URL) throws -> String {
        let accessing = url.startAccessingSecurityScopedResource()
        defer { if accessing { url.stopAccessingSecurityScopedResource() } }
        var coordinationError: NSError?
        var result: Swift.Result<String, Error> = .failure(CocoaError(.fileReadUnknown))
        NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &coordinationError) { readURL in
            result = Swift.Result { try String(contentsOf: readURL, encoding: .utf8) }
        }
        if let coordinationError { throw coordinationError }
        return try result.get()
    }

    /// True when the weekly backup is on and the last one is a week old.
    static func isDue(settings: AppSettings, now: Date = Date()) -> Bool {
        guard settings.isBackupEnabled else { return false }
        let last = Date(timeIntervalSince1970: TimeInterval(settings.lastBackupMillis) / 1000)
        return now.timeIntervalSince(last) >= interval
    }

    /// Asks iOS for the next weekly background run.
    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: taskIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: interval)
        try? BGTaskScheduler.shared.submit(request)
    }

    static func cancelSchedule() {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: taskIdentifier)
    }
}
