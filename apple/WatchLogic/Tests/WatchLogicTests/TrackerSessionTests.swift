import JugglingCore
import XCTest
@testable import WatchLogic

/// Juggle mode, `MainView.mc` and its delegates, the same cases as the Wear OS
/// `TrackerSessionTest`. Each test names the requirement in
/// `connectiq/REQUIREMENTS.md` it secures.
final class TrackerSessionTests: XCTestCase {
    private let scheduler = FakeScheduler()
    private let link = FakePhoneLink()
    private let effects = FakeEffects()
    private var epochNow: Int64 = 1_700_000_000
    private lazy var session = TrackerSession(
        ballCount: 3, link: link, scheduler: scheduler, clock: scheduler, effects: effects,
        epochSeconds: { [unowned self] in self.epochNow }
    )
    private var t: Int64 = 0

    private var state: TrackerUiState { session.state }

    private func feed(_ samples: [AccelSample]) {
        // The sensor delivers roughly one batch a second.
        for batch in samples.chunked(25) { session.onSamples(batch) }
        t = Feeds.next(samples)
    }

    private func warmUp() { feed(Feeds.warmup()) }
    private func catches(_ n: Int) { feed(Feeds.catches(t, n)) }
    private func idle(_ samples: Int = 60) { feed(Feeds.baseline(t, samples)) }

    private func completedRun(_ n: Int) {
        catches(n)
        idle()
    }

    private func select(_ id: String) { session.onMenuSelect(id) }

    // MARK: JUG-1

    func testJUG1ShowsRunStateLiveCountAndSessionStats() {
        warmUp()
        XCTAssertFalse(state.runActive)
        catches(3)
        XCTAssertTrue(state.runActive)
        XCTAssertEqual(state.currentCount, 3)
        idle()
        XCTAssertFalse(state.runActive)
        XCTAssertEqual(state.currentCount, 0)
        XCTAssertEqual(state.previousCount, 3)
        XCTAssertEqual(state.runs, 1)
        XCTAssertEqual(state.average, 3.0)
        XCTAssertEqual(state.max, 3)
    }

    func testJUG1ElapsedTimeCountsFromTheScreenOpening() {
        _ = session
        scheduler.advance(65_000)
        session.tick()
        XCTAssertEqual(state.elapsedSeconds, 65)
        XCTAssertEqual(Format.elapsed(state.elapsedSeconds), "1:05")
    }

    // MARK: JUG-2

    func testJUG2StartStopOpensTheSessionEndMenu() {
        session.onStartStop()
        XCTAssertEqual(state.menu?.title, "End session?")
        XCTAssertEqual(state.menu?.items.map(\.label), ["Sync and quit", "Quit without sync", "Continue"])
    }

    func testJUG2TheSessionEndMenuPausesTheSessionClockAndContinueResumesIt() {
        _ = session
        scheduler.advance(10_000)
        session.onStartStop()
        scheduler.advance(20_000)
        session.tick()
        XCTAssertEqual(state.elapsedSeconds, 10)
        select(TrackerSession.itemContinue)
        XCTAssertNil(state.menu)
        XCTAssertEqual(state.elapsedSeconds, 10)
        scheduler.advance(5_000)
        session.tick()
        XCTAssertEqual(state.elapsedSeconds, 15)
    }

    func testJUG2QuitWithoutSyncExitsWithoutSending() {
        warmUp()
        completedRun(3)
        session.onStartStop()
        select(TrackerSession.itemNoSyncQuit)
        XCTAssertEqual(effects.exits, 1)
        XCTAssertTrue(link.sent.isEmpty)
    }

    // MARK: JUG-3 / JUG-4 / JUG-5

    func testJUG3BackDuringARunOffersToDiscardThisRun() {
        warmUp()
        catches(4)
        session.onBack()
        let menu = state.menu!
        XCTAssertEqual(menu.title, "Discard this run?")
        XCTAssertEqual(menu.items[0].label, "Discard")
        XCTAssertEqual(menu.items[0].subLabel, "4 catches")
        XCTAssertEqual(menu.items[1].label, "Keep")

        select(TrackerSession.itemDiscardYes)
        XCTAssertEqual(state.currentCount, 0)
        XCTAssertEqual(state.runs, 0)
        XCTAssertEqual(effects.exits, 0)
    }

    func testJUG3BackBetweenRunsOffersToDiscardTheLastRun() {
        warmUp()
        completedRun(5)
        completedRun(3)
        session.onBack()
        XCTAssertEqual(state.menu?.title, "Discard last run?")
        XCTAssertEqual(state.menu?.items[0].subLabel, "3 catches")
        select(TrackerSession.itemDiscardYes)
        XCTAssertEqual(state.runs, 1)
        XCTAssertEqual(state.previousCount, 5)
    }

    func testJUG3KeepOrBackingOutOfThePromptChangesNothing() {
        warmUp()
        completedRun(3)
        session.onBack()
        select(TrackerSession.itemDiscardNo)
        XCTAssertEqual(state.runs, 1)

        session.onBack()
        session.onMenuBack()
        XCTAssertNil(state.menu)
        XCTAssertEqual(state.runs, 1)
        XCTAssertEqual(effects.exits, 0)
    }

