import Foundation
import JugglingCore
import Observation

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
    /// What the detector counts during capture, shown live.
    var liveCount = 0
}

/// Raw accelerometer recordings for the detection corpus: the record flow
/// (balls, capture, typed catch count) and the stored recordings, whether
/// captured here or sent by a watch. Counterpart of the Android app's
/// `RawRecordingModel`.
@MainActor
@Observable
final class RawRecordingModel {
    @ObservationIgnored let recordingStore: RecordingStore?
    /// Who juggles now; new recordings and the export are tagged with it.
    @ObservationIgnored private let currentJuggler: () -> Juggler?
    /// Called with the new count whenever a catch is counted.
    @ObservationIgnored var onCatch: (Int) -> Void = { _ in }

    private(set) var state = RawRecordingState()
    private(set) var recordings: [RecordingStore.Summary] = []
    private(set) var recordingCount = 0

    @ObservationIgnored private var rawX: [Int] = []
    @ObservationIgnored private var rawY: [Int] = []
    @ObservationIgnored private var rawZ: [Int] = []
    @ObservationIgnored private var startedAt: Date?
    @ObservationIgnored private var firstNanos: Int64?
    @ObservationIgnored private var lastNanos: Int64?
    // The detector runs during capture so the screen can show a live count.
    @ObservationIgnored private var detector: JugglingDetector?
    @ObservationIgnored private var throttle = SampleThrottle()
    @ObservationIgnored private let fileQueue = DispatchQueue(label: "com.juggling.tracker.recordings")

    init(recordingStore: RecordingStore?, currentJuggler: @escaping () -> Juggler?) {
        self.recordingStore = recordingStore
        self.currentJuggler = currentJuggler
        refresh()
    }

    /// Runs saved before the juggler was stored with each run.
    var recordingsWithoutJuggler: Int { recordings.filter { $0.juggler == nil }.count }

    func refresh() {
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
    func zip() async -> Data {
        guard let recordingStore else { return Data() }
        let fallback = currentJuggler()
        return await withCheckedContinuation { continuation in
            fileQueue.async { continuation.resume(returning: recordingStore.exportZip(fallback: fallback)) }
        }
    }

    func clear() {
        recordings = []
        recordingCount = 0
        guard let recordingStore else { return }
        fileQueue.async { recordingStore.clearAll() }
    }

    // MARK: - The record flow

    func startFlow() {
        state = RawRecordingState(step: .selectBalls)
    }

    func confirmBalls(_ balls: Int) {
        state = RawRecordingState(step: .recording, selectedBallCount: balls)
        clearBuffers()
        startedAt = Date()
        detector = JugglingDetector(ballCount: balls)
        throttle.reset()
    }

    func stop() {
        guard state.step == .recording else { return }
        state.step = .enterCatches
    }

    /// Takes a batch of raw accelerometer samples while capturing.
    func process(_ samples: [PhoneAccelSample]) {
        guard let first = samples.first, let last = samples.last, state.step == .recording else { return }
        for s in samples {
            rawX.append(Self.milliG(s.ax))
            rawY.append(Self.milliG(s.ay))
            rawZ.append(Self.milliG(s.az))
        }
        if firstNanos == nil { firstNanos = first.timestampNanos }
        lastNanos = last.timestampNanos

        if let detector {
            for s in samples {
                let sampleMs = s.timestampNanos / 1_000_000
                guard throttle.accept(sampleMs) else { continue }
                let oldCount = detector.currentCount
                detector.processSampleMs2(s.ax, s.ay, s.az, nowMs: sampleMs)
                let newCount = detector.currentCount
                detector.checkAutoFinish(nowMs: sampleMs)
                if newCount > oldCount { onCatch(newCount) }
            }
        }
        state.sampleCount = rawX.count
        state.liveCount = detector?.currentCount ?? 0
    }

    func save(actualCatches: Int) {
        let timestamp = Int64((startedAt ?? Date()).timeIntervalSince1970)
        let detected = detector?.currentCount ?? 0
        let balls = state.selectedBallCount
        let juggler = currentJuggler()
        let rate = Self.measuredSampleRate(count: rawX.count, firstNanos: firstNanos, lastNanos: lastNanos)
        let (xs, ys, zs) = (rawX, rawY, rawZ)
        if let recordingStore {
            fileQueue.async {
                recordingStore.saveRecording(
                    balls: balls, catches: actualCatches, detected: detected, sampleRate: rate,
                    timestamp: timestamp, x: xs, y: ys, z: zs,
                    source: RecordingStore.sourcePhone, juggler: juggler
                )
            }
            refresh()
        }
        cancel()
    }

    func cancel() {
        state = RawRecordingState()
        clearBuffers()
        detector = nil
    }

    private func clearBuffers() {
        rawX = []
        rawY = []
        rawZ = []
        firstNanos = nil
        lastNanos = nil
    }

    private static func milliG(_ ms2: Double) -> Int {
        Int(ms2 * 1000.0 / 9.80665)
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
