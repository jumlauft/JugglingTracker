import JugglingCore
import XCTest
@testable import WatchLogic

/// WatchConnectivity as the watch app sees it, without a radio: activation
/// and every send finish only when a test says so.
final class FakeConnectivity: PhoneConnectivity {
    struct NotReachable: Error {}

    var isActivated = false
    var onActivation: ((Bool) -> Void)?
    var onMessage: (([String: Any]) -> Void)?
    private(set) var activations = 0
    private(set) var sent: [[String: Any]] = []
    private var replies: [([String: Any]) -> Void] = []
    private var failures: [(Error) -> Void] = []

    /// When set, answers every message at once, the way the iPhone app does:
    /// an ack for a session or a run's end marker, an empty reply otherwise.
    var phoneAnswers = false

    func activate() { activations += 1 }

    func finishActivation(_ activated: Bool) {
        isActivated = activated
        onActivation?(activated)
    }

    func sendMessage(
        _ message: [String: Any],
        reply: @escaping ([String: Any]) -> Void,
        failure: @escaping (Error) -> Void
    ) {
        sent.append(message)
        replies.append(reply)
        failures.append(failure)
        if phoneAnswers { reply(Self.phoneAnswer(to: message)) }
    }

    static func phoneAnswer(to message: [String: Any]) -> [String: Any] {
        switch message["type"] as? String {
        case WatchProtocol.typeSession, WatchProtocol.typeRecEnd:
            return WatchProtocol.ack(timestamp: WatchProtocol.ackTimestamp(of: message))
        default:
            return [:]
        }
    }

    func reply(_ answer: [String: Any], index: Int? = nil) {
        replies[index ?? replies.count - 1](answer)
    }

    func fail(index: Int? = nil) {
        failures[index ?? failures.count - 1](NotReachable())
    }

    var types: [String] { sent.map { $0["type"] as? String ?? "?" } }
}

final class ConnectivityPhoneLinkTests: XCTestCase {
    private let connectivity = FakeConnectivity()
    private lazy var link = ConnectivityPhoneLink(connectivity: connectivity)
    private var results: [Bool] = []
    private var heard: [[String: Any]] = []

    override func setUp() {
        super.setUp()
        link.setMessageListener { [unowned self] in self.heard.append($0) }
    }

    private func send(_ payload: [String: Any]) {
        link.send(payload) { [unowned self] in self.results.append($0) }
    }

    func testActivatesTheSessionAsSoonAsItIsMade() {
        XCTAssertEqual(connectivity.activations, 1)
    }

    func testAReplyMeansDeliveredAndItsAckReachesTheListener() {
        connectivity.finishActivation(true)
        send(["type": "session", "timestamp": 42])
        XCTAssertEqual(connectivity.types, ["session"])
        XCTAssertEqual(results, [])

        connectivity.reply(WatchProtocol.ack(timestamp: 42))
        XCTAssertEqual(results, [true])
        XCTAssertEqual(heard.count, 1)
        XCTAssertEqual(heard.first?["type"] as? String, "ack")
        XCTAssertEqual(WatchProtocol.int64(heard.first?["timestamp"]), 42)
    }

    func testAnEmptyReplyIsDeliveryOnly() {
        connectivity.finishActivation(true)
        send(["type": "rec_chunk", "id": 1, "i": 0])
        connectivity.reply([:])
        XCTAssertEqual(results, [true])
        XCTAssertTrue(heard.isEmpty)
    }

    func testAFailedSendIsNotDelivered() {
        connectivity.finishActivation(true)
        send(["type": "session", "timestamp": 42])
        connectivity.fail()
        XCTAssertEqual(results, [false])
        XCTAssertTrue(heard.isEmpty)
    }

    func testASendIsReportedOnceEvenIfTheTransportAnswersTwice() {
        connectivity.finishActivation(true)
        send(["type": "session", "timestamp": 42])
        connectivity.reply(WatchProtocol.ack(timestamp: 42))
        connectivity.fail()
        connectivity.reply(WatchProtocol.ack(timestamp: 42))
        XCTAssertEqual(results, [true])
        XCTAssertEqual(heard.count, 1)
    }

