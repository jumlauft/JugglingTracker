import Foundation
import JugglingCore

/// Where every message from either watch lands. It stores what the message
/// carries and decides whether the watch gets its `ack`. Counterpart of the
/// Android app's `logic/WatchInbox.kt`, with the same rules.
///
/// It lives as long as the app, not a screen, so the watch links (Garmin
/// through Connect IQ in `GarminLink`, Apple Watch through WatchConnectivity
/// in `AppleWatchLink`) hand their messages here and the screens follow along through
/// `onEvent`. Use from the main thread.
///
/// The ack goes back only once the data is stored. A watch that gets no ack
/// keeps its data and offers to retry, so a run that arrived incomplete, or a
/// payload that could not be read, must never be acknowledged.
final class WatchInbox {
    enum Source: String {
        case garmin
        case appleWatch

        var analyticsName: String {
            switch self {
            case .garmin: "garmin_watch"
            case .appleWatch: "apple_watch"
            }
        }
    }

    enum Event: Equatable {
        /// A message from `source` arrived. `more` is true while the transfer
        /// it belongs to is still going (a run's header or one of its chunks).
        case receiving(Source, more: Bool)
        /// `session` was stored, replacing an earlier copy under its timestamp if there was one.
        case sessionStored(SessionSummary)
        /// A recorded run was written.
        case recordingStored
    }

    private let sessions: SessionStore?
    private let recordings: RecordingStore?
    private let currentJuggler: () -> Juggler?
    private var listeners: [UUID: (Event) -> Void] = [:]

    init(sessions: SessionStore?, recordings: RecordingStore?, currentJuggler: @escaping () -> Juggler? = { nil }) {
        self.sessions = sessions
        self.recordings = recordings
        self.currentJuggler = currentJuggler
    }

    @discardableResult
    func addListener(_ listener: @escaping (Event) -> Void) -> UUID {
        let id = UUID()
        listeners[id] = listener
        return id
    }

    func removeListener(_ id: UUID) {
        listeners[id] = nil
    }

    private func publish(_ event: Event) {
        for listener in listeners.values { listener(event) }
    }

    /// Handles one message from a watch and returns the ack to send back, or
    /// nil when none may go: run headers and chunks are never acked, and
    /// neither is anything that was not stored.
    @discardableResult
    func receive(_ payload: [String: Any], from source: Source) -> [String: Any]? {
        guard let type = payload["type"] as? String else { return nil }
        switch type {
        case WatchProtocol.typeRecChunk:
            // The watch chains chunks and waits for an ack only at the end.
            // Each one still keeps the card on "receiving".
            appendRecordingChunk(payload)
            publish(.receiving(source, more: true))
            return nil
        case WatchProtocol.typeRecStart:
            publish(.receiving(source, more: true))
            startRecordingTransfer(payload)
            return nil
        case WatchProtocol.typeSession:
            publish(.receiving(source, more: false))
            guard importSession(payload, from: source) != nil else { return nil }
            return WatchProtocol.ack(timestamp: WatchProtocol.ackTimestamp(of: payload))
        case WatchProtocol.typeRecEnd:
            publish(.receiving(source, more: false))
            guard finishRecordingTransfer(payload, from: source) else { return nil }
            return WatchProtocol.ack(timestamp: WatchProtocol.ackTimestamp(of: payload))
        default:
            return nil
        }
    }

    // MARK: - Sessions

    /// Stores one finished session (see `SessionSummary.fromWatchPayload` for
    /// the payload). Returns it, or nil when the payload holds no session or
    /// it could not be written; then no ack may go.
    @discardableResult
    func importSession(_ payload: [String: Any], from source: Source) -> SessionSummary? {
        guard let session = SessionSummary.fromWatchPayload(payload) else { return nil }
        Telemetry.shared.log("import_session", [
            "ball_count": session.ballCount,
            "run_count": session.runCount,
            "total_throws": session.totalThrows,
            "source": source.analyticsName,
        ])
        if let sessions, !sessions.store(session) { return nil }
        publish(.sessionStored(session))
        return session
    }

    // MARK: - Recorded runs
    //
    // A run arrives as rec_start, rec_chunk parts and rec_end, put back
    // together by JugglingCore's RecordingTransfer. Only rec_end is
    // acknowledged, after the run has been written.

    private var transfer = RecordingTransfer()

    func startRecordingTransfer(_ payload: [String: Any]) {
        transfer.start(payload)
    }

    func appendRecordingChunk(_ payload: [String: Any]) {
        transfer.append(payload)
    }

    /// Returns true when a complete run was written.
    func finishRecordingTransfer(_ payload: [String: Any], from source: Source) -> Bool {
        guard let run = transfer.finish(payload) else { return false }

        Telemetry.shared.log("import_recording", [
            "ball_count": run.balls,
            "catches": run.catches,
            "source": source.analyticsName,
        ])

        // A run with no samples has nothing to write, so it counts as stored.
        if let recordings, !run.x.isEmpty {
            let written = recordings.saveRecording(
                balls: run.balls, catches: run.catches, detected: run.detected, sampleRate: run.sampleRate,
                timestamp: run.id, x: run.x, y: run.y, z: run.z,
                source: RecordingStore.sourceWatch, juggler: currentJuggler()
            )
            guard written != nil else { return false }
        }
        publish(.recordingStored)
        return true
    }
}
