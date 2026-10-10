import XCTest
@testable import JugglingCore

final class SessionSummaryTests: XCTestCase {
    func testSummarizeComputesTheRunStatistics() {
        let s = SessionSummary.summarize(timestamp: 1_000, ballCount: 3, runs: [10, 20, 30])
        XCTAssertEqual(s.runCount, 3)
        XCTAssertEqual(s.avgThrows, 20.0)
        XCTAssertEqual(s.stdDevThrows, (200.0 / 3.0).squareRoot(), accuracy: 1e-12)
        XCTAssertEqual(s.bestRun, 30)
        XCTAssertEqual(s.totalThrows, 60)
        XCTAssertEqual(s.runDurationsMillis, [0, 0, 0])
    }

    func testRunDurationsAreForcedToOnePerRun() {
        XCTAssertEqual(SessionSummary.normalizeRunDurations(runCount: 2, [5, 6, 7]), [5, 6])
        XCTAssertEqual(SessionSummary.normalizeRunDurations(runCount: 3, [-5]), [0, 0, 0])
    }

    func testRegularityOutsideZeroToHundredIsDropped() {
        XCTAssertNil(SessionSummary.summarize(timestamp: 1, ballCount: 3, runs: [3], shapeConsistency: 101).shapeConsistency)
        XCTAssertEqual(SessionSummary.summarize(timestamp: 1, ballCount: 3, runs: [3], shapeConsistency: 86).shapeConsistency, 86)
    }

    /// The README's example session payload, as any watch sends it.
    func testAWatchSessionPayloadBecomesAStoredSession() {
        let payload: [String: Any] = [
            "type": "session", "countMode": "watch_hand", "balls": 3, "timestamp": 1_780_511_578,
            "durationSeconds": 742, "runDurationsMillis": [8200, 5100, 10400], "runs": [17, 11, 21],
            "shapeConsistency": 86,
        ]
        let s = SessionSummary.fromWatchPayload(payload)!
        XCTAssertEqual(s.timestamp, 1_780_511_578_000, "epoch seconds become milliseconds")
        XCTAssertEqual(s.ballCount, 3)
        XCTAssertEqual(s.runHistory, [17, 11, 21])
        XCTAssertEqual(s.runDurationsMillis, [8200, 5100, 10400])
        XCTAssertEqual(s.durationSeconds, 742)
        XCTAssertEqual(s.shapeConsistency, 86)
    }

    func testAPayloadWithoutRunsHoldsNoSession() {
        XCTAssertNil(SessionSummary.fromWatchPayload(["type": "session", "balls": 3, "timestamp": 1, "runs": [Int]()]))
        XCTAssertNil(SessionSummary.fromWatchPayload(["type": "session", "timestamp": 1, "runs": [3]]))
    }
}
