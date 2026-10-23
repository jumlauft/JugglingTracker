import XCTest
@testable import JugglingTracker

/// The history graph used to truncate the touch position, so a point's touch
/// area ran from that point to the next one and the newest point on the right
/// edge could hardly be hit. Each point's area is now centred on it.
final class HistoryGraphSelectionTests: XCTestCase {
    // Five points at x = 0, 1, 2, 3, 4.
    private let count = 5

    func testTouchJustLeftOfNewestPointSelectsIt() {
        XCTAssertEqual(nearestPointIndex(3.6, count: count), 4)
    }

    func testTouchJustLeftOfAPointSelectsThatPoint() {
        XCTAssertEqual(nearestPointIndex(1.6, count: count), 2)
        XCTAssertEqual(nearestPointIndex(1.4, count: count), 1)
    }

    func testTouchOutsideTheGraphIsClamped() {
        XCTAssertEqual(nearestPointIndex(-0.3, count: count), 0)
        XCTAssertEqual(nearestPointIndex(4.5, count: count), 4)
    }

    func testSinglePointIsAlwaysSelected() {
        XCTAssertEqual(nearestPointIndex(0.4, count: 1), 0)
        XCTAssertEqual(nearestPointIndex(-0.4, count: 1), 0)
    }
}
