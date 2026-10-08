import Foundation

/// Swift port of `connectiq/source/JugglingDetector.mc` and
/// `shared/.../JugglingDetector.kt`, line for line, used by the Apple Watch app
/// and the iPhone app's phone-in-hand mode.
///
/// Like the Garmin detector it takes raw accelerometer samples in **milli-g
/// including gravity** at 25 Hz and converts them to m/s² on entry, so recorded
/// runs from any watch replay through it unchanged. The phone's own sensor
/// enters at `processSampleMs2`. It removes a low-pass gravity estimate,
/// highpasses the magnitude, turns threshold crossings into candidates, merges
/// nearby candidates into bursts and counts every other committed burst as a
/// catch by the watch hand.
///
/// Times are `Int64` milliseconds throughout: `Int` is 32 bits on the
/// arm64_32 Apple Watches (Series 4 to 8), too small for epoch milliseconds.
///
/// Keep this in sync with `JugglingDetector.mc`, `JugglingDetector.kt` and
/// `simulation/eval_new_watch.py`.
public final class JugglingDetector {
    public static let sampleRate = 25
    public static let samplePeriodMs: Int64 = 1000 / Int64(sampleRate)
    public static let milliGToMs2 = 9.80665 / 1000.0

    /// A run of fewer than this many watch-hand catches is a false start.
    /// Applied in `recordRun`, so a manual stop and an idle timeout are treated
    /// the same way (RUN-1).
    public static let minRunCatches = 3

    static let gravityAlphaIdle = 0.95
    static let gravityAlphaActive = 0.99

    static let refractoryMs3: Int64 = 80
    static let refractoryMs4: Int64 = 80
    static let refractoryMs5: Int64 = 40
    static let refractoryMs6: Int64 = 40
    static let refractoryMs7Plus: Int64 = 160

    static let mergeWindowMs3: Int64 = 160
    static let mergeWindowMs4: Int64 = 160
    static let mergeWindowMs5: Int64 = 160
    static let mergeWindowMs6: Int64 = 120
    static let mergeWindowMs7Plus: Int64 = 80

    public static let autoFinishDelayMs: Int64 = 2000

    // 2nd-order Butterworth highpass, 0.7 Hz at fs = 25 Hz.
    static let hpB0 = 0.883002
    static let hpB1 = -1.766004
    static let hpB2 = 0.883002
    static let hpA1 = -1.752268
    static let hpA2 = 0.779739

    static let hpThreshold3 = 2.0
    static let hpThreshold4 = 3.0
    static let hpThreshold5 = 3.0
    static let hpThreshold6 = 5.0
    static let hpThreshold7Plus = 2.5
    static let hpHysteresis = 0.3

    static let minRawMag3 = 7.0
    static let minRawMag4 = 11.0
    static let minRawMag5 = 13.0
    static let minRawMag6 = 17.0
    static let minRawMag7Plus = 16.0

    /// Samples ignored while the gravity estimate settles (DET-4).
    static let warmupSamples = 25

    public let ballCount: Int
    public let hpThreshold: Double
    public let refractoryMs: Int64
    public let minRawMag: Double
    public let mergeWindowMs: Int64

    public private(set) var currentCount = 0
    public private(set) var previousCount = 0
    public private(set) var sessionMax = 0

    private var gravityX = 0.0
    private var gravityY = 0.0
    private var gravityZ = 9.80665
    private var gravityInitialized = false
    private var samplesSeen = 0

    private var lastCandidateTimeMs: Int64 = 0
    // Last time a burst committed. Auto-finish keys on this, not on raw
    // motion, so wrist movement after a drop cannot hold a run open (DET-9).
    private var lastActiveTimeMs: Int64 = 0

    private var sessionRunCount = 0
    private var sessionTotal = 0
    private var runCatchList: [Int] = []
    private var runDurationList: [Int64] = []

    private var committedBurstCount = 0
    private var hasFirstCatchTime = false
    private var firstCatchTimeMs: Int64 = 0
    private var lastCatchTimeMs: Int64 = 0

    private var hpX1 = 0.0
    private var hpX2 = 0.0
    private var hpY1 = 0.0
    private var hpY2 = 0.0

    private var above = false
    private var peakTimeMs: Int64 = 0
    private var peakRawMag = 0.0
    private var peakFiltered = 0.0

    private var hasPendingPeak = false
    private var pendingPeakTimeMs: Int64 = 0
    private var pendingPeakScore = 0.0
    private var clusterLastCandidateTimeMs: Int64 = 0

    // How alike each hand cycle is to the one before it, per run and session.
    private let shape = ShapeConsistency()

