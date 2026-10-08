import JugglingCore
import XCTest
@testable import WatchLogic

/// Record mode, `RecordingView.mc` and its delegates, the same cases as the
/// Wear OS `RecordingSessionTest`. Each test names the requirement in
/// `connectiq/REQUIREMENTS.md` it secures.
final class RecordingSessionTests: XCTestCase {
    private let scheduler = FakeScheduler()
    private let link = FakePhoneLink()
    private let effects = FakeEffects()
    private var logLines: [String] = []
    private var epoch: Int64 = 1_700_000_000
    private lazy var session = RecordingSession(
        ballCount: 3, link: link, scheduler: scheduler, effects: effects,
        epochSeconds: { [unowned self] in self.epoch },
        log: { [unowned self] in self.logLines.append($0) }
    )
    private var t: Int64 = 0

    private var state: RecordingUiState { session.state }

    private func feed(_ samples: [AccelSample]) {
        for batch in samples.chunked(25) { session.onSamples(batch) }
        t = Feeds.next(samples)
    }

    /// Records a run of warmup + `catches` and stops it, landing on the label screen.
    private func recordRun(_ catches: Int = 3) {
        session.onStart()
        feed(Feeds.warmup())
        feed(Feeds.catches(t, catches))
        session.onStart()
    }

    /// Lets every part of the transfer through until it waits for the ack.
    private func deliverAllParts() {
        link.autoResult = true
        link.complete(true) // the part already in flight
        scheduler.advance(RecordingSession.nextPartDelayMs * 200)
    }

    private var chunks: [[String: Any]] { link.sent.filter { $0["type"] as? String == "rec_chunk" } }

    // MARK: REC-1

    func testREC1StartDrivesIdleRecordingLabelingSyncingAndBackToIdle() {
        XCTAssertEqual(state.phase, .idle)
        session.onStart()
        XCTAssertEqual(state.phase, .recording)
        feed(Feeds.warmup())
        session.onStart()
        XCTAssertEqual(state.phase, .labeling)
        session.onStart()
        XCTAssertEqual(state.phase, .syncing)
        deliverAllParts()
        link.ack(epoch)
        XCTAssertEqual(state.phase, .idle)
        XCTAssertEqual(state.runsCompleted, 1)
    }

    func testREC1SamplesAreOnlyKeptWhileRecording() {
        feed(Feeds.baseline(0, 30))
        XCTAssertEqual(state.recordedSamples, 0)
        session.onStart()
        feed(Feeds.baseline(t, 30))
        XCTAssertEqual(state.recordedSamples, 30)
    }

    func testREC1BackWhileLabelingOffersToDiscard() {
        recordRun()
        session.onBack()
        XCTAssertEqual(state.menu?.title, "Discard run?")
        XCTAssertEqual(state.menu?.items.map(\.label), ["Yes", "Continue"])
        session.onMenuSelect(RecordingSession.itemDiscardConfirm)
        XCTAssertEqual(state.phase, .idle)
        XCTAssertEqual(state.runsCompleted, 0)
        XCTAssertTrue(link.sent.isEmpty)
    }

    func testREC1BackWhileRecordingOffersToQuit() {
        session.onStart()
        session.onBack()
        XCTAssertEqual(state.menu?.title, "Quit? Lose run")
        session.onMenuSelect(RecordingSession.itemQuitContinue)
        XCTAssertEqual(effects.exits, 0)
        XCTAssertEqual(state.phase, .recording)

        session.onBack()
        XCTAssertEqual(state.menu?.title, "Quit? Lose run")
        session.onMenuSelect(RecordingSession.itemQuitConfirm)
        XCTAssertEqual(effects.exits, 1)
    }

    // MARK: REC-2

    func testREC2ARunStopsByItselfAt3000Samples() {
        session.onStart()
        feed(Feeds.baseline(0, RecordingSession.maxRunSamples + 10))
        XCTAssertEqual(state.phase, .labeling)
        XCTAssertEqual(state.recordedSamples, RecordingSession.maxRunSamples)
    }

