import JugglingCore
import XCTest
@testable import JugglingTracker

@MainActor
final class TrackerModelTests: XCTestCase {
    private var model: TrackerModel!
    private var store: SessionStore!
    private var spoken: [String] = []

    override func setUp() async throws {
        let dir = makeTempDirectory(self)
        let defaults = UserDefaults(suiteName: "TrackerModelTests-\(UUID().uuidString)")!
        store = SessionStore(fileURL: dir.appendingPathComponent("sessions.jsonl"))
        model = TrackerModel(
            settings: AppSettings(defaults: defaults),
            sessionStore: store,
            recordingStore: RecordingStore(directory: dir.appendingPathComponent("recordings"))
        )
        spoken = []
        model.speak = { [unowned self] in self.spoken.append($0) }
    }

    /// A recorded watch run as the iPhone's accelerometer would deliver it:
    /// in g with CoreMotion's sign, at 100 Hz (each 25 Hz sample held for
    /// four readings), in batches of ten.
    private func phoneBatches(_ runId: String) -> [[PhoneAccelSample]] {
        var samples: [PhoneAccelSample] = []
        for (x, y, z) in loadRun(runId) {
            for _ in 0..<4 {
                var sample = PhoneAccelSample.fromCoreMotion(
                    x: -Double(x) / 1000, y: -Double(y) / 1000, z: -Double(z) / 1000, timestamp: 0
                )
                // Exact 10 ms steps from t = 1000 s, so every fourth reading
                // lands on the reference's 40 ms grid.
                sample.timestampNanos = 1_000_000_000_000 + Int64(samples.count) * 10_000_000
                samples.append(sample)
            }
        }
        return stride(from: 0, to: samples.count, by: 10).map { Array(samples[$0..<min($0 + 10, samples.count)]) }
    }

    /// The same run fed straight into the detector at 25 Hz.
    private func reference(_ runId: String, balls: Int) -> [Int] {
        let k = JugglingDetector.milliGToMs2
        let detector = JugglingDetector(ballCount: balls)
        var t: Int64 = 1_000_000
        for s in loadRun(runId) {
            detector.processSampleMs2(Double(s.0) * k, Double(s.1) * k, Double(s.2) * k, nowMs: t)
            detector.checkAutoFinish(nowMs: t)
            t += JugglingDetector.samplePeriodMs
        }
        detector.finishCurrentRun()
        return detector.runCatches
    }

    func testAPhoneSessionCountsAsTheDetectorDoesAndIsStored() {
        let start = Date(timeIntervalSince1970: 1_790_000_000)
        model.startPhoneSession(ballCount: 3, at: start)
        for batch in phoneBatches("20260922_144802") { model.processPhoneSamples(batch) }
        XCTAssertTrue(model.stopPhoneSessionAndSave(at: start.addingTimeInterval(90)))

        let expected = reference("20260922_144802", balls: 3)
        XCTAssertFalse(expected.isEmpty)
        let saved = store.sessions.first
        XCTAssertEqual(saved?.runHistory, expected)
        XCTAssertEqual(saved?.timestamp, 1_790_000_000_000)
        XCTAssertEqual(saved?.durationSeconds, 90)
        XCTAssertEqual(model.sessions, store.sessions)
        XCTAssertFalse(model.phoneSession.isRecording)
        XCTAssertEqual(model.toast, "Saved \(expected.count) phone runs for 3 balls")
    }

    func testVoiceAnnouncesEveryIntervalOfCatches() {
        model.settings.voiceInterval = 10
        model.startPhoneSession(ballCount: 3)
        for batch in phoneBatches("20260922_144802") { model.processPhoneSamples(batch) }
        XCTAssertFalse(spoken.isEmpty)
        XCTAssertEqual(spoken.filter { (Int($0) ?? 1) % 10 != 0 }, [])
    }

    func testVoiceOffSaysNothingWhileJuggling() {
        model.settings.isVoiceEnabled = false
        model.startPhoneSession(ballCount: 3)
        for batch in phoneBatches("20260922_144802") { model.processPhoneSamples(batch) }
        XCTAssertTrue(spoken.isEmpty)
    }

    func testAFrozenAccelerometerEndsTheSessionWithAnError() {
        model.startPhoneSession(ballCount: 3)
        let stuck = (0..<700).map { i in
            PhoneAccelSample(ax: 0.1, ay: 0.2, az: 9.8, timestampNanos: Int64(i) * 10_000_000)
        }
        model.processPhoneSamples(stuck)
        XCTAssertFalse(model.phoneSession.isRecording)
        XCTAssertEqual(model.phoneSession.sensorError, "Accelerometer is not responding. Restart the phone and try again.")
    }

