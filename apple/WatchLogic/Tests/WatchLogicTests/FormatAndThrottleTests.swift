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

final class SampleThrottleTests: XCTestCase {
    private func keptPerSecond(_ intervalMs: Int64, seconds: Int64 = 10) -> Double {
        var throttle = SampleThrottle()
        var kept = 0
        var t: Int64 = 0
        while t < seconds * 1000 {
            if throttle.accept(t) { kept += 1 }
            t += intervalMs
        }
        return Double(kept) / Double(seconds)
    }

    func testDET1FasterSensorsAreThinnedTo25Hz() {
        for interval: Int64 in [5, 10, 20, 30, 38] {
            XCTAssertEqual(keptPerSecond(interval), 25.0, accuracy: 1.0, "sensor every \(interval) ms")
        }
    }

    func testASensorAlreadyAt25HzKeepsEverySample() {
        XCTAssertEqual(keptPerSecond(40), 25.0)
    }

    func testASlowerSensorKeepsEverySampleItGets() {
        XCTAssertEqual(keptPerSecond(50), 20.0)
    }
}