    // MARK: REC-3

    func testREC3TheDetectedCountIsTheStartingLabelAndItNeverGoesBelowZero() {
        recordRun(4)
        XCTAssertEqual(state.detectedCount, 4)
        XCTAssertEqual(state.labelCount, 4)
        session.onUp()
        XCTAssertEqual(state.labelCount, 5)
        for _ in 0..<8 { session.onDown() }
        XCTAssertEqual(state.labelCount, 0)
    }

    // MARK: REC-4

    func testREC4EachConfirmedRunLogsAOneLineSummaryWithoutTheSamples() {
        recordRun(3)
        let samples = state.recordedSamples
        session.onUp()
        session.onStart()
        XCTAssertEqual(
            logLines,
            ["RUN_DATA,balls=3,catches=4,detected=3,rate=25,countMode=watch_hand,samples=\(samples)"]
        )
    }

    // MARK: REC-5

    func testREC5RunsTransferAsRecStartOneChunkPerChunkSamplesThenRecEnd() {
        recordRun(3)
        let samples = state.recordedSamples
        session.onStart()
        deliverAllParts()

        let chunkCount = (samples + RecordingSession.chunkSamples - 1) / RecordingSession.chunkSamples
        XCTAssertEqual(link.types, ["rec_start"] + Array(repeating: "rec_chunk", count: chunkCount) + ["rec_end"])

        let start = link.sent[0]
        XCTAssertEqual(start["id"] as? Int64, epoch)
        XCTAssertEqual(start["timestamp"] as? Int64, epoch)
        XCTAssertEqual(start["balls"] as? Int, 3)
        XCTAssertEqual(start["catches"] as? Int, 3)
        XCTAssertEqual(start["detected"] as? Int, 3)
        XCTAssertEqual(start["sampleRate"] as? Int, 25)
        XCTAssertEqual(start["samples"] as? Int, samples)
        XCTAssertEqual(start["chunks"] as? Int, chunkCount)
        XCTAssertEqual(start["countMode"] as? String, "watch_hand")

        XCTAssertEqual(chunks.map { ($0["x"] as? [Int])?.count ?? 0 }.reduce(0, +), samples)
        XCTAssertEqual(chunks.map { $0["i"] as? Int ?? -1 }, Array(0..<chunkCount))

        // Only rec_end is acknowledged: until then the run is still syncing.
        XCTAssertEqual(state.phase, .syncing)
        XCTAssertTrue(state.transferDone)
        link.ack(epoch)
        XCTAssertEqual(state.phase, .idle)
    }

    func testREC5AFullLengthRunGoesOverInThreeChunksThatFitAWatchConnectivityMessage() {
        session.onStart()
        // Six-digit readings on every axis, well past what the sensor reports,
        // so this bounds the largest chunk the watch can ever send.
        let loud = (0..<RecordingSession.maxRunSamples).map {
            AccelSample(x: -99_999, y: -99_999, z: -99_999, timeMs: Int64($0) * Feeds.periodMs)
        }
        feed(loud)
        session.onStart() // label screen
        session.onStart() // confirm
        deliverAllParts()

        XCTAssertEqual(chunks.count, 3)
        XCTAssertEqual(link.sent[0]["chunks"] as? Int, 3)
        // WatchConnectivity's sendMessage takes a payload of up to about
        // 65 KB; keep well under that.
        let largest = chunks.map { WatchProtocol.encode($0).count }.max() ?? 0
        XCTAssertLessThan(largest, 50_000, "largest chunk is \(largest) bytes")
    }

    // MARK: REC-6

    func testREC6ALateFailureFromASkippedRunDoesNotFailTheNextOne() {
        recordRun(3)
        session.onStart() // sends rec_start, which never reports
        scheduler.advance(RecordingSession.syncTimeoutMs)
        XCTAssertEqual(state.menu?.title, "no reply from phone")
        session.onMenuSelect(RecordingSession.itemSyncSkip)
        XCTAssertEqual(state.phase, .idle)

        epoch += 100
        recordRun(3)
        session.onStart()
        link.complete(false, index: 0) // the skipped run's transmit fails late
        XCTAssertEqual(state.phase, .syncing)
        XCTAssertNil(state.menu)
    }

