import XCTest
@testable import JugglingCore

/// The Garmin detector tests of `connectiq/test/DetectorTest.mc`, run against
/// the Swift port with the same inputs and expectations, as the Kotlin
/// `JugglingDetectorTest` does. Each test names the requirement in
/// `connectiq/REQUIREMENTS.md` it secures.
final class JugglingDetectorTests: XCTestCase {
    private func warmedUp(_ d: JugglingDetector) -> Int64 { d.feed(Feeds.warmup()) }

    @discardableResult
    private func catches(_ d: JugglingDetector, _ startMs: Int64, _ n: Int) -> Int64 {
        d.feed(Feeds.catches(startMs, n))
    }

    @discardableResult
    private func idle(_ d: JugglingDetector, _ startMs: Int64, _ samples: Int = 60) -> Int64 {
        d.feed(Feeds.baseline(startMs, samples))
    }

    func testDET1SamplesAreMilliGConvertedToMetresPerSecondSquared() {
        XCTAssertEqual(JugglingDetector.milliGToMs2, 9.80665 / 1000.0)
        XCTAssertEqual(JugglingDetector.sampleRate, 25)
    }

    func testDET4NoCountDuringWarmup() {
        let d = JugglingDetector(ballCount: 3)
        var t: Int64 = 0
        for _ in 0..<24 {
            d.processSample(0, 0, -4000, nowMs: t)
            t += Feeds.periodMs
        }
        XCTAssertEqual(d.currentCount, 0)
        XCTAssertEqual(d.sessionRuns, 0)
    }

    func testDET5RawMagnitudeGateRejectsWeakMotion() {
        let d = JugglingDetector(ballCount: 3)
        var t = warmedUp(d)
        t = d.feed(Feeds.burst(t, amplitudeMs2: 4.0))
        idle(d, t, 20)
        XCTAssertEqual(d.currentCount, 0)
    }

    func testDET6ParametersArePerBallCount() {
        let expected: [Int: [Double]] = [
            3: [2.0, 80, 7.0, 160],
            4: [3.0, 80, 11.0, 160],
            5: [3.0, 40, 13.0, 160],
            6: [5.0, 40, 17.0, 120],
            7: [2.5, 160, 16.0, 80],
            9: [2.5, 160, 16.0, 80],
        ]
        for (balls, p) in expected {
            let d = JugglingDetector(ballCount: balls)
            XCTAssertEqual(
                [d.hpThreshold, Double(d.refractoryMs), d.minRawMag, Double(d.mergeWindowMs)], p, "\(balls) balls"
            )
        }
    }

    func testDET6TheGateIsPerBallCount() {
        let three = JugglingDetector(ballCount: 3)
        three.feed(Feeds.burst(warmedUp(three), amplitudeMs2: 12.0))
        XCTAssertEqual(three.currentCount, 1, "12 m/s2 clears the 3-ball gate of 7.0")

        let six = JugglingDetector(ballCount: 6)
        six.feed(Feeds.burst(warmedUp(six), amplitudeMs2: 12.0))
        XCTAssertEqual(six.currentCount, 0, "12 m/s2 must not clear the 6-ball gate of 17.0")
    }

    func testDET8EveryOtherBurstIsAWatchHandCatch() {
        let d = JugglingDetector(ballCount: 3)
        var t = warmedUp(d)
        t = d.feed(Feeds.burst(t))
        XCTAssertEqual(d.currentCount, 1)
        t = d.feed(Feeds.burst(t))
        XCTAssertEqual(d.currentCount, 1)
        d.feed(Feeds.burst(t))
        XCTAssertEqual(d.currentCount, 2)
    }

    func testDET9RunAutoFinishesAfterIdleDelay() {
        let d = JugglingDetector(ballCount: 3)
        var t = warmedUp(d)
        t = catches(d, t, 3)
        XCTAssertEqual(d.currentCount, 3)
        idle(d, t)
        XCTAssertEqual(d.currentCount, 0)
        XCTAssertEqual(d.previousCount, 3)
        XCTAssertEqual(d.sessionRuns, 1)
    }

    func testDET9AutoFinishKeysOnCommittedBurstsNotLowLevelMotion() {
        let d = JugglingDetector(ballCount: 3)
        var t = warmedUp(d)
        t = catches(d, t, 3)
        // Wrist motion under the gate keeps arriving but never commits a burst.
        for _ in 0..<6 { t = d.feed(Feeds.burst(t, amplitudeMs2: 4.0)) }
        XCTAssertEqual(d.currentCount, 0, "low-level motion must not hold the run open")
        XCTAssertEqual(d.sessionRuns, 1)
    }

