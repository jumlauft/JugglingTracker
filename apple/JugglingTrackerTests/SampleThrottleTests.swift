import XCTest
@testable import JugglingTracker

/// The Android `SampleThrottleTest` cases.
final class SampleThrottleTests: XCTestCase {
    private func keptPerSecond(intervalMs: Int64, seconds: Int64 = 10) -> Double {
        var throttle = SampleThrottle()
        var kept = 0
        var t: Int64 = 0
        while t < seconds * 1000 {
            if throttle.accept(t) { kept += 1 }
            t += intervalMs
        }
        return Double(kept) / Double(seconds)
    }

    func testFasterSensorsAreThinnedTo25Hz() {
        for interval: Int64 in [5, 10, 20, 30, 38] {
            XCTAssertEqual(keptPerSecond(intervalMs: interval), 25, accuracy: 1, "sensor every \(interval) ms")
        }
    }

    func testAJitteryFastSensorStillYields25Hz() {
        var throttle = SampleThrottle()
        let gaps: [Int64] = [4, 6, 5, 7, 3, 6, 4]
        var kept = 0
        var t: Int64 = 0
        var i = 0
        while t < 60_000 {
            if throttle.accept(t) { kept += 1 }
            t += gaps[i % gaps.count]
            i += 1
        }
        XCTAssertEqual(Double(kept) / 60, 25, accuracy: 0.2)
    }

    func testASensorAlreadyAt25HzKeepsEverySample() {
        XCTAssertEqual(keptPerSecond(intervalMs: 40), 25)
    }

    func testASlowerSensorKeepsEverySampleItGets() {
        XCTAssertEqual(keptPerSecond(intervalMs: 50), 20)
    }

    func testResetStartsAFreshGrid() {
        var throttle = SampleThrottle()
        XCTAssertTrue(throttle.accept(1_000))
        XCTAssertFalse(throttle.accept(1_010))
        throttle.reset()
        XCTAssertTrue(throttle.accept(1_010))
    }
}
