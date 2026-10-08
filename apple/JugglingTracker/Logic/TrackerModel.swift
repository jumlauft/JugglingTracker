import Foundation
import JugglingCore
import Observation

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
    var statusMessage = "Ready to record with phone"
    var sensorError: String?
}

enum RawRecordingStep: Equatable {
    case idle
    case selectBalls
    case recording
    case enterCatches
}

struct RawRecordingState: Equatable {
    var step: RawRecordingStep = .idle
    var selectedBallCount = 3
    var sampleCount = 0
}

/// The app's state and logic, behind every screen. Counterpart of the Android
/// app's `JugglingViewModel`, with the same rules for phone sessions, raw
/// recordings, the watch card and the history.
@MainActor
@Observable
final class TrackerModel {
    /// How long the watch card shows "receiving" after a transfer's last message.
    static let receivingDisplayMs: UInt64 = 1500
    /// How long the card waits for a transfer's next message before giving up
    /// on it. Longer than the watches' own 10 s sync timeout.
    static let receivingStallMs: UInt64 = 15_000
    /// Seconds of an unchanging accelerometer before calling it broken.
    static let frozenSensorSeconds = 5
    static let frozenSensorSamples = frozenSensorSeconds * JugglingDetector.sampleRate

    let settings: AppSettings
    @ObservationIgnored let sessionStore: SessionStore?
    @ObservationIgnored let recordingStore: RecordingStore?
    @ObservationIgnored let inbox: WatchInbox
    /// Speaks announcements; the app wires it to `Voice`.
    @ObservationIgnored var speak: (String) -> Void = { _ in }

    // MARK: Watch card

    var garminStatus: GarminConnectionStatus = .notInitialized
    var garminMessage = ""
    var appleWatchStatus: AppleWatchConnectionStatus = .checking

    // While a watch is sending, its card stays on "receiving"; a status its
    // link reports meanwhile waits here and shows once the transfer ends.
    @ObservationIgnored private var garminReceiving: Task<Void, Never>?
    @ObservationIgnored private var appleWatchReceiving: Task<Void, Never>?
    @ObservationIgnored private var garminWhileReceiving: GarminConnectionStatus?
    @ObservationIgnored private var appleWatchWhileReceiving: AppleWatchConnectionStatus?

    // MARK: Screens

    private(set) var sessions: [SessionSummary] = []
    private(set) var recordings: [RecordingStore.Summary] = []
    private(set) var recordingCount = 0
    private(set) var phoneSession = PhoneSessionState()
    private(set) var rawRecording = RawRecordingState()
    /// A short message the screen shows for a moment, like an Android toast.
    var toast: String?

    // MARK: Sample processing

    @ObservationIgnored private var detector: JugglingDetector?
    @ObservationIgnored private var phoneSessionStartedAt: Date?
    @ObservationIgnored private var firstSampleMs: Int64?
    @ObservationIgnored private var throttle = SampleThrottle()
    @ObservationIgnored private var rawX: [Int] = []
    @ObservationIgnored private var rawY: [Int] = []
    @ObservationIgnored private var rawZ: [Int] = []
    @ObservationIgnored private var rawStartedAt: Date?
    @ObservationIgnored private var rawFirstNanos: Int64?
    @ObservationIgnored private var rawLastNanos: Int64?
    // A wedged accelerometer keeps delivering the identical vector; a still
    // phone still jitters in the low bits, so only a stuck sensor repeats
    // all three axes exactly for seconds.
    @ObservationIgnored private var frozenSamples = 0
    @ObservationIgnored private var lastRaw: (Double, Double, Double)?

    @ObservationIgnored private var inboxListener: UUID?
    @ObservationIgnored private let fileQueue = DispatchQueue(label: "com.juggling.tracker.recordings")

    init(settings: AppSettings, sessionStore: SessionStore?, recordingStore: RecordingStore?, inbox: WatchInbox? = nil) {
        self.settings = settings
        self.sessionStore = sessionStore
        self.recordingStore = recordingStore
        self.inbox = inbox ?? WatchInbox(sessions: sessionStore, recordings: recordingStore) { [settings] in
            settings.currentJuggler
        }
        sessions = sessionStore?.sessions ?? []
        inboxListener = self.inbox.addListener { [weak self] event in
            MainActor.assumeIsolated { self?.handle(event) }
        }
        refreshRecordings()
    }

