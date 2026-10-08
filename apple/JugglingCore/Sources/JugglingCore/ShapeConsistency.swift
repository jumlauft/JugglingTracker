import Foundation

/// Shape consistency, shown as Regularity: how alike each hand cycle is to the
/// one before it. Swift port of `connectiq/source/ShapeConsistency.mc` and
/// `shared/.../ShapeConsistency.kt`, line for line.
///
/// Every sample is highpassed per axis (the detector's 0.7 Hz filter, which
/// also removes gravity) and kept in a short history. Once a second during a
/// run, the last `window` samples are compared with the `window` samples one
/// cycle earlier, for every lag from `minLag` to `maxLag` (0.36 s to 1.4 s, one
/// hand cycle at 3 to 7+ balls). The score is the best normalised 3-axis
/// correlation over those lags: 1.0 when the wrist repeats exactly the same
/// motion every cycle, near 0 when one cycle says nothing about the next.
///
/// Only windows lying wholly between the run's first and last watch-hand
/// catch count, so the start-up throws and the drop at the end do not. A
/// window is held as pending until a later catch confirms it. The session
/// score weights each run by its windows and is reported as a whole
/// percentage.
///
/// Reference: `simulation/shape_consistency.py`. Keep them in step.
public final class ShapeConsistency {
    public static let samplePeriodMs: Int64 = 40
    public static let window = 50
    public static let minLag = 9
    public static let maxLag = 35
    public static let history = window + maxLag
    public static let scoreEvery = 25
    public static let maxPending = 8

    // Same 0.7 Hz highpass as JugglingDetector.
    static let hpB0 = 0.883002
    static let hpB1 = -1.766004
    static let hpB2 = 0.883002
    static let hpA1 = -1.752268
    static let hpA2 = 0.779739

    private var histX = [Double](repeating: 0, count: history)
    private var histY = [Double](repeating: 0, count: history)
    private var histZ = [Double](repeating: 0, count: history)
    private var head = 0
    private var samples = 0

    // Highpass state per axis: x[n-1], x[n-2], y[n-1], y[n-2].
    private var hpX = [Double](repeating: 0, count: 4)
    private var hpY = [Double](repeating: 0, count: 4)
    private var hpZ = [Double](repeating: 0, count: 4)

    private var pendingEnd: [Int64] = []
    private var pendingScore: [Double] = []

    private var runSum = 0.0
    private var runCount = 0
    private var runSums: [Double] = []
    private var runCounts: [Int] = []

    public init() {}

    private static func highpass(_ s: inout [Double], _ v: Double) -> Double {
        let out = hpB0 * v + hpB1 * s[0] + hpB2 * s[1] - hpA1 * s[2] - hpA2 * s[3]
        s[1] = s[0]
        s[0] = v
        s[3] = s[2]
        s[2] = out
        return out
    }

    /// Feed one raw sample (milli-g); the detector calls this before detection.
    public func addSample(
        x: Int, y: Int, z: Int, nowMs: Int64,
        runActive: Bool, hasFirstCatch: Bool, firstCatchMs: Int64
    ) {
        histX[head] = Self.highpass(&hpX, Double(x))
        histY[head] = Self.highpass(&hpY, Double(y))
        histZ[head] = Self.highpass(&hpZ, Double(z))
        head = (head + 1) % Self.history
        samples += 1

        if samples < Self.history || samples % Self.scoreEvery != 0 { return }
        if !runActive || !hasFirstCatch { return }
        if nowMs - Int64(Self.history - 1) * Self.samplePeriodMs < firstCatchMs { return }
        if pendingEnd.count >= Self.maxPending {
            pendingEnd.removeFirst()
            pendingScore.removeFirst()
        }
        pendingEnd.append(nowMs)
        pendingScore.append(score())
    }

    private func score() -> Double {
        let n = Self.history
        // Newest first: ax[k] is the sample k steps back.
        var ax = [Double](repeating: 0, count: n)
        var ay = [Double](repeating: 0, count: n)
        var az = [Double](repeating: 0, count: n)
        var e = [Double](repeating: 0, count: n)
        for k in 0..<n {
            let i = (head - 1 - k + n) % n
            ax[k] = histX[i]
            ay[k] = histY[i]
            az[k] = histZ[i]
            e[k] = ax[k] * ax[k] + ay[k] * ay[k] + az[k] * az[k]
        }
        var e0 = 0.0
        for k in 0..<Self.window { e0 += e[k] }
        if e0 <= 0.0 { return 0.0 }
        var el = 0.0
        for k in Self.minLag..<(Self.minLag + Self.window) { el += e[k] }
        var best = 0.0
        for lag in Self.minLag...Self.maxLag {
            if lag > Self.minLag { el += e[lag + Self.window - 1] - e[lag - 1] }
            var cross = 0.0
            for k in 0..<Self.window {
                let j = k + lag
                cross += ax[k] * ax[j] + ay[k] * ay[j] + az[k] * az[j]
            }
            let denom = e0 * el
            if denom > 0.0 {
                let c = cross / denom.squareRoot()
                if c > best { best = c }
            }
        }
        return best > 1.0 ? 1.0 : best
    }

    /// A watch-hand catch at `catchMs` confirms every window ending by then.
    public func onCatch(_ catchMs: Int64) {
        while let end = pendingEnd.first, end <= catchMs {
            runSum += pendingScore.removeFirst()
            runCount += 1
            pendingEnd.removeFirst()
        }
    }

    public func commitRun() {
        runSums.append(runSum)
        runCounts.append(runCount)
        clearRun()
    }

    public func clearRun() {
        runSum = 0.0
        runCount = 0
        pendingEnd.removeAll()
        pendingScore.removeAll()
    }

    public func discardLastRun() {
        if !runSums.isEmpty {
            runSums.removeLast()
            runCounts.removeLast()
        }
    }

    /// Whole percentage over the session, or -1 before any window counts.
    public func sessionPercent() -> Int {
        let count = runCounts.reduce(0, +)
        if count == 0 { return -1 }
        return Int((100.0 * runSums.reduce(0.0, +) / Double(count) + 0.5).rounded(.down))
    }
}
