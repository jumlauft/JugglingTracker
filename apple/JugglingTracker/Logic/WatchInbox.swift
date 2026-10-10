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
    /// the payload). Returns it, or nil when the payload holds no session.
    @discardableResult
    func importSession(_ payload: [String: Any], from source: Source) -> SessionSummary? {
        guard let session = SessionSummary.fromWatchPayload(payload) else { return nil }
        Telemetry.shared.log("import_session", [
            "ball_count": session.ballCount,
            "run_count": session.runCount,
            "total_throws": session.totalThrows,
            "source": source.analyticsName,
        ])
        sessions?.store(session)
        publish(.sessionStored(session))
        return session
    }

    // MARK: - Recorded runs
    //
    // The Garmin cannot send a dictionary holding hundreds of numbers, so a
    // run arrives as rec_start, a series of rec_chunk parts, then rec_end.
    // Only rec_end is acknowledged, after the run has been written. A retry
    // from the watch starts again with rec_start.

    private struct IncomingRecording {
        let id: Int64
        let balls: Int
        let catches: Int
        let detected: Int
        let sampleRate: Int
        let totalSamples: Int
        let totalChunks: Int
        var x: [Int] = []
        var y: [Int] = []
        var z: [Int] = []
        var nextChunk = 0
    }

    private var incoming: IncomingRecording?

    func startRecordingTransfer(_ payload: [String: Any]) {
        guard let id = WatchProtocol.int64(payload["id"]),
              let balls = WatchProtocol.int(payload["balls"]),
              let catches = WatchProtocol.int(payload["catches"])
        else { return }
        incoming = IncomingRecording(
            id: id,
            balls: balls,
            catches: catches,
            detected: WatchProtocol.int(payload["detected"]) ?? 0,
            sampleRate: WatchProtocol.int(payload["sampleRate"]) ?? 25,
            totalSamples: WatchProtocol.int(payload["samples"]) ?? 0,
            totalChunks: WatchProtocol.int(payload["chunks"]) ?? 0
        )
    }

    func appendRecordingChunk(_ payload: [String: Any]) {
        guard var run = incoming, WatchProtocol.int64(payload["id"]) == run.id,
              let index = WatchProtocol.int(payload["i"])
        else { return }
        // Already have this one: a transport may deliver a message twice.
        if index < run.nextChunk { return }
        if index > run.nextChunk {
            // A chunk was lost; drop the run rather than write bad training
            // data. rec_end then goes unacked and the watch offers a retry.
            incoming = nil
            return
        }
        guard let xs = WatchProtocol.intList(payload["x"]),
              let ys = WatchProtocol.intList(payload["y"]),
              let zs = WatchProtocol.intList(payload["z"])
        else { return }
        run.x += xs
        run.y += ys
        run.z += zs
        run.nextChunk = index + 1
        incoming = run
    }

    /// Returns true when a complete run was written.
    func finishRecordingTransfer(_ payload: [String: Any], from source: Source) -> Bool {
        guard let run = incoming else { return false }
        incoming = nil
        guard WatchProtocol.int64(payload["id"]) == run.id,
              run.nextChunk == run.totalChunks,
              run.x.count == run.totalSamples
        else { return false }

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