    /// True while the accelerometer has to keep streaming, so the screen holds
    /// the display awake for exactly that long: nobody touches the phone while
    /// juggling, and a locked phone stops delivering samples.
    var shouldKeepScreenOn: Bool {
        phoneSession.isRecording || rawRecording.step == .recording
    }

    // MARK: - Watch links

    func onGarminLinkStatus(_ status: GarminConnectionStatus, message: String) {
        garminMessage = message
        if garminReceiving != nil { garminWhileReceiving = status } else { garminStatus = status }
    }

    func onAppleWatchLinkStatus(_ status: AppleWatchConnectionStatus) {
        if appleWatchReceiving != nil { appleWatchWhileReceiving = status } else { appleWatchStatus = status }
    }

    private func handle(_ event: WatchInbox.Event) {
        switch event {
        case let .receiving(source, more):
            showReceiving(source, more: more)
        case let .sessionStored(session):
            showStored(session)
            let text = "Received \(session.runCount) runs for \(session.ballCount) balls"
            toast = text
            speak(text)
        case .recordingStored:
            refreshRecordings()
        }
    }

    private func showReceiving(_ source: WatchInbox.Source, more: Bool) {
        let hold = (more ? Self.receivingStallMs : Self.receivingDisplayMs) * 1_000_000
        switch source {
        case .garmin:
            garminStatus = .receiving
            garminReceiving?.cancel()
            garminReceiving = Task { [weak self] in
                try? await Task.sleep(nanoseconds: hold)
                guard !Task.isCancelled, let self else { return }
                self.garminReceiving = nil
                self.garminStatus = self.garminWhileReceiving ?? .ready
                self.garminWhileReceiving = nil
            }
        case .appleWatch:
            appleWatchStatus = .receiving
            appleWatchReceiving?.cancel()
            appleWatchReceiving = Task { [weak self] in
                try? await Task.sleep(nanoseconds: hold)
                guard !Task.isCancelled, let self else { return }
                self.appleWatchReceiving = nil
                self.appleWatchStatus = self.appleWatchWhileReceiving ?? .ready
                self.appleWatchWhileReceiving = nil
            }
        }
    }

    // MARK: - History

    private func showStored(_ session: SessionSummary) {
        if let sessionStore {
            sessions = sessionStore.sessions
            return
        }
        // No store (tests): keep the list the way the store would.
        if let existing = sessions.firstIndex(where: { $0.timestamp == session.timestamp }) {
            sessions[existing] = session
        } else {
            sessions.insert(session, at: 0)
        }
    }

    func delete(_ session: SessionSummary) {
        sessions.removeAll { $0 == session }
        sessionStore?.delete(session)
    }

    var sessionsCSV: String { SessionCSV.write(sessions) }

    /// Adds a backup's sessions, skipping those already here. Returns how many were added.
    func restore(_ backup: [SessionSummary]) -> Int {
        if let sessionStore {
            let added = sessionStore.restore(backup)
            sessions = sessionStore.sessions
            return added
        }
        let added = SessionCSV.newSessions(backup, existing: sessions)
        sessions = (sessions + added).sorted { $0.timestamp > $1.timestamp }
        return added.count
    }

    // MARK: - Settings

    func setAnalyticsEnabled(_ enabled: Bool) {
        settings.isAnalyticsEnabled = enabled
        Telemetry.shared.setEnabled(enabled)
    }

    /// Runs saved before the juggler was stored with each run.
    var recordingsWithoutJuggler: Int { recordings.filter { $0.juggler == nil }.count }

    // MARK: - Recordings

    func refreshRecordings() {
        guard let recordingStore else { return }
        fileQueue.async { [weak self] in
            let list = recordingStore.listRecordings()
            Task { @MainActor in
                self?.recordings = list
                self?.recordingCount = list.count
            }
        }
    }