    public init(ballCount: Int) {
        self.ballCount = ballCount
        if ballCount <= 3 {
            hpThreshold = Self.hpThreshold3
            refractoryMs = Self.refractoryMs3
            minRawMag = Self.minRawMag3
            mergeWindowMs = Self.mergeWindowMs3
        } else if ballCount == 4 {
            hpThreshold = Self.hpThreshold4
            refractoryMs = Self.refractoryMs4
            minRawMag = Self.minRawMag4
            mergeWindowMs = Self.mergeWindowMs4
        } else if ballCount == 5 {
            hpThreshold = Self.hpThreshold5
            refractoryMs = Self.refractoryMs5
            minRawMag = Self.minRawMag5
            mergeWindowMs = Self.mergeWindowMs5
        } else if ballCount == 6 {
            // Six throws land harder and closer together than five, so the
            // peak that marks a catch clears a higher bar.
            hpThreshold = Self.hpThreshold6
            refractoryMs = Self.refractoryMs6
            minRawMag = Self.minRawMag6
            mergeWindowMs = Self.mergeWindowMs6
        } else {
            // 7+ is a distinctly faster cadence than 5.
            hpThreshold = Self.hpThreshold7Plus
            refractoryMs = Self.refractoryMs7Plus
            minRawMag = Self.minRawMag7Plus
            mergeWindowMs = Self.mergeWindowMs7Plus
        }
    }

    /// Watch-hand catch counts of every completed run this session, in order.
    public var runCatches: [Int] { runCatchList }

    /// First-to-last watch-hand catch span of every completed run (DET-10).
    public var runDurationsMillis: [Int64] { runDurationList }

    public var sessionRuns: Int { sessionRunCount }

    /// Mean over completed runs, 0.0 before any run completes (RUN-3).
    public var sessionAverage: Double {
        sessionRunCount == 0 ? 0.0 : Double(sessionTotal) / Double(sessionRunCount)
    }

    public var isRunActive: Bool { hasActiveRun() }

    /// Session shape consistency as a whole percentage, or -1 before any run
    /// has lasted long enough to be scored. See `ShapeConsistency`.
    public var shapeConsistencyPercent: Int { shape.sessionPercent() }

    /// Feed one raw accelerometer sample in milli-g at time `nowMs`.
    public func processSample(_ gxMilliG: Int, _ gyMilliG: Int, _ gzMilliG: Int, nowMs: Int64) {
        // Before detection, so it sees every sample, warmup included.
        shape.addSample(
            x: gxMilliG, y: gyMilliG, z: gzMilliG, nowMs: nowMs,
            runActive: hasActiveRun(), hasFirstCatch: hasFirstCatchTime, firstCatchMs: firstCatchTimeMs
        )

        processSampleMs2(
            Double(gxMilliG) * Self.milliGToMs2,
            Double(gyMilliG) * Self.milliGToMs2,
            Double(gzMilliG) * Self.milliGToMs2,
            nowMs: nowMs
        )
    }

    /// Feed one accelerometer sample already in m/s², as the phone's sensor
    /// is converted to. Counts exactly as `processSample` does, but does not
    /// score shape consistency, which needs the raw milli-g samples.
    public func processSampleMs2(_ ax: Double, _ ay: Double, _ az: Double, nowMs: Int64) {
        // Seed gravity from the first real sample so it matches the watch's
        // actual orientation instead of an assumed "down" (DET-2).
        if !gravityInitialized {
            gravityX = ax
            gravityY = ay
            gravityZ = az
            gravityInitialized = true
        } else {
            let alpha = hasActiveRun() ? Self.gravityAlphaActive : Self.gravityAlphaIdle
            gravityX = alpha * gravityX + (1.0 - alpha) * ax
            gravityY = alpha * gravityY + (1.0 - alpha) * ay
            gravityZ = alpha * gravityZ + (1.0 - alpha) * az
        }

        let lx = ax - gravityX
        let ly = ay - gravityY
        let lz = az - gravityZ
        let mag = (lx * lx + ly * ly + lz * lz).squareRoot()

        samplesSeen += 1

        // Every sample goes through the filter, warmup included, so its state
        // tracks the signal and detection begins without a transient.
        let filtered = applyHighpass(mag)

        if samplesSeen <= Self.warmupSamples { return }

        if !above {
            if filtered > hpThreshold {
                above = true
                peakTimeMs = nowMs
                peakFiltered = filtered
                peakRawMag = mag
            }
        } else {
            if filtered > peakFiltered {
                peakFiltered = filtered
                peakTimeMs = nowMs
            }
            if mag > peakRawMag {
                peakRawMag = mag
            }
            if filtered < hpThreshold * Self.hpHysteresis {
                above = false
                // DET-5: refractory period elapsed and the raw gate cleared.
                if peakTimeMs - lastCandidateTimeMs > refractoryMs && peakRawMag > minRawMag {
                    addCandidate(peakTimeMs, score: peakFiltered, nowMs: nowMs)
                    lastCandidateTimeMs = peakTimeMs
                }
            }
        }

        flushPendingPeak(nowMs)
    }

    /// Finishes the run if it has been idle long enough. Returns the finished
    /// run's watch-hand catch count, or 0 if nothing finished.
    @discardableResult
    public func checkAutoFinish(nowMs: Int64) -> Int {
        flushPendingPeak(nowMs)
        if currentCount > 0 && lastActiveTimeMs > 0 && nowMs - lastActiveTimeMs > Self.autoFinishDelayMs {
            let finished = currentCount
            recordRun(finished)
            currentCount = 0
            clearRunDetectionState()
            return finished
        }
        return 0
    }

