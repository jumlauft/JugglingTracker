import Foundation
@testable import JugglingCore

/// The repository root, found by walking up from this file until
/// `connectiq/data` appears, so the tests read the same recordings and pinned
/// counts as the Kotlin and Python tests.
let repoRoot: URL = {
    var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
    while dir.path != "/" {
        if FileManager.default.fileExists(atPath: dir.appendingPathComponent("connectiq/data").path) {
            return dir
        }
        dir = dir.deletingLastPathComponent()
    }
    fatalError("connectiq/data not found above \(#filePath)")
}()

func readRepoFile(_ relativePath: String) -> String {
    let url = repoRoot.appendingPathComponent(relativePath)
    guard let text = try? String(contentsOf: url, encoding: .utf8) else {
        fatalError("cannot read \(url.path)")
    }
    return text
}

/// The x, y, z milli-g samples of one recording.
func loadSamples(_ relativePath: String) -> [(Int, Int, Int)] {
    readRepoFile(relativePath)
        .components(separatedBy: .newlines)
        .map { $0.trimmingCharacters(in: .whitespaces) }
        .filter { !$0.isEmpty && !$0.hasPrefix("#") && !$0.hasPrefix("x,") }
        .compactMap { line in
            let parts = line.split(separator: ",").prefix(3).map { Int($0.trimmingCharacters(in: .whitespaces)) }
            guard parts.count == 3, let x = parts[0], let y = parts[1], let z = parts[2] else { return nil }
            return (x, y, z)
        }
}

func loadRun(_ runId: String) -> [(Int, Int, Int)] {
    loadSamples("connectiq/data/\(runId).csv")
}

/// The capture groups of every match of `pattern` in the Python list that
/// `simulation/test_detection.py` assigns to `name`, read out of that file
/// rather than copied, so the pinned values cannot drift apart.
func pinnedEntries(_ name: String, pattern: String) -> [[String]] {
    let source = readRepoFile("simulation/test_detection.py")
    guard let start = source.range(of: "\(name) = [") else { fatalError("\(name) not found") }
    let rest = source[start.upperBound...]
    let block = String(rest[..<(rest.range(of: "\n]")?.lowerBound ?? rest.endIndex)])
    let regex = try! NSRegularExpression(pattern: pattern)
    let ns = block as NSString
    return regex.matches(in: block, range: NSRange(location: 0, length: ns.length)).map { match in
        (1..<match.numberOfRanges).map { ns.substring(with: match.range(at: $0)) }
    }
}

/// One raw accelerometer sample in milli-g, as a watch's sensor delivers it.
struct Sample {
    let x: Int
    let y: Int
    let z: Int
    let timeMs: Int64
}

/// Synthetic accelerometer input, the same shapes `connectiq/test/DetectorTest.mc`
/// and `shared/.../Feeds.kt` use: a still watch with gravity on Z, and
/// catch-like impulses against it, in milli-g at 25 Hz.
enum Feeds {
    static let milliGPerMs2 = 1000.0 / 9.80665
    static let baselineZ = 1000
    static let periodMs = JugglingDetector.samplePeriodMs

    static func baseline(_ startMs: Int64, _ samples: Int) -> [Sample] {
        (0..<samples).map { Sample(x: 0, y: 0, z: baselineZ, timeMs: startMs + Int64($0) * periodMs) }
    }

    /// One impulse: three spiked samples, then twelve still ones to commit it.
    static func burst(_ startMs: Int64, amplitudeMs2: Double = 50.0) -> [Sample] {
        let spikeZ = baselineZ - Int(amplitudeMs2 * milliGPerMs2)
        return (0..<3).map { Sample(x: 0, y: 0, z: spikeZ, timeMs: startMs + Int64($0) * periodMs) } +
            baseline(startMs + 3 * periodMs, 12)
    }

    /// `catches` watch-hand catches need 2 * catches - 1 bursts (DET-8).
    static func catches(_ startMs: Int64, _ catches: Int) -> [Sample] {
        var out: [Sample] = []
        var t = startMs
        for _ in 0..<(catches * 2 - 1) {
            let b = burst(t)
            out += b
            t = b.last!.timeMs + periodMs
        }
        return out
    }

    static func warmup() -> [Sample] { baseline(0, 55) }

    static func next(_ samples: [Sample]) -> Int64 { samples.last!.timeMs + periodMs }
}

extension JugglingDetector {
    /// Drives the detector the way the Garmin test helpers do, one sample at a time.
    @discardableResult
    func feed(_ samples: [Sample]) -> Int64 {
        for s in samples {
            processSample(s.x, s.y, s.z, nowMs: s.timeMs)
            checkAutoFinish(nowMs: s.timeMs)
        }
        return Feeds.next(samples)
    }
}