    func testASendFailureOffersRetrySkipOrQuitAndRetryRestartsFromTheHeader() {
        recordRun(3)
        session.onStart()
        link.complete(false)
        XCTAssertEqual(state.menu?.title, "send failed")
        XCTAssertEqual(state.menu?.items.map(\.label), ["Retry sync", "Skip (lose data)", "Quit"])
        XCTAssertEqual(state.errorMessage, "Sync failed")

        session.onMenuSelect(RecordingSession.itemSyncRetry)
        scheduler.advance(RecordingSession.nextPartDelayMs)
        XCTAssertEqual(link.types, ["rec_start", "rec_start"])
    }

    // MARK: REC-7

    func testREC7TheNextPartIsSentFromATimerNotFromTheDeliveryCallback() {
        recordRun(3)
        session.onStart()
        XCTAssertEqual(link.sent.count, 1)
        link.complete(true)
        XCTAssertEqual(link.sent.count, 1, "nothing is sent from inside the callback")
        scheduler.advance(RecordingSession.nextPartDelayMs)
        XCTAssertEqual(link.types, ["rec_start", "rec_chunk"])
    }

    // MARK: REC-8

    func testREC8AStaleAckForAnotherRunIsRejected() {
        recordRun(3)
        session.onStart()
        deliverAllParts()
        link.ack(epoch - 5)
        XCTAssertEqual(state.phase, .syncing)
        XCTAssertEqual(state.runsCompleted, 0)
        link.ack(epoch)
        XCTAssertEqual(state.phase, .idle)
    }

    // MARK: REC-9

    func testREC9BackIsConsumedWhileSyncing() {
        recordRun(3)
        session.onStart()
        session.onBack()
        XCTAssertNil(state.menu)
        XCTAssertEqual(state.phase, .syncing)
        XCTAssertEqual(effects.exits, 0)
    }

    // MARK: REC-10

    func testREC10BackingOutOfTheSyncFailureMenuRetries() {
        recordRun(3)
        session.onStart()
        link.complete(false)
        session.onMenuBack()
        XCTAssertNil(state.menu)
        scheduler.advance(RecordingSession.nextPartDelayMs)
        XCTAssertEqual(link.sent.count, 2)
    }

    func testREC10BackingOutOfTheQuitOrDiscardPromptMeansContinue() {
        session.onStart()
        session.onBack()
        session.onMenuBack()
        XCTAssertNil(state.menu)
        XCTAssertEqual(effects.exits, 0)
        XCTAssertEqual(state.phase, .recording)

        feed(Feeds.warmup())
        session.onStart()
        session.onBack()
        session.onMenuBack()
        XCTAssertEqual(state.phase, .labeling)
        session.onStart()
        XCTAssertEqual(state.phase, .syncing)
    }

    // MARK: REC-11

    func testREC11BackOnTheIdleScreenLeavesWithoutAPrompt() {
        XCTAssertTrue(session.onBack())
        XCTAssertNil(state.menu)
        XCTAssertEqual(effects.exits, 0)
        // Closed: it ignores any input that still arrives.
        XCTAssertFalse(session.onStart())
        XCTAssertEqual(state.phase, .idle)
    }

    func testREC11BackAfterASyncedRunStillLeaves() {
        recordRun(3)
        session.onStart()
        link.ack(epoch)
        XCTAssertEqual(state.phase, .idle)
        XCTAssertTrue(session.onBack())
        XCTAssertEqual(effects.exits, 0)
    }

    func testSyncProgressShowsChunksWhileTheyGoOver() {
        recordRun(3)
        session.onStart()
        XCTAssertEqual(Format.recordingSyncText(state), "Sync to phone.")
        link.complete(true)
        scheduler.advance(RecordingSession.nextPartDelayMs)
        XCTAssertEqual(Format.recordingSyncText(state), "0/\(state.totalChunks)")
    }
}
