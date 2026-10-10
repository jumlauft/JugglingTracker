import Foundation
import XCTest
@testable import JugglingCore

final class WatchProtocolTests: XCTestCase {
    func testASessionPayloadSurvivesTheRoundTrip() {
        let payload: [String: Any] = [
            "type": "session",
            "countMode": "watch_hand",
            "balls": 5,
            "timestamp": Int64(1_700_000_000),
            "durationSeconds": Int64(42),
            "runDurationsMillis": [Int64(1200), Int64(800)],
            "runs": [12, 7],
        ]
        let decoded = WatchProtocol.decode(WatchProtocol.encode(payload))!
        XCTAssertEqual(decoded["type"] as? String, "session")
        XCTAssertEqual(WatchProtocol.int(decoded["balls"]), 5)
        XCTAssertEqual(WatchProtocol.int64(decoded["timestamp"]), 1_700_000_000)
        XCTAssertEqual(WatchProtocol.intList(decoded["runs"]), [12, 7])
        XCTAssertEqual(WatchProtocol.int64List(decoded["runDurationsMillis"]), [1200, 800])
    }

    func testGarbageDecodesToNil() {
        XCTAssertNil(WatchProtocol.decode(Data("not json".utf8)))
        XCTAssertNil(WatchProtocol.decode(Data("[1,2]".utf8)))
    }

    /// The JSON a Wear OS watch sends, which is also the Garmin payload shape.
    func testASessionFromAnotherWatchDecodesToTheGarminPayloadShape() {
        let json = """
            {"type":"session","countMode":"watch_hand","balls":5,"timestamp":1700000000,
             "durationSeconds":42,"runDurationsMillis":[1200,800],"runs":[12,7]}
            """
        let payload = WatchProtocol.decode(Data(json.utf8))!
        XCTAssertEqual(payload["type"] as? String, "session")
        XCTAssertEqual(WatchProtocol.int(payload["balls"]), 5)
        XCTAssertEqual(WatchProtocol.int64(payload["timestamp"]), 1_700_000_000)
        XCTAssertEqual(WatchProtocol.intList(payload["runs"]), [12, 7])
        XCTAssertEqual(WatchProtocol.int64List(payload["runDurationsMillis"]), [1200, 800])
    }

    func testAFull1000SampleChunkKeepsEverySample() {
        let values = (0..<1000).map { -16_000 + $0 }
        let array = "[" + values.map(String.init).joined(separator: ",") + "]"
        let json = #"{"type":"rec_chunk","id":1700000000,"i":2,"x":\#(array),"y":\#(array),"z":\#(array)}"#
        let payload = WatchProtocol.decode(Data(json.utf8))!
        XCTAssertEqual(WatchProtocol.intList(payload["x"]), values)
        XCTAssertEqual(WatchProtocol.intList(payload["z"]), values)
        XCTAssertEqual(WatchProtocol.int(payload["i"]), 2)
    }

    func testTheAckEchoesTheTimestampWhenThereIsOne() {
        let withTs = WatchProtocol.decode(WatchProtocol.encode(WatchProtocol.ack(timestamp: 1_700_000_000)))!
        XCTAssertEqual(withTs["type"] as? String, "ack")
        XCTAssertEqual(WatchProtocol.int64(withTs["timestamp"]), 1_700_000_000)

        let without = WatchProtocol.ack(timestamp: nil)
        XCTAssertNil(without["timestamp"])
    }

    func testTheAckTimestampIsTheSessionTimestampOrTheRunId() {
        XCTAssertEqual(WatchProtocol.ackTimestamp(of: ["type": "session", "timestamp": 1_700_000_000]), 1_700_000_000)
        XCTAssertEqual(WatchProtocol.ackTimestamp(of: ["type": "rec_end", "id": 1_790_195_504]), 1_790_195_504)
        XCTAssertNil(WatchProtocol.ackTimestamp(of: ["type": "rec_end"]))
    }
}
