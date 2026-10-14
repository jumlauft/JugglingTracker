import XCTest
@testable import WatchLogic

final class FormatTests: XCTestCase {
    func testSHAPE4TheScreenShowsAPercentageOrADash() {
        XCTAssertEqual(Format.percentOrDash(-1), "-")
        XCTAssertEqual(Format.percentOrDash(83), "83%")
    }

    func testJUG1StatsAndTimeFormatLikeTheGarmin() {
        XCTAssertEqual(Format.elapsed(65), "1:05")
        XCTAssertEqual(Format.elapsed(3601), "1:00:01")
        XCTAssertEqual(Format.countOrDash(0), "-")
        XCTAssertEqual(Format.countOrDash(7), "7")
        XCTAssertEqual(Format.averageOrDash(0.0), "-")
        XCTAssertEqual(Format.averageOrDash(4.5), "4.5")
        XCTAssertEqual(Format.syncing(3), "Sync to phone...")
    }
}
