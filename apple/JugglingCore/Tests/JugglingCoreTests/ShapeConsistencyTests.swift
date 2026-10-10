import Foundation
import XCTest
@testable import JugglingCore

/// SHAPE-1..4 for the Swift port, as the Kotlin `ShapeConsistencyTest` runs
/// them. The labelled runs in `simulation/regularity_data` go through the real
/// detector, and each must score exactly what `simulation/test_detection.py`
/// pins in `EXPECTED_SHAPE`.
final class ShapeConsistencyTests: XCTestCase {
    private func replay(_ runId: String) -> Int {
        let path = "simulation/regularity_data/\(runId).csv"
        let firstLine = readRepoFile(path).components(separatedBy: .newlines).first ?? ""
        let regex = try! NSRegularExpression(pattern: #"balls=(\d+)"#)
        let ns = firstLine as NSString
        let match = regex.firstMatch(in: firstLine, range: NSRange(location: 0, length: ns.length))!
        let balls = Int(ns.substring(with: match.range(at: 1)))!

        let detector = JugglingDetector(ballCount: balls)
        for (i, s) in loadSamples(path).enumerated() {
            detector.processSample(s.0, s.1, s.2, nowMs: Int64(i) * JugglingDetector.samplePeriodMs)
        }
        detector.finishCurrentRun()
        return detector.shapeConsistencyPercent
    }

    func testSHAPE4LabelledRunsScoreExactlyWhatTheReferenceScores() {
        let expected = pinnedEntries("EXPECTED_SHAPE", pattern: #"\("(\d{8}_\d{6})",\s*"([a-z-]+)",\s*(\d+)\)"#)
        XCTAssertEqual(expected.count, 4)
        let failures = expected.compactMap { e -> String? in
            let got = replay(e[0])
            return got == Int(e[2])! ? nil : "\(e[0]) (\(e[1])): reference \(e[2]), swift port \(got)"
        }
        XCTAssertEqual(failures, [])
    }

    private func periodic(_ i: Int) -> [Int] {
        let phase = 2 * Double.pi * Double(i) / 20
        return [
            Int((500 * sin(phase)).rounded()),
            Int((300 * cos(phase)).rounded()),
            1000 + Int((200 * sin(2 * phase)).rounded()),
        ]
    }

    private var lcg: Int64 = 12345

    private func unrelated() -> [Int] {
        var out = (0..<3).map { _ -> Int in
            lcg = (lcg * 75 + 74) % 65537
            return Int(lcg % 1001 - 500)
        }
        out[2] += 1000
        return out
    }

    private func fed(_ sample: (Int) -> [Int], firstCatchMs: Int64 = 0) -> ShapeConsistency {
        let tracker = ShapeConsistency()
        for i in 0..<400 {
            let s = sample(i)
            tracker.addSample(
                x: s[0], y: s[1], z: s[2], nowMs: Int64(i) * 40,
                runActive: true, hasFirstCatch: true, firstCatchMs: firstCatchMs
            )
        }
        return tracker
    }

    func testSHAPE1PeriodicMotionScoresFullAndUnrelatedMotionScoresLow() {
        let periodicTracker = fed(periodic)
        periodicTracker.onCatch(400 * 40)
        periodicTracker.commitRun()
        XCTAssertGreaterThanOrEqual(periodicTracker.sessionPercent(), 99)

        let noise = fed { _ in self.unrelated() }
        noise.onCatch(400 * 40)
        noise.commitRun()
        XCTAssertTrue((0...49).contains(noise.sessionPercent()), "unrelated scored \(noise.sessionPercent())")
    }

    func testSHAPE2OnlyWindowsConfirmedInsideTheRunCount() {
        let unconfirmed = fed(periodic)
        unconfirmed.commitRun()
        XCTAssertEqual(unconfirmed.sessionPercent(), -1)

        let early = fed(periodic, firstCatchMs: Int64(400 - ShapeConsistency.history + 2) * 40)
        early.onCatch(400 * 40)
        early.commitRun()
        XCTAssertEqual(early.sessionPercent(), -1)
    }

    func testSHAPE3DiscardingTheLastRunRemovesItsScore() {
        let tracker = ShapeConsistency()
        for i in 0..<800 {
            let s = i < 400 ? periodic(i) : unrelated()
            tracker.addSample(
                x: s[0], y: s[1], z: s[2], nowMs: Int64(i) * 40,
                runActive: true, hasFirstCatch: true, firstCatchMs: i < 400 ? 0 : 400 * 40
            )
            if i == 399 {
                tracker.onCatch(Int64(i) * 40)
                tracker.commitRun()
            }
        }
        tracker.onCatch(800 * 40)
        tracker.commitRun()
        let mixed = tracker.sessionPercent()
        tracker.discardLastRun()
        XCTAssertGreaterThanOrEqual(tracker.sessionPercent(), 99)
        XCTAssertLessThan(mixed, 99)
    }
}