    func testStoppingWithoutAnyRunSavesNothing() {
        model.startPhoneSession(ballCount: 3)
        XCTAssertFalse(model.stopPhoneSessionAndSave())
        XCTAssertTrue(store.sessions.isEmpty)
        XCTAssertEqual(model.phoneSession.sensorError, "No phone runs to save")
    }

    func testTheScreenStaysOnExactlyWhileSampling() {
        XCTAssertFalse(model.shouldKeepScreenOn)
        model.startPhoneSession(ballCount: 3)
        XCTAssertTrue(model.shouldKeepScreenOn)
        model.cancelPhoneSession()
        XCTAssertFalse(model.shouldKeepScreenOn)
        model.startRawRecordingFlow()
        XCTAssertFalse(model.shouldKeepScreenOn)
        model.confirmRawRecordingBalls(5)
        XCTAssertTrue(model.shouldKeepScreenOn)
        model.stopRawRecording()
        XCTAssertFalse(model.shouldKeepScreenOn)
    }

    func testARawRecordingIsSavedInMilliGWithItsMeasuredRate() async throws {
        model.startRawRecordingFlow()
        model.confirmRawRecordingBalls(4)
        // One second at 100 Hz, phone lying face up.
        let samples = (0..<101).map { i in
            PhoneAccelSample.fromCoreMotion(x: 0, y: 0, z: -1, timestamp: 10 + Double(i) * 0.01)
        }
        model.processPhoneSamples(samples)
        XCTAssertEqual(model.rawRecording.sampleCount, 101)
        model.stopRawRecording()
        XCTAssertEqual(model.rawRecording.step, .enterCatches)
        model.saveRawRecording(actualCatches: 0)
        XCTAssertEqual(model.rawRecording.step, .idle)

        let recordings = try XCTUnwrap(model.recordingStore)
        // The save runs off the main thread; wait for it.
        for _ in 0..<50 where recordings.recordingCount == 0 {
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        let saved = try XCTUnwrap(recordings.listRecordings().first)
        XCTAssertEqual(saved.balls, 4)
        XCTAssertEqual(saved.sampleRate, 100)
        XCTAssertEqual(saved.samples, 101)
        XCTAssertEqual(saved.source, RecordingStore.sourcePhone)
        let text = try String(contentsOf: recordings.directory.appendingPathComponent(saved.fileName), encoding: .utf8)
        // Face up reads +1 g on z, as on Android (truncated to whole milli-g).
        let firstRow = text.components(separatedBy: "\n")[2]
        XCTAssertTrue(["0,0,1000", "0,0,999"].contains(firstRow), firstRow)
    }

    func testMeasuredSampleRateFallsBackToTheRequestedRate() {
        XCTAssertEqual(TrackerModel.measuredSampleRate(count: 1, firstNanos: 0, lastNanos: 0), 100)
        XCTAssertEqual(TrackerModel.measuredSampleRate(count: 51, firstNanos: 0, lastNanos: 1_000_000_000), 50)
    }

    func testAWatchSessionShowsUpAndIsAnnounced() {
        model.inbox.receive(
            ["type": "session", "balls": 5, "timestamp": 1_790_000_000, "runs": [10, 20], "shapeConsistency": 77],
            from: .appleWatch
        )
        XCTAssertEqual(model.sessions.first?.shapeConsistency, 77)
        XCTAssertEqual(model.appleWatchStatus, .receiving)
        XCTAssertEqual(spoken, ["Received 2 runs for 5 balls"])
    }

    func testALinkStatusWaitsUntilTheTransferEnds() {
        model.inbox.receive(["type": "rec_start", "id": 1, "balls": 3, "catches": 1, "samples": 0, "chunks": 0], from: .garmin)
        model.onGarminLinkStatus(.disconnected, message: "")
        XCTAssertEqual(model.garminStatus, .receiving)
    }

    func testRestoreAddsABackupsNewSessions() throws {
        model.inbox.receive(["type": "session", "balls": 3, "timestamp": 1_790_000_000, "runs": [10]], from: .garmin)
        let backup = try SessionCSV.parse(model.sessionsCSV).sessions + [
            SessionSummary.summarize(timestamp: 1_700_000_000_000, ballCount: 4, runs: [3]),
        ]
        XCTAssertEqual(model.restore(backup), 1)
        XCTAssertEqual(model.sessions.map(\.timestamp), [1_790_000_000_000, 1_700_000_000_000])
    }

    func testAppleWatchStatusClassification() {
        XCTAssertEqual(AppleWatchConnectionStatus.classify(paired: nil, appInstalled: false), .unavailable)
        XCTAssertEqual(AppleWatchConnectionStatus.classify(paired: false, appInstalled: false), .noWatch)
        XCTAssertEqual(AppleWatchConnectionStatus.classify(paired: true, appInstalled: false), .watchAppMissing)
        XCTAssertEqual(AppleWatchConnectionStatus.classify(paired: true, appInstalled: true), .ready)
    }
}
