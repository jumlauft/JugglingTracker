import JugglingCore
import XCTest
@testable import JugglingTracker

final class ScreenshotModeTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_790_000_000)

    func testDemoHistoryClimbsForThreeFourAndFiveBalls() {
        let sessions = ScreenshotMode.demoSessions(now: now)
        XCTAssertEqual(Set(sessions.map(\.ballCount)), [3, 4, 5])
        for balls in [3, 4, 5] {
            let ordered = sessions.filter { $0.ballCount == balls }.sorted { $0.timestamp < $1.timestamp }
            XCTAssertGreaterThanOrEqual(ordered.count, 8, "\(balls) balls")
            XCTAssertGreaterThan(ordered.last!.avgThrows, ordered.first!.avgThrows * 2, "\(balls) balls")
            XCTAssertTrue(ordered.allSatisfy { $0.timestamp < Int64(now.timeIntervalSince1970 * 1000) })
            XCTAssertTrue(ordered.allSatisfy { $0.runDurationsMillis.count == $0.runCount })
        }
    }

    func testDemoHistoryIsTheSameEveryTime() {
        XCTAssertEqual(ScreenshotMode.demoSessions(now: now), ScreenshotMode.demoSessions(now: now))
    }
}
