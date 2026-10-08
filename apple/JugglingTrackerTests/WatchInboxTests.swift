import XCTest
@testable import JugglingTracker

/// The phone may only ack what it has stored: a watch that gets an ack throws
/// its copy away. The Android `WatchInboxTest` cases.
final class WatchInboxTests: XCTestCase {
    private var sessions: SessionStore!
    private var recordings: RecordingStore!
    private var inbox: WatchInbox!
    private var events: [WatchInbox.Event] = []

    override func setUp() {
        super.setUp()
        let dir = makeTempDirectory(self)
        sessions = SessionStore(fileURL: dir.appendingPathComponent("sessions.jsonl"))
        recordings = RecordingStore(directory: dir.appendingPathComponent("recordings"))
        inbox = WatchInbox(sessions: sessions, recordings: recordings)
        events = []
        inbox.addListener { [unowned self] in self.events.append($0) }
    }

    private func session(_ timestamp: Int64, _ runs: [Int]) -> [String: Any] {
        ["type": "session", "countMode": "watch_hand", "balls": 3, "timestamp": timestamp, "durationSeconds": 60, "runs": runs]
    }

    private func recStart(samples: Int, chunks: Int, id: Int64 = 1_780_000_000) -> [String: Any] {
        ["type": "rec_start", "id": id, "balls": 5, "catches": 12, "detected": 9, "sampleRate": 25, "samples": samples, "chunks": chunks]
    }

    private func recChunk(_ index: Int, _ values: [Int], id: Int64 = 1_780_000_000) -> [String: Any] {
        ["type": "rec_chunk", "id": id, "i": index, "x": values, "y": values, "z": values]
    }

    private func recEnd(id: Int64 = 1_780_000_000) -> [String: Any] {
        ["type": "rec_end", "id": id]
    }

    private func ackTimestamp(_ ack: [String: Any]?) -> Int64? {
        guard let ack, ack["type"] as? String == "ack" else { return nil }
        return ack["timestamp"] as? Int64
    }

    func testAStoredSessionIsAckedWithItsTimestamp() {
        let ack = inbox.receive(session(1_780_000_000, [10, 20]), from: .garmin)
        XCTAssertEqual(ackTimestamp(ack), 1_780_000_000)
        XCTAssertEqual(sessions.sessions.count, 1)
        XCTAssertEqual(sessions.sessions[0].timestamp, 1_780_000_000_000)
    }

    func testASessionThatCannotBeReadIsNotAcked() {
        XCTAssertNil(inbox.receive(["type": "session", "balls": 3], from: .garmin))
        XCTAssertNil(inbox.receive(session(1_780_000_000, []), from: .garmin))
        XCTAssertTrue(sessions.sessions.isEmpty)
    }

    func testAResentSessionIsAckedAgainAndReplacesTheFirstCopy() {
        inbox.receive(session(1_780_000_000, [10]), from: .garmin)
        let ack = inbox.receive(session(1_780_000_000, [10, 25]), from: .garmin)
        XCTAssertEqual(ackTimestamp(ack), 1_780_000_000)
        XCTAssertEqual(sessions.sessions.map(\.runHistory), [[10, 25]])
    }

    func testMessagesThatAreNotWatchPayloadsGetNoAck() {
        XCTAssertNil(inbox.receive(["type": "ack"], from: .garmin))
        XCTAssertNil(inbox.receive(["balls": 3], from: .garmin))
        XCTAssertTrue(events.isEmpty)
    }

    func testReceivingNamesTheWatchItCameFrom() {
        inbox.receive(session(1_780_000_000, [10]), from: .appleWatch)
        XCTAssertEqual(events.first, .receiving(.appleWatch, more: false))
    }

    func testACompleteRunIsWrittenAndAckedWithItsId() {
        XCTAssertNil(inbox.receive(recStart(samples: 4, chunks: 2), from: .garmin))
        XCTAssertNil(inbox.receive(recChunk(0, [1, 2]), from: .garmin))
        XCTAssertNil(inbox.receive(recChunk(1, [3, 4]), from: .garmin))
        let ack = inbox.receive(recEnd(), from: .garmin)

        XCTAssertEqual(ackTimestamp(ack), 1_780_000_000)
        let list = recordings.listRecordings()
        XCTAssertEqual(list.count, 1)
        XCTAssertEqual(list[0].samples, 4)
        XCTAssertTrue(list[0].fromWatch)
        XCTAssertEqual(events.last, .recordingStored)
        XCTAssertEqual(events.prefix(3).map { $0 }, [
            .receiving(.garmin, more: true), .receiving(.garmin, more: true), .receiving(.garmin, more: true),
        ])
    }

    func testADuplicatedChunkIsIgnored() {
        inbox.receive(recStart(samples: 4, chunks: 2), from: .garmin)
        inbox.receive(recChunk(0, [1, 2]), from: .garmin)
        inbox.receive(recChunk(0, [1, 2]), from: .garmin)
        inbox.receive(recChunk(1, [3, 4]), from: .garmin)
        XCTAssertNotNil(inbox.receive(recEnd(), from: .garmin))
    }

    func testARunMissingAChunkIsDroppedAndNotAcked() {
        inbox.receive(recStart(samples: 4, chunks: 2), from: .garmin)
        inbox.receive(recChunk(1, [3, 4]), from: .garmin)
        XCTAssertNil(inbox.receive(recEnd(), from: .garmin))
        XCTAssertTrue(recordings.listRecordings().isEmpty)
    }

    func testARunCutShortIsNotAcked() {
        inbox.receive(recStart(samples: 6, chunks: 2), from: .garmin)
        inbox.receive(recChunk(0, [1, 2]), from: .garmin)
        inbox.receive(recChunk(1, [3, 4]), from: .garmin)
        XCTAssertNil(inbox.receive(recEnd(), from: .garmin))
    }

    func testARunIsSavedWithTheCurrentJuggler() {
        let jonas = Juggler(name: "Jonas", hand: Juggler.left, firstThrow: Juggler.right)
        inbox = WatchInbox(sessions: sessions, recordings: recordings) { jonas }
        inbox.receive(recStart(samples: 2, chunks: 1), from: .appleWatch)
        inbox.receive(recChunk(0, [1, 2]), from: .appleWatch)
        XCTAssertNotNil(inbox.receive(recEnd(), from: .appleWatch))
        XCTAssertEqual(recordings.listRecordings().first?.juggler, jonas)
    }
}
