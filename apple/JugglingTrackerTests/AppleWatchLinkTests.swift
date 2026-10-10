import JugglingCore
import XCTest
@testable import JugglingTracker

/// WatchConnectivity as the iPhone app sees it, without a radio.
private final class FakeWatchSession: WatchConnectivitySession {
    var isActivated = false
    var activationFailed = false
    var isPaired = false
    var isWatchAppInstalled = false
    var onStateChange: (() -> Void)?
    var onMessage: (([String: Any], @escaping ([String: Any]) -> Void) -> Void)?
    private(set) var activations = 0

    func activate() { activations += 1 }

    func finishActivation(paired: Bool, appInstalled: Bool) {
        isActivated = true
        isPaired = paired
        isWatchAppInstalled = appInstalled
        onStateChange?()
    }

    /// Delivers `message` the way WatchConnectivity does, after a round trip
    /// through a property list, and returns every answer it got.
    func deliver(_ message: [String: Any]) -> [[String: Any]] {
        var answers: [[String: Any]] = []
        onMessage?(plistRoundTrip(message)) { answers.append(plistRoundTrip($0)) }
        return answers
    }
}

/// WatchConnectivity only carries property-list values, and hands numbers
/// over as NSNumber. Fails the test if `message` holds anything else.
private func plistRoundTrip(_ message: [String: Any]) -> [String: Any] {
    let data = try! PropertyListSerialization.data(fromPropertyList: message, format: .binary, options: 0)
    return try! PropertyListSerialization.propertyList(from: data, format: nil) as! [String: Any]
}

@MainActor
final class AppleWatchLinkTests: XCTestCase {
    private var sessions: SessionStore!
    private var recordings: RecordingStore!
    private var inbox: WatchInbox!
    private var watch: FakeWatchSession!
    private var link: AppleWatchLink!
    private var statuses: [AppleWatchConnectionStatus] = []

    override func setUp() async throws {
        let dir = makeTempDirectory(self)
        sessions = SessionStore(fileURL: dir.appendingPathComponent("sessions.jsonl"))
        recordings = RecordingStore(directory: dir.appendingPathComponent("recordings"))
        inbox = WatchInbox(sessions: sessions, recordings: recordings)
        watch = FakeWatchSession()
        statuses = []
        link = AppleWatchLink(inbox: inbox, session: watch) { [unowned self] in self.statuses.append($0) }
        link.start()
    }

    private func ackTimestamp(_ answers: [[String: Any]]) -> Int64? {
        guard answers.count == 1, answers[0]["type"] as? String == "ack" else { return nil }
        return WatchProtocol.int64(answers[0]["timestamp"])
    }

    // MARK: Status

    func testChecksUntilActivatedThenShowsReady() {
        XCTAssertEqual(watch.activations, 1)
        XCTAssertEqual(statuses, [.checking])
        watch.finishActivation(paired: true, appInstalled: true)
        XCTAssertEqual(statuses, [.checking, .ready])
    }

    func testNoPairedWatchAndMissingAppShowTheChecklist() {
        watch.finishActivation(paired: false, appInstalled: false)
        XCTAssertEqual(statuses.last, .noWatch)
        watch.isPaired = true
        watch.onStateChange?()
        XCTAssertEqual(statuses.last, .watchAppMissing)
        watch.isWatchAppInstalled = true
        watch.onStateChange?()
        XCTAssertEqual(statuses.last, .ready)
    }

    func testAFailedActivationIsUnavailable() {
        watch.activationFailed = true
        watch.onStateChange?()
        XCTAssertEqual(statuses.last, .unavailable)
    }

    func testWithoutWatchConnectivityItIsUnavailable() {
        var reported: [AppleWatchConnectionStatus] = []
        AppleWatchLink(inbox: inbox, session: nil) { reported.append($0) }.start()
        XCTAssertEqual(reported, [.unavailable])
    }

    func testStopDetachesFromTheSession() {
        link.stop()
        XCTAssertNil(watch.onStateChange)
        XCTAssertNil(watch.onMessage)
    }

    // MARK: Messages

    func testASessionIsStoredAndAcked() {
        let answers = watch.deliver([
            "type": "session", "countMode": "watch_hand", "balls": 4, "timestamp": Int64(1_790_000_000),
            "durationSeconds": 60, "runs": [12, 30], "runDurationsMillis": [Int64(4_000), Int64(9_000)],
            "shapeConsistency": 81,
        ])
        XCTAssertEqual(ackTimestamp(answers), 1_790_000_000)
        XCTAssertEqual(sessions.sessions.count, 1)
        XCTAssertEqual(sessions.sessions.first?.ballCount, 4)
        XCTAssertEqual(sessions.sessions.first?.shapeConsistency, 81)
    }

    func testAnUnreadableSessionGetsAnEmptyAnswerSoTheWatchKeepsIt() {
        let answers = watch.deliver(["type": "session", "balls": 4])
        XCTAssertEqual(answers.count, 1)
        XCTAssertTrue(answers[0].isEmpty)
        XCTAssertTrue(sessions.sessions.isEmpty)
    }

    func testARecordedRunArrivesInPartsAndOnlyItsEndIsAcked() {
        let id: Int64 = 1_790_000_100
        let xs = Array(0..<1500).map { $0 - 750 }
        var answers = watch.deliver([
            "type": "rec_start", "id": id, "balls": 3, "catches": 20, "detected": 18,
            "sampleRate": 25, "samples": xs.count, "chunks": 2,
        ])
        for (i, part) in [Array(xs[0..<1000]), Array(xs[1000...])].enumerated() {
            answers += watch.deliver(["type": "rec_chunk", "id": id, "i": i, "x": part, "y": part, "z": part])
        }
        XCTAssertEqual(answers.count, 3)
        XCTAssertTrue(answers.allSatisfy(\.isEmpty), "parts are delivered, not acked")

        XCTAssertEqual(ackTimestamp(watch.deliver(["type": "rec_end", "id": id])), id)
        let saved = recordings.listRecordings()
        XCTAssertEqual(saved.count, 1)
        XCTAssertEqual(saved.first?.samples, 1500)
        XCTAssertEqual(saved.first?.source, RecordingStore.sourceWatch)
    }

    func testARunWithALostPartIsNotAcked() {
        let id: Int64 = 1_790_000_200
        _ = watch.deliver([
            "type": "rec_start", "id": id, "balls": 3, "catches": 2, "detected": 2,
            "sampleRate": 25, "samples": 4, "chunks": 2,
        ])
        _ = watch.deliver(["type": "rec_chunk", "id": id, "i": 1, "x": [1, 2], "y": [1, 2], "z": [1, 2]])
        let answers = watch.deliver(["type": "rec_end", "id": id])
        XCTAssertEqual(answers.count, 1)
        XCTAssertTrue(answers[0].isEmpty)
        XCTAssertTrue(recordings.listRecordings().isEmpty)
    }

    func testTheLargestPartTheWatchSendsFitsAWatchConnectivityMessage() throws {
        // A full 1000-sample part with readings far beyond what the sensor
        // reports. sendMessage takes up to about 65 KB.
        let part = Array(repeating: -99_999, count: 1000)
        let message: [String: Any] = ["type": "rec_chunk", "id": Int64(1_790_000_000), "i": 2, "x": part, "y": part, "z": part]
        let size = try PropertyListSerialization.data(fromPropertyList: message, format: .binary, options: 0).count
        XCTAssertLessThan(size, 50_000, "a part is \(size) bytes as a property list")
    }
}