    /// Ends a run still in progress, committing any pending burst first,
    /// exactly as an idle timeout would (RUN-6). Returns its watch-hand catch
    /// count.
    @discardableResult
    public func finishCurrentRun() -> Int {
        if hasPendingPeak {
            commitPendingPeak(pendingPeakTimeMs)
        }
        if currentCount > 0 {
            let finished = currentCount
            recordRun(finished)
            currentCount = 0
            clearRunDetectionState()
            return finished
        }
        return 0
    }

    /// Drops the run in progress, or else retroactively removes the last
    /// completed run and recomputes the session from the runs that remain
    /// (RUN-4). Returns false when there was nothing to discard (RUN-5).
    @discardableResult
    public func discardLastRun() -> Bool {
        if hasActiveRun() {
            currentCount = 0
            clearRunDetectionState()
            return true
        }

        guard let removed = runCatchList.popLast() else { return false }
        // Remove by position: removing by value would take an earlier run that
        // happens to have the same catch count.
        runDurationList.removeLast()
        shape.discardLastRun()
        sessionRunCount -= 1
        sessionTotal -= removed

        // Max and previous depend on which runs remain, so recompute them.
        sessionMax = runCatchList.max() ?? 0
        previousCount = runCatchList.last ?? 0
        return true
    }

    private func hasActiveRun() -> Bool {
        currentCount > 0 || hasPendingPeak || committedBurstCount > 0
    }

    private func recordRun(_ catches: Int) {
        if catches < Self.minRunCatches {
            // False start: leave previous and every session statistic exactly
            // as they were. The caller still resets currentCount.
            return
        }
        previousCount = catches
        sessionRunCount += 1
        sessionTotal += catches
        if catches > sessionMax { sessionMax = catches }
        runCatchList.append(catches)
        runDurationList.append(currentRunDurationMillis())
        shape.commitRun()
    }

    private func currentRunDurationMillis() -> Int64 {
        if !hasFirstCatchTime || lastCatchTimeMs < firstCatchTimeMs { return 0 }
        return lastCatchTimeMs - firstCatchTimeMs
    }

    private func clearRunDetectionState() {
        lastCandidateTimeMs = 0
        lastActiveTimeMs = 0
        above = false
        peakTimeMs = 0
        peakRawMag = 0.0
        peakFiltered = 0.0
        hasPendingPeak = false
        pendingPeakTimeMs = 0
        pendingPeakScore = 0.0
        clusterLastCandidateTimeMs = 0
        committedBurstCount = 0
        hasFirstCatchTime = false
        firstCatchTimeMs = 0
        lastCatchTimeMs = 0
        shape.clearRun()
    }

    private func commitPendingPeak(_ nowMs: Int64) {
        if !hasPendingPeak { return }
        committedBurstCount += 1
        // DET-8: hands alternate, so only odd-numbered bursts are the watch hand.
        if committedBurstCount % 2 == 1 {
            currentCount += 1
            if !hasFirstCatchTime {
                firstCatchTimeMs = pendingPeakTimeMs
                hasFirstCatchTime = true
            }
            lastCatchTimeMs = pendingPeakTimeMs
            shape.onCatch(lastCatchTimeMs)
        }
        lastActiveTimeMs = nowMs
        hasPendingPeak = false
        pendingPeakTimeMs = 0
        pendingPeakScore = 0.0
        clusterLastCandidateTimeMs = 0
    }

    private func addCandidate(_ candidateTimeMs: Int64, score: Double, nowMs: Int64) {
        if !hasPendingPeak {
            hasPendingPeak = true
            pendingPeakTimeMs = candidateTimeMs
            pendingPeakScore = score
            clusterLastCandidateTimeMs = candidateTimeMs
            return
        }

        // DET-7: candidates inside the merge window are lobes of one catch.
        if candidateTimeMs - clusterLastCandidateTimeMs < mergeWindowMs {
            if score > pendingPeakScore {
                pendingPeakTimeMs = candidateTimeMs
                pendingPeakScore = score
            }
            clusterLastCandidateTimeMs = candidateTimeMs
            return
        }

        commitPendingPeak(nowMs)
        hasPendingPeak = true
        pendingPeakTimeMs = candidateTimeMs
        pendingPeakScore = score
        clusterLastCandidateTimeMs = candidateTimeMs
    }

    private func flushPendingPeak(_ nowMs: Int64) {
        if hasPendingPeak && !above && nowMs - clusterLastCandidateTimeMs >= mergeWindowMs {
            commitPendingPeak(nowMs)
        }
    }

    private func applyHighpass(_ x: Double) -> Double {
        let y = Self.hpB0 * x + Self.hpB1 * hpX1 + Self.hpB2 * hpX2 - Self.hpA1 * hpY1 - Self.hpA2 * hpY2
        hpX2 = hpX1
        hpX1 = x
        hpY2 = hpY1
        hpY1 = y
        return y
    }
}