    func testSendsWaitForActivationAndGoOutInOrder() {
        send(["type": "rec_start", "id": 1])
        send(["type": "rec_chunk", "id": 1, "i": 0])
        XCTAssertTrue(connectivity.sent.isEmpty)
        XCTAssertEqual(connectivity.activations, 1, "already activating, so no second activate")

        connectivity.finishActivation(true)
        XCTAssertEqual(connectivity.types, ["rec_start", "rec_chunk"])
    }

    func testFailedActivationFailsTheWaitingSendsAndTheNextSendTriesAgain() {
        send(["type": "session", "timestamp": 42])
        connectivity.finishActivation(false)
        XCTAssertEqual(results, [false])
        XCTAssertTrue(connectivity.sent.isEmpty)

        send(["type": "session", "timestamp": 42]) // Retry sync
        XCTAssertEqual(connectivity.activations, 2)
        connectivity.finishActivation(true)
        XCTAssertEqual(connectivity.types, ["session"])
    }

    func testMessagesThePhoneSendsOnItsOwnReachTheListener() {
        connectivity.onMessage?(WatchProtocol.ack(timestamp: 7))
        XCTAssertEqual(WatchProtocol.int64(heard.first?["timestamp"]), 7)
    }

    // MARK: With the sessions

    private func juggleOneRun(_ session: TrackerSession) {
        let warmup = Feeds.warmup()
        let run = Feeds.catches(Feeds.next(warmup), 3)
        let rest = Feeds.baseline(Feeds.next(run), 60)
        for batch in (warmup + run + rest).chunked(25) { session.onSamples(batch) }
        XCTAssertEqual(session.state.runs, 1)
    }

    func testSyncAndQuitClosesTheJuggleSessionOnThePhonesAck() {
        let scheduler = FakeScheduler()
        let effects = FakeEffects()
        connectivity.finishActivation(true)
        connectivity.phoneAnswers = true
        let session = TrackerSession(
            ballCount: 3, link: link, scheduler: scheduler, clock: scheduler, effects: effects,
            epochSeconds: { 1_700_000_000 }
        )
        juggleOneRun(session)
        session.onStartStop()
        session.onMenuSelect(TrackerSession.itemSyncQuit)

        XCTAssertEqual(connectivity.types, ["session"])
        XCTAssertTrue(session.state.exited)
        XCTAssertEqual(effects.exits, 1)
    }

    func testNoPhoneInReachOffersRetryAndContinue() {
        let scheduler = FakeScheduler()
        let effects = FakeEffects()
        connectivity.finishActivation(true)
        let session = TrackerSession(
            ballCount: 3, link: link, scheduler: scheduler, clock: scheduler, effects: effects,
            epochSeconds: { 1_700_000_000 }
        )
        juggleOneRun(session)
        session.onStartStop()
        session.onMenuSelect(TrackerSession.itemSyncQuit)
        connectivity.fail()

        XCTAssertEqual(session.state.errorMessage, "Sync failed")
        let items = session.state.menu?.items.map(\.id) ?? []
        XCTAssertTrue(items.contains(TrackerSession.itemSyncRetry))
        XCTAssertTrue(items.contains(TrackerSession.itemContinue))
        XCTAssertEqual(effects.exits, 0)
    }

    func testARecordedRunGoesOverInPartsAndClosesOnTheAckForItsEnd() {
        let scheduler = FakeScheduler()
        let effects = FakeEffects()
        connectivity.finishActivation(true)
        connectivity.phoneAnswers = true
        let session = RecordingSession(
            ballCount: 3, link: link, scheduler: scheduler, effects: effects,
            epochSeconds: { 1_700_000_000 }, log: { _ in }
        )
        session.onStart()
        let samples = Feeds.warmup() + Feeds.catches(Feeds.next(Feeds.warmup()), 3)
        for batch in samples.chunked(25) { session.onSamples(batch) }
        session.onStart() // stop, label screen
        session.onStart() // confirm, sync
        scheduler.advance(RecordingSession.nextPartDelayMs * 200)

        XCTAssertEqual(connectivity.types.first, "rec_start")
        XCTAssertEqual(connectivity.types.last, "rec_end")
        XCTAssertEqual(session.state.phase, .idle)
        XCTAssertEqual(session.state.runsCompleted, 1)
    }
}