    func testDET10RunDurationSpansFirstToLastCatch() {
        let d = JugglingDetector(ballCount: 3)
        catches(d, warmedUp(d), 3)
        d.finishCurrentRun()
        // Five bursts, 15 samples apart: first to last watch-hand catch is 4 bursts.
        XCTAssertEqual(d.runDurationsMillis, [4 * 15 * Feeds.periodMs])
    }

    func testRUN1FalseStartsAreNotRecorded() {
        let d = JugglingDetector(ballCount: 3)
        var t = warmedUp(d)
        t = catches(d, t, 2)
        XCTAssertEqual(d.currentCount, 2)
        idle(d, t)
        XCTAssertEqual(d.sessionRuns, 0)
        XCTAssertEqual(d.previousCount, 0)
        XCTAssertEqual(d.sessionMax, 0)
        XCTAssertEqual(d.runCatches, [])
        XCTAssertEqual(d.currentCount, 0)
    }

    func testRUN1ThreeCatchesIsARealRun() {
        let d = JugglingDetector(ballCount: 3)
        idle(d, catches(d, warmedUp(d), 3))
        XCTAssertEqual(d.sessionRuns, 1)
        XCTAssertEqual(d.previousCount, 3)
        XCTAssertEqual(d.sessionMax, 3)
    }

    func testRUN3SessionAverageIsOverCompletedRuns() {
        let d = JugglingDetector(ballCount: 3)
        XCTAssertEqual(d.sessionAverage, 0.0)
        var t = warmedUp(d)
        t = idle(d, catches(d, t, 3))
        idle(d, catches(d, t, 5))
        XCTAssertEqual(d.sessionRuns, 2)
        XCTAssertEqual(d.sessionAverage, 4.0)
        XCTAssertEqual(d.sessionMax, 5)
    }

    func testRUN4DiscardDropsTheRunInProgress() {
        let d = JugglingDetector(ballCount: 3)
        catches(d, warmedUp(d), 4)
        XCTAssertTrue(d.isRunActive)
        XCTAssertTrue(d.discardLastRun())
        XCTAssertEqual(d.currentCount, 0)
        XCTAssertEqual(d.sessionRuns, 0)
        XCTAssertEqual(d.previousCount, 0)
    }

    func testRUN4DiscardRemovesLastCompletedRunAndRecomputes() {
        let d = JugglingDetector(ballCount: 3)
        var t = warmedUp(d)
        t = idle(d, catches(d, t, 5))
        t = idle(d, catches(d, t, 3))
        idle(d, catches(d, t, 7))
        XCTAssertEqual(d.sessionRuns, 3)
        XCTAssertEqual(d.sessionMax, 7)
        XCTAssertEqual(d.sessionAverage, 5.0)

        XCTAssertTrue(d.discardLastRun())
        XCTAssertEqual(d.sessionRuns, 2)
        XCTAssertEqual(d.previousCount, 3)
        XCTAssertEqual(d.sessionMax, 5)
        XCTAssertEqual(d.sessionAverage, 4.0)
        XCTAssertEqual(d.runCatches.count, 2)
        XCTAssertEqual(d.runDurationsMillis.count, 2)
    }

    func testRUN4DiscardTakesTheLastRunNotAMatchingEarlierOne() {
        let d = JugglingDetector(ballCount: 3)
        var t = warmedUp(d)
        t = idle(d, catches(d, t, 4))
        t = idle(d, catches(d, t, 6))
        idle(d, catches(d, t, 4))
        d.discardLastRun()
        XCTAssertEqual(d.runCatches, [4, 6])
        XCTAssertEqual(d.sessionMax, 6)
    }

    func testRUN5DiscardReportsFailureWhenThereIsNothingToDiscard() {
        let d = JugglingDetector(ballCount: 3)
        XCTAssertFalse(d.discardLastRun())
        idle(d, warmedUp(d), 20)
        XCTAssertFalse(d.discardLastRun())
    }

    func testRUN6FinishingRecordsTheRunInProgress() {
        let d = JugglingDetector(ballCount: 3)
        catches(d, warmedUp(d), 4)
        XCTAssertEqual(d.finishCurrentRun(), 4)
        XCTAssertEqual(d.currentCount, 0)
        XCTAssertEqual(d.sessionRuns, 1)
        XCTAssertEqual(d.previousCount, 4)
    }

    func testRUN6FinishingWithNoRunInProgressRecordsNothing() {
        let d = JugglingDetector(ballCount: 3)
        _ = warmedUp(d)
        XCTAssertEqual(d.finishCurrentRun(), 0)
        XCTAssertEqual(d.sessionRuns, 0)
    }
}
