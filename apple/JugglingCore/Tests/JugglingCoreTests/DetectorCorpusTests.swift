import XCTest
@testable import JugglingCore

/// DET-11: replays every labelled recording in `connectiq/data` through the
/// Swift port and requires the exact count `simulation/test_detection.py` pins
/// for the Garmin detector, run by run. Same checks as the Kotlin
/// `DetectorCorpusTest`.
final class DetectorCorpusTests: XCTestCase {
    private struct Expected {
        let runId: String
        let balls: Int
        let detected: Int
    }

    private func expectedRuns() -> [Expected] {
        pinnedEntries("EXPECTED_RUNS", pattern: #"\("(\d{8}_\d{6})",\s*(\d+),\s*(\d+),\s*(\d+)\)"#).map {
            Expected(runId: $0[0], balls: Int($0[1])!, detected: Int($0[3])!)
        }
    }

    /// Counts the way the reference simulation does: one pass over the whole
    /// recording at 40 ms per sample with no idle split, then the trailing
    /// pending burst flushed.
    private func detect(_ samples: [(Int, Int, Int)], balls: Int) -> Int {
        let d = JugglingDetector(ballCount: balls)
        for (i, s) in samples.enumerated() {
            d.processSample(s.0, s.1, s.2, nowMs: Int64(i) * JugglingDetector.samplePeriodMs)
        }
        return d.finishCurrentRun()
    }

    func testDET11EveryRecordingCountsExactlyWhatTheGarminReferenceCounts() {
        let runs = expectedRuns()
        XCTAssertGreaterThanOrEqual(runs.count, 100, "expected the full corpus, parsed \(runs.count)")

        let failures = runs.compactMap { e -> String? in
            let got = detect(loadRun(e.runId), balls: e.balls)
            return got == e.detected ? nil : "\(e.runId) (\(e.balls)b): reference \(e.detected), swift port \(got)"
        }
        XCTAssertEqual(failures, [])
    }

    /// DET-9: four 7-ball runs recorded back to back split into four runs on
    /// the Juggle screen, as the reference pins. Samples arrive in one-second
    /// batches with the auto-finish checked after each, as the watches feed them.
    func testDET9BackToBackRunsSplitWhereTheReferenceSplitsThem() {
        let samples = loadRun("20261007_184543")
        let d = JugglingDetector(ballCount: 7)
        for (i, s) in samples.enumerated() {
            let t = Int64(i) * JugglingDetector.samplePeriodMs
            d.processSample(s.0, s.1, s.2, nowMs: t)
            if (i + 1) % JugglingDetector.sampleRate == 0 { d.checkAutoFinish(nowMs: t) }
        }
        d.finishCurrentRun()
        XCTAssertEqual(d.runCatches, [9, 8, 10, 13])
    }

    func testDET11EveryRecordingOnDiskIsCovered() throws {
        let dir = repoRoot.appendingPathComponent("connectiq/data")
        let onDisk = Set(try FileManager.default.contentsOfDirectory(atPath: dir.path)
            .filter { $0.hasSuffix(".csv") }
            .map { String($0.dropLast(4)) })
        XCTAssertEqual(onDisk, Set(expectedRuns().map(\.runId)))
    }
}
