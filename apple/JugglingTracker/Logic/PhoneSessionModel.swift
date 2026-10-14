import Foundation
import JugglingCore
import Observation

/// What the phone session screen says about the session, as a key the screen
/// turns into text from the String Catalog. The Android app has the same
/// cases, with the same texts in strings.xml.
enum PhoneSessionStatus: Equatable {
    case ready
    case mountPhone
    case runActive
    case waitingForRun
    case saved
    case cancelled
    case noRuns
    case sensorUnavailable
    case sensorFrozen

    var text: String {
        switch self {
        case .ready: String(localized: "Ready to record with phone")
        case .mountPhone: String(localized: "Mount the phone to your wrist and start juggling")
        case .runActive: String(localized: "Run active")
        case .waitingForRun: String(localized: "Waiting for next run")
        case .saved: String(localized: "Phone session saved")
        case .cancelled: String(localized: "Phone session cancelled")
        case .noRuns: String(localized: "No phone runs to save")
        case .sensorUnavailable: String(localized: "Phone accelerometer unavailable")
        case .sensorFrozen: String(localized: "Accelerometer is not responding. Restart the phone and try again.")
        }
    }
}

struct PhoneSessionState: Equatable {
    var selectedBallCount = 3
    var isRecording = false
    var currentCount = 0
    var previousCount = 0
    var completedRuns: [Int] = []
    var runDurationsMillis: [Int64] = []
    var sessionRunCount = 0
    var sessionAverage = 0.0
    var sessionMax = 0
    var elapsedSeconds: Int64 = 0
    var status: PhoneSessionStatus = .ready
    /// Why the last session ended without being saved, shown in red.
    var sensorError: PhoneSessionStatus?
}

/// A juggling session counted with the phone's own accelerometer, strapped to
/// the wrist: the detector, the frozen-sensor check and the session's stats.
/// Counterpart of the Android app's `PhoneSessionModel`.
@MainActor
@Observable
final class PhoneSessionModel {
    /// Seconds of an unchanging accelerometer before calling it broken.
    static let frozenSensorSeconds = 5
    static let frozenSensorSamples = frozenSensorSeconds * JugglingDetector.sampleRate

    private(set) var state = PhoneSessionState()

    @ObservationIgnored private let history: HistoryModel
    /// Called with the new count whenever a catch is counted.
    @ObservationIgnored var onCatch: (Int) -> Void = { _ in }

    @ObservationIgnored private var detector: JugglingDetector?
    @ObservationIgnored private var startedAt: Date?
    @ObservationIgnored private var firstSampleMs: Int64?
    @ObservationIgnored private var throttle = SampleThrottle()
    // A wedged accelerometer keeps delivering the identical vector; a still
    // phone still jitters in the low bits, so only a stuck sensor repeats
    // all three axes exactly for seconds.
    @ObservationIgnored private var frozenSamples = 0
    @ObservationIgnored private var lastRaw: (Double, Double, Double)?

    init(history: HistoryModel) {
        self.history = history
    }

    func selectBallCount(_ count: Int) {
        guard !state.isRecording else { return }
        state.selectedBallCount = min(max(count, 3), 9)
    }

    func start(ballCount: Int, at start: Date = Date()) {
        let balls = min(max(ballCount, 3), 9)
        detector = JugglingDetector(ballCount: balls)
        startedAt = start
        firstSampleMs = nil
        throttle.reset()
        resetFrozenTracking()
        state = PhoneSessionState(selectedBallCount: balls, isRecording: true, status: .mountPhone)
        Telemetry.shared.log("start_phone_session", ["ball_count": balls])
    }

    /// Takes a batch of raw accelerometer samples and updates the screen once per batch.
    func process(_ samples: [PhoneAccelSample]) {
        var lastProcessedMs: Int64?
        for s in samples {
            guard let detector, state.isRecording else { break }
            let sampleMs = s.timestampNanos / 1_000_000
            guard throttle.accept(sampleMs) else { continue }
            if firstSampleMs == nil { firstSampleMs = sampleMs }

            if isSensorFrozen(s) {
                markSensorUnavailable(.sensorFrozen)
                return
            }

            let oldCount = detector.currentCount
            detector.processSampleMs2(s.ax, s.ay, s.az, nowMs: sampleMs)
            let newCount = detector.currentCount
            detector.checkAutoFinish(nowMs: sampleMs)
            if newCount > oldCount { onCatch(newCount) }
            lastProcessedMs = sampleMs
        }
        if let lastProcessedMs { updateFromDetector(lastProcessedMs) }
    }

    private func isSensorFrozen(_ s: PhoneAccelSample) -> Bool {
        if let lastRaw, lastRaw == (s.ax, s.ay, s.az) {
            frozenSamples += 1
        } else {
            frozenSamples = 0
            lastRaw = (s.ax, s.ay, s.az)
        }
        return frozenSamples >= Self.frozenSensorSamples
    }

    private func resetFrozenTracking() {
        frozenSamples = 0
        lastRaw = nil
    }

    /// Ends the session and stores it in the history. Returns the number of
    /// runs saved, or nil when there was no session or no run to save.
    @discardableResult
    func stopAndSave(at stop: Date = Date()) -> Int? {
        guard let detector else { return nil }
        detector.finishCurrentRun()
        let runs = detector.runCatches
        let balls = min(max(detector.ballCount, 3), 9)
        Telemetry.shared.log("stop_phone_session", [
            "ball_count": balls,
            "run_count": runs.count,
            "total_throws": runs.reduce(0, +),
            "success": true,
        ])

        guard !runs.isEmpty else {
            markSensorUnavailable(.noRuns)
            return nil
        }
        let started = startedAt ?? stop
        let timestamp = Int64((started.timeIntervalSince1970 * 1000).rounded(.down))
        history.store(SessionSummary.summarize(
            timestamp: timestamp,
            ballCount: balls,
            runs: runs,
            durationSeconds: max(Int64(stop.timeIntervalSince(started)), 0),
            runDurationsMillis: detector.runDurationsMillis
        ))
        reset(.saved)
        return runs.count
    }

    func cancel() {
        reset(.cancelled)
    }

    /// Ends the session without saving it and shows `reason` in red.
    func markSensorUnavailable(_ reason: PhoneSessionStatus = .sensorUnavailable) {
        reset(reason)
        state.sensorError = reason
    }

    private func updateFromDetector(_ sampleMs: Int64) {
        guard let detector else { return }
        let elapsed = max((sampleMs - (firstSampleMs ?? sampleMs)) / 1000, 0)
        let status: PhoneSessionStatus
        if detector.currentCount > 0 || detector.isRunActive {
            status = .runActive
        } else if detector.sessionRuns > 0 {
            status = .waitingForRun
        } else {
            status = .mountPhone
        }
        state.currentCount = detector.currentCount
        state.previousCount = detector.previousCount
        state.completedRuns = detector.runCatches
        state.runDurationsMillis = detector.runDurationsMillis
        state.sessionRunCount = detector.sessionRuns
        state.sessionAverage = detector.sessionAverage
        state.sessionMax = detector.sessionMax
        state.elapsedSeconds = elapsed
        state.status = status
        state.sensorError = nil
    }

    private func reset(_ status: PhoneSessionStatus) {
        let balls = state.selectedBallCount
        detector = nil
        startedAt = nil
        firstSampleMs = nil
        throttle.reset()
        resetFrozenTracking()
        state = PhoneSessionState(selectedBallCount: balls, status: status)
    }
}
