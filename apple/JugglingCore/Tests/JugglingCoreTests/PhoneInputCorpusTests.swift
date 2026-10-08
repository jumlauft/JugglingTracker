import XCTest
@testable import JugglingCore

/// Replays real recorded runs through the detector's m/s² entry point, the way
/// the iPhone app feeds its own accelerometer, with the idle auto-finish
/// running between samples. Same cases as the Kotlin `PhoneInputCorpusTest`.
final class PhoneInputCorpusTests: XCTestCase {
    private func detect(_ runId: String, balls: Int) -> Int {
        let k = JugglingDetector.milliGToMs2
        let detector = JugglingDetector(ballCount: balls)
        var t: Int64 = 0
        for s in loadRun(runId) {
            detector.processSampleMs2(Double(s.0) * k, Double(s.1) * k, Double(s.2) * k, nowMs: t)
            detector.checkAutoFinish(nowMs: t)
            t += JugglingDetector.samplePeriodMs
        }
        // A candidate raised by the final samples is still pending when the
        // data runs out; the app flushes it when the session ends.
        detector.finishCurrentRun()
        // Total catches however the auto-finish split the recording into runs.
        return detector.runCatches.reduce(0, +) + detector.currentCount
    }

    func testCountsRealRecordedRunsTheSameAsTheReferenceImplementations() {
        // (runId, balls, expected) from simulation/test_detection.py.
        let cases: [(String, Int, Int)] = [
            ("20260603_201719", 3, 20),
            ("20260603_223112", 3, 26),
            ("20260922_144802", 3, 88),
            ("20260922_152712", 5, 60),
            ("20260924_170736", 6, 26),
            ("20260922_152032", 7, 12),
        ]
        let failures = cases.compactMap { testCase -> String? in
            let (runId, balls, expected) = testCase
            let got = detect(runId, balls: balls)
            return got == expected ? nil : "\(runId) (\(balls)b): expected \(expected), phone input counted \(got)"
        }
        XCTAssertEqual(failures, [])
    }

    /// The known one-catch divergence the Kotlin port pins on this 4-ball run
    /// (19 where the reference finds 20). The Swift port must behave the same.
    func testKnownOneCatchDivergenceOnAFourBallRun() {
        XCTAssertEqual(detect("20260603_223524", balls: 4), 19)
    }
}