    func testJUG4BackWithNothingRecordedIsSwallowedAndNeverExits() {
        warmUp()
        session.onBack()
        XCTAssertNil(state.menu)
        XCTAssertEqual(effects.exits, 0)
    }

    func testJUG5TheDiscardPromptUsesTheAppsOwnEnglishLabels() {
        warmUp()
        catches(3)
        session.onBack()
        XCTAssertEqual(state.menu?.items.map(\.label), ["Discard", "Keep"])
    }

    // MARK: JUG-6

    func testJUG6BackingOutOfAnyMenuLeavesStartStopWorking() {
        warmUp()
        catches(3)
        session.onBack()
        session.onMenuBack()
        session.onStartStop()
        XCTAssertEqual(state.menu?.title, "End session?")
        session.onMenuBack()
        XCTAssertNil(state.menu)
        session.onStartStop()
        XCTAssertEqual(state.menu?.title, "End session?")
    }

    // MARK: JUG-7

    func testJUG7VibratesEveryTenCatchesAndResetsWhenTheRunFinishes() {
        warmUp()
        catches(9)
        XCTAssertEqual(state.currentCount, 9)
        XCTAssertEqual(effects.vibrations, 0)
        // Two more bursts: the other hand, then the 10th watch-hand catch.
        feed(Feeds.burst(t))
        feed(Feeds.burst(t))
        XCTAssertEqual(state.currentCount, 10)
        XCTAssertEqual(effects.vibrations, 1)
        idle()
        XCTAssertEqual(effects.vibrations, 1)
        catches(10)
        XCTAssertEqual(effects.vibrations, 2, "the counter restarts with the next run")
    }

    // MARK: JUG-8

    func testJUG8ContinueAfterAFailedSyncClearsTheBanner() {
        warmUp()
        completedRun(3)
        session.onStartStop()
        select(TrackerSession.itemSyncQuit)
        link.complete(false)
        XCTAssertEqual(state.errorMessage, "Sync failed")
        XCTAssertEqual(state.menu?.title, "Sync failed")
        select(TrackerSession.itemContinue)
        XCTAssertNil(state.errorMessage)
    }

    func testJUG8ContinueAbandonsTheSyncSoALateAckCannotCloseTheSession() {
        warmUp()
        completedRun(3)
        session.onStartStop()
        select(TrackerSession.itemSyncQuit)
        scheduler.advance(TrackerSession.syncTimeoutMs)
        select(TrackerSession.itemContinue)

        link.ack(1_700_000_000) // the phone stored the first copy after all
        XCTAssertEqual(effects.exits, 0)
        XCTAssertFalse(state.exited)
    }

    func testJUG8ContinueWhileASyncIsInFlightStopsIt() {
        warmUp()
        completedRun(3)
        session.onStartStop()
        select(TrackerSession.itemSyncQuit)
        XCTAssertTrue(state.sending)

        session.onStartStop()
        select(TrackerSession.itemContinue)
        XCTAssertFalse(state.sending)

        // Neither the old attempt's timer nor its late failure pops a menu mid-juggle.
        scheduler.advance(TrackerSession.syncTimeoutMs)
        link.complete(false, index: 0)
        XCTAssertNil(state.menu)
        XCTAssertNil(state.errorMessage)
        link.ack()
        XCTAssertEqual(effects.exits, 0)
    }

    // MARK: Detection pauses under a menu, as the Garmin sensor does

    func testSamplesAreIgnoredWhileAMenuIsOpen() {
        warmUp()
        session.onStartStop()
        catches(3)
        session.onMenuBack()
        XCTAssertEqual(state.currentCount, 0)
    }

    // MARK: SYNC-1

    func testSYNC1ASessionGoesOverAsOneMessage() {
        warmUp()
        completedRun(3)
        catches(4) // still in progress: folded in on sync
        scheduler.advance(42_000)
        session.onStartStop()
        select(TrackerSession.itemSyncQuit)

        XCTAssertEqual(link.sent.count, 1)
        let payload = link.sent[0]
        XCTAssertEqual(payload["type"] as? String, "session")
        XCTAssertEqual(payload["countMode"] as? String, "watch_hand")
        XCTAssertEqual(payload["balls"] as? Int, 3)
        XCTAssertEqual(payload["timestamp"] as? Int64, 1_700_000_000)
        XCTAssertEqual(payload["durationSeconds"] as? Int64, 42)
        XCTAssertEqual(payload["runs"] as? [Int], [3, 4])
        XCTAssertEqual((payload["runDurationsMillis"] as? [Int64])?.count, 2)
        // SHAPE-4: the session's shape consistency goes along with it.
        XCTAssertTrue((0...100).contains(state.shapeConsistency))
        XCTAssertEqual(payload["shapeConsistency"] as? Int, state.shapeConsistency)
        XCTAssertTrue(state.sending)
        XCTAssertEqual(Format.syncing(state.syncDots), "Sync to phone.")
        // The phone reads it back, after the JSON round trip, as these runs.
        let back = WatchProtocol.decode(WatchProtocol.encode(payload))
        let summary = back.flatMap { SessionSummary.fromWatchPayload($0) }
        XCTAssertEqual(summary?.runHistory, [3, 4])
        XCTAssertEqual(summary?.timestamp, 1_700_000_000_000)
    }