    /// Every recording as a zip, each run keeping its own juggler and the rest
    /// tagged with the current one.
    func recordingsZip() async -> Data {
        guard let recordingStore else { return Data() }
        let fallback = settings.currentJuggler
        return await withCheckedContinuation { continuation in
            fileQueue.async { continuation.resume(returning: recordingStore.exportZip(fallback: fallback)) }
        }
    }

    func clearRecordings() {
        recordings = []
        recordingCount = 0
        guard let recordingStore else { return }
        fileQueue.async { recordingStore.clearAll() }
    }

    // MARK: - Raw recording

    func startRawRecordingFlow() {
        rawRecording = RawRecordingState(step: .selectBalls)
    }

    func confirmRawRecordingBalls(_ balls: Int) {
        rawRecording = RawRecordingState(step: .recording, selectedBallCount: balls)
        rawX = []
        rawY = []
        rawZ = []
        rawFirstNanos = nil
        rawLastNanos = nil
        rawStartedAt = Date()
        // The detector runs during capture so the screen can show a live count.
        detector = JugglingDetector(ballCount: balls)
        firstSampleMs = nil
        throttle.reset()
        phoneSession.currentCount = 0
    }

    func stopRawRecording() {
        guard rawRecording.step == .recording else { return }
        rawRecording.step = .enterCatches
    }

    func saveRawRecording(actualCatches: Int) {
        let timestamp = Int64((rawStartedAt ?? Date()).timeIntervalSince1970)
        let detected = detector?.currentCount ?? 0
        let balls = rawRecording.selectedBallCount
        let juggler = settings.currentJuggler
        let rate = Self.measuredSampleRate(count: rawX.count, firstNanos: rawFirstNanos, lastNanos: rawLastNanos)
        let (xs, ys, zs) = (rawX, rawY, rawZ)
        if let recordingStore {
            fileQueue.async {
                recordingStore.saveRecording(
                    balls: balls, catches: actualCatches, detected: detected, sampleRate: rate,
                    timestamp: timestamp, x: xs, y: ys, z: zs,
                    source: RecordingStore.sourcePhone, juggler: juggler
                )
            }
            refreshRecordings()
        }
        cancelRawRecording()
    }

    func cancelRawRecording() {
        rawRecording = RawRecordingState()
        rawX = []
        rawY = []
        rawZ = []
        rawFirstNanos = nil
        rawLastNanos = nil
        if !phoneSession.isRecording {
            detector = nil
            phoneSession.currentCount = 0
        }
    }

    // MARK: - Phone session

    func selectPhoneBallCount(_ count: Int) {
        guard !phoneSession.isRecording else { return }
        phoneSession.selectedBallCount = min(max(count, 3), 9)
    }

    func startPhoneSession(ballCount: Int, at start: Date = Date()) {
        let balls = min(max(ballCount, 3), 9)
        detector = JugglingDetector(ballCount: balls)
        phoneSessionStartedAt = start
        firstSampleMs = nil
        throttle.reset()
        resetFrozenTracking()
        phoneSession = PhoneSessionState(
            selectedBallCount: balls,
            isRecording: true,
            statusMessage: "Mount the phone to your wrist and start juggling"
        )
        Telemetry.shared.log("start_phone_session", ["ball_count": balls])
    }

    /// Takes a batch of raw accelerometer samples and updates the screen once per batch.
    func processPhoneSamples(_ samples: [PhoneAccelSample]) {
        guard let first = samples.first, let last = samples.last else { return }
        if rawRecording.step == .recording {
            for s in samples {
                rawX.append(Self.milliG(s.ax))
                rawY.append(Self.milliG(s.ay))
                rawZ.append(Self.milliG(s.az))
            }
            if rawFirstNanos == nil { rawFirstNanos = first.timestampNanos }
            rawLastNanos = last.timestampNanos
            rawRecording.sampleCount = rawX.count
        }

        var lastProcessedMs: Int64?
        for s in samples {
            guard let detector, phoneSession.isRecording || rawRecording.step == .recording else { break }
            let sampleMs = s.timestampNanos / 1_000_000
            guard throttle.accept(sampleMs) else { continue }
            if firstSampleMs == nil { firstSampleMs = sampleMs }

            if phoneSession.isRecording && isSensorFrozen(s) {
                markPhoneSensorUnavailable("Accelerometer is not responding. Restart the phone and try again.")
                return
            }

            let oldCount = detector.currentCount
            detector.processSampleMs2(s.ax, s.ay, s.az, nowMs: sampleMs)
            let newCount = detector.currentCount
            detector.checkAutoFinish(nowMs: sampleMs)
            if newCount > oldCount { announce(newCount) }
            lastProcessedMs = sampleMs
        }
        if let lastProcessedMs { updateFromDetector(lastProcessedMs) }
    }

