import Foundation
import JugglingCore

/// The finished-session history. Counterpart of the Android app's
/// `data/SessionRepository.kt`.
///
/// Sessions live in `fileURL` as JSON Lines: one session object per line,
/// oldest first, with the same keys the Android app writes. Importing a new
/// session appends one line; replacing, restoring or deleting rewrites the
/// whole file atomically, so a crash mid-write leaves the old history.
///
/// Disk writes run in order on a serial queue; the in-memory list is updated
/// at once, so `sessions` never waits on the disk. Use from the main thread.
final class SessionStore {
    static let fileName = "sessions.jsonl"

    private let fileURL: URL
    private let writer = DispatchQueue(label: "com.juggling.tracker.sessions")
    private let errors: (Error) -> Void

    /// The history, newest first.
    private(set) var sessions: [SessionSummary] = []

    init(fileURL: URL, errors: @escaping (Error) -> Void = { _ in }) {
        self.fileURL = fileURL
        self.errors = errors
        load()
    }

    /// The store in the app's Application Support folder.
    static func standard(errors: @escaping (Error) -> Void = { _ in }) -> SessionStore {
        SessionStore(fileURL: AppFiles.supportDirectory.appendingPathComponent(fileName), errors: errors)
    }

    /// Stores one finished session, replacing the one stored under its
    /// timestamp if there is one: a watch resends under the same timestamp
    /// when it retries, or after the user carried on juggling. Returns the
    /// stored summary, or nil when there were no runs to store.
    @discardableResult
    func importSession(
        ballCount: Int,
        timestamp: Int64,
        runs: [Int],
        durationSeconds: Int64 = 0,
        runDurationsMillis: [Int64] = [],
        shapeConsistency: Int? = nil
    ) -> SessionSummary? {
        guard !runs.isEmpty else { return nil }
        let session = SessionSummary.summarize(
            timestamp: timestamp, ballCount: ballCount, runs: runs, durationSeconds: durationSeconds,
            runDurationsMillis: runDurationsMillis, shapeConsistency: shapeConsistency
        )
        store(session)
        return session
    }

    /// Stores a summary built elsewhere (a watch payload), with the same
    /// replace-by-timestamp rule as `importSession`.
    func store(_ session: SessionSummary) {
        if let existing = sessions.firstIndex(where: { $0.timestamp == session.timestamp }) {
            sessions[existing] = session
            rewrite()
        } else {
            sessions.insert(session, at: 0)
            sessions.sort { $0.timestamp > $1.timestamp }
            append(session)
        }
    }

    /// Adds the sessions of a backup that are not stored yet (see
    /// `SessionCSV.newSessions`). Returns how many were added.
    func restore(_ backup: [SessionSummary]) -> Int {
        let added = SessionCSV.newSessions(backup, existing: sessions)
        guard !added.isEmpty else { return 0 }
        sessions.append(contentsOf: added)
        sessions.sort { $0.timestamp > $1.timestamp }
        rewrite()
        return added.count
    }

    func delete(_ session: SessionSummary) {
        guard let index = sessions.firstIndex(of: session) else { return }
        sessions.remove(at: index)
        rewrite()
    }

    /// Waits for every queued write. For tests.
    func flush() {
        writer.sync {}
    }

    // MARK: - Disk

    private static func line(_ session: SessionSummary) -> Data? {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        guard var data = try? encoder.encode(session) else { return nil }
        data.append(0x0A)
        return data
    }

    private func append(_ session: SessionSummary) {
        guard let line = Self.line(session) else { return }
        let url = fileURL
        let errors = errors
        writer.async {
            do {
                if let handle = try? FileHandle(forWritingTo: url) {
                    defer { try? handle.close() }
                    try handle.seekToEnd()
                    try handle.write(contentsOf: line)
                } else {
                    try FileManager.default.createDirectory(
                        at: url.deletingLastPathComponent(), withIntermediateDirectories: true
                    )
                    try line.write(to: url, options: .atomic)
                }
            } catch {
                errors(error)
            }
        }
    }

    private func rewrite() {
        var data = Data()
        for session in sessions.reversed() {
            if let line = Self.line(session) { data.append(line) }
        }
        let url = fileURL
        let errors = errors
        writer.async {
            do {
                try FileManager.default.createDirectory(
                    at: url.deletingLastPathComponent(), withIntermediateDirectories: true
                )
                try data.write(to: url, options: .atomic)
            } catch {
                errors(error)
            }
        }
    }

    private func load() {
        guard let text = try? String(contentsOf: fileURL, encoding: .utf8) else { return }
        let decoder = JSONDecoder()
        var byTimestamp: [Int64: SessionSummary] = [:]
        for line in text.split(separator: "\n") where !line.trimmingCharacters(in: .whitespaces).isEmpty {
            // A line cut short by a crash is skipped.
            guard let session = try? decoder.decode(SessionSummary.self, from: Data(line.utf8)) else { continue }
            // The timestamp is the session's identity; a later line wins.
            byTimestamp[session.timestamp] = session
        }
        sessions = byTimestamp.values.sorted { $0.timestamp > $1.timestamp }
    }
}

/// Where the app keeps its files.
enum AppFiles {
    /// Application Support: the history and the recordings, hidden from the Files app.
    static var supportDirectory: URL {
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// A fresh scratch folder for files handed to another app (mail, share sheet).
    static func shareDirectory() -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("shared", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}