    func testSYNC1ShapeConsistencyIsLeftOutUntilARunHasBeenScored() {
        warmUp()
        catches(3) // far too short for one window
        session.onStartStop()
        select(TrackerSession.itemSyncQuit)

        XCTAssertEqual(state.shapeConsistency, -1)
        XCTAssertNil(link.sent.first?["shapeConsistency"])
    }

    // MARK: SYNC-2

    func testSYNC2DeliveryAloneDoesNotCloseTheSessionTheAckDoes() {
        warmUp()
        completedRun(3)
        session.onStartStop()
        select(TrackerSession.itemSyncQuit)
        link.complete(true)
        XCTAssertEqual(effects.exits, 0)
        XCTAssertTrue(state.sending)
        link.ack(1_700_000_000)
        XCTAssertEqual(effects.exits, 1)
        XCTAssertTrue(state.exited)
    }

    func testSYNC2ATimeoutOpensTheRetryMenuAndRetrySendsAgain() {
        warmUp()
        completedRun(3)
        session.onStartStop()
        select(TrackerSession.itemSyncQuit)
        link.complete(true)
        scheduler.advance(TrackerSession.syncTimeoutMs)
        XCTAssertFalse(state.sending)
        XCTAssertEqual(state.menu?.title, "Sync failed")
        XCTAssertEqual(state.menu?.items.map(\.label), ["Retry sync", "Quit without sync", "Continue"])
        select(TrackerSession.itemSyncRetry)
        XCTAssertEqual(link.sent.count, 2)
        XCTAssertEqual(link.sent[0] as NSDictionary, link.sent[1] as NSDictionary)
        link.ack()
        XCTAssertEqual(effects.exits, 1)
    }

    func testSYNC2AnAckArrivingWhileTheRetryMenuIsOpenStillClosesTheSession() {
        warmUp()
        completedRun(3)
        session.onStartStop()
        select(TrackerSession.itemSyncQuit)
        scheduler.advance(TrackerSession.syncTimeoutMs)
        XCTAssertEqual(state.menu?.title, "Sync failed")
        link.ack()
        XCTAssertNil(state.menu)
        XCTAssertEqual(effects.exits, 1)
    }

    func testSYNC2AnAckWithNothingPendingIsIgnored() {
        _ = session
        link.ack()
        XCTAssertEqual(effects.exits, 0)
    }

    // MARK: SYNC-3

    func testSYNC3ASessionWithNoCompletedRunsIsNotSent() {
        warmUp()
        catches(2) // a false start
        session.onStartStop()
        select(TrackerSession.itemSyncQuit)
        XCTAssertTrue(link.sent.isEmpty)
        XCTAssertEqual(effects.exits, 1)
    }

    // MARK: SYNC-4

    func testSYNC4ALateFailureFromAnAbandonedAttemptDoesNotAbortTheRetry() {
        warmUp()
        completedRun(3)
        session.onStartStop()
        select(TrackerSession.itemSyncQuit)
        scheduler.advance(TrackerSession.syncTimeoutMs)
        select(TrackerSession.itemSyncRetry)
        XCTAssertTrue(state.sending)

        link.complete(false, index: 0) // the first attempt reports back late
        XCTAssertTrue(state.sending, "the retry is still in flight")
        XCTAssertNil(state.menu)
    }

    // MARK: SYNC-5

    func testSYNC5EndingAgainAfterContinueResendsTheWholeSessionUnderTheFirstTimestamp() {
        warmUp()
        completedRun(3)
        session.onStartStop()
        select(TrackerSession.itemSyncQuit)
        scheduler.advance(TrackerSession.syncTimeoutMs)
        select(TrackerSession.itemContinue)

        epochNow += 120
        completedRun(4)
        session.onStartStop()
        select(TrackerSession.itemSyncQuit)

        XCTAssertEqual(link.sent.count, 2)
        XCTAssertEqual(link.sent[1]["timestamp"] as? Int64, 1_700_000_000)
        XCTAssertEqual(link.sent[1]["runs"] as? [Int], [3, 4])
        link.ack(1_700_000_000)
        XCTAssertEqual(effects.exits, 1)
    }

    func testStatusDotsCycleOnceASecondWhileSending() {
        warmUp()
        completedRun(3)
        session.onStartStop()
        select(TrackerSession.itemSyncQuit)
        XCTAssertEqual(state.syncDots, 1)
        scheduler.advance(1_000)
        XCTAssertEqual(state.syncDots, 2)
        scheduler.advance(2_000)
        XCTAssertEqual(state.syncDots, 1)
    }

    func testEveryChangeIsReported() {
        var changes = 0
        session.onChange = { changes += 1 }
        warmUp()
        XCTAssertGreaterThan(changes, 0)
    }
}