    private static func milliG(_ ms2: Double) -> Int {
        Int(ms2 * 1000.0 / 9.80665)
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

    private func announce(_ count: Int) {
        guard settings.isVoiceEnabled, settings.voiceInterval > 0, count % settings.voiceInterval == 0 else { return }
        speak(String(count))
    }

    /// Ends the phone session and stores it. Returns false when there was no run to save.
    @discardableResult
    func stopPhoneSessionAndSave(at stop: Date = Date()) -> Bool {
        guard let detector else { return false }
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
            resetPhoneSession("No phone runs to save")
            phoneSession.sensorError = "No phone runs to save"
            return false
        }
        let started = phoneSessionStartedAt ?? stop
        let timestamp = Int64((started.timeIntervalSince1970 * 1000).rounded(.down))
        let session = SessionSummary.summarize(
            timestamp: timestamp,
            ballCount: balls,
            runs: runs,
            durationSeconds: max(Int64(stop.timeIntervalSince(started)), 0),
            runDurationsMillis: detector.runDurationsMillis
        )
        sessionStore?.store(session)
        showStored(session)
        resetPhoneSession("Phone session saved")
        let text = "Saved \(runs.count) phone runs for \(balls) balls"
        toast = text
        speak(text)
        return true
    }

    func cancelPhoneSession() {
        resetPhoneSession("Phone session cancelled")
    }

    func markPhoneSensorUnavailable(_ message: String) {
        resetPhoneSession(message)
        phoneSession.sensorError = message
    }

    private func updateFromDetector(_ sampleMs: Int64) {
        guard let detector else { return }
        let elapsed = max((sampleMs - (firstSampleMs ?? sampleMs)) / 1000, 0)
        let status: String
        if detector.currentCount > 0 || detector.isRunActive {
            status = "Run active"
        } else if detector.sessionRuns > 0 {
            status = "Waiting for next run"
        } else {
            status = "Mount the phone to your wrist and start juggling"
        }
        phoneSession.currentCount = detector.currentCount
        phoneSession.previousCount = detector.previousCount
        phoneSession.completedRuns = detector.runCatches
        phoneSession.runDurationsMillis = detector.runDurationsMillis
        phoneSession.sessionRunCount = detector.sessionRuns
        phoneSession.sessionAverage = detector.sessionAverage
        phoneSession.sessionMax = detector.sessionMax
        phoneSession.elapsedSeconds = elapsed
        if phoneSession.isRecording {
            phoneSession.statusMessage = status
            phoneSession.sensorError = nil
        }
    }

    private func resetPhoneSession(_ status: String = "Ready to record with phone") {
        let balls = phoneSession.selectedBallCount
        detector = nil
        phoneSessionStartedAt = nil
        firstSampleMs = nil
        throttle.reset()
        resetFrozenTracking()
        phoneSession = PhoneSessionState(selectedBallCount: balls, statusMessage: status)
    }

    /// The whole-Hz rate `count` samples arrived at between the first and
    /// last sensor timestamps, or the requested rate when that cannot be measured.
    nonisolated static func measuredSampleRate(count: Int, firstNanos: Int64?, lastNanos: Int64?) -> Int {
        guard count >= 2, let firstNanos, let lastNanos, lastNanos > firstNanos else {
            return PhoneAccelerometer.requestedSampleRate
        }
        let rate = Double(count - 1) * 1_000_000_000.0 / Double(lastNanos - firstNanos)
        return max(Int(rate.rounded()), 1)
    }
}
