import Foundation

/// One finished session, from a watch or from a phone recording. Swift
/// counterpart of the Android app's `model/SessionSummary.kt`.
///
/// `timestamp` (epoch milliseconds) is the identity: storage replaces the
/// session it holds under a timestamp when the same one arrives again, so no
/// two stored sessions share one. A watch resends under the same timestamp
/// when it retries, or when the user carried on juggling after an ack went
/// missing and then ended the session again with more runs.
public struct SessionSummary: Equatable, Codable, Sendable {
    public var timestamp: Int64
    public var ballCount: Int
    public var runCount: Int
    public var avgThrows: Double
    public var stdDevThrows: Double
    public var bestRun: Int
    public var totalThrows: Int
    public var runHistory: [Int]
    public var durationSeconds: Int64
    public var runDurationsMillis: [Int64]
    /// How alike each hand cycle was to the one before it (Regularity), as a
    /// whole percent averaged over the session. Nil when the watch sent none:
    /// older watch builds, phone recordings, or no run long enough to score.
    public var shapeConsistency: Int?

    public init(
        timestamp: Int64, ballCount: Int, runCount: Int, avgThrows: Double, stdDevThrows: Double,
        bestRun: Int, totalThrows: Int, runHistory: [Int], durationSeconds: Int64 = 0,
        runDurationsMillis: [Int64] = [], shapeConsistency: Int? = nil
    ) {
        self.timestamp = timestamp
        self.ballCount = ballCount
        self.runCount = runCount
        self.avgThrows = avgThrows
        self.stdDevThrows = stdDevThrows
        self.bestRun = bestRun
        self.totalThrows = totalThrows
        self.runHistory = runHistory
        self.durationSeconds = durationSeconds
        self.runDurationsMillis = runDurationsMillis
        self.shapeConsistency = shapeConsistency
    }

    /// Builds the stored summary of one session from its per-run counts,
    /// which must not be empty.
    public static func summarize(
        timestamp: Int64,
        ballCount: Int,
        runs: [Int],
        durationSeconds: Int64 = 0,
        runDurationsMillis: [Int64] = [],
        shapeConsistency: Int? = nil
    ) -> SessionSummary {
        precondition(!runs.isEmpty, "a session has at least one run")
        let avg = runs.reduce(0.0) { $0 + Double($1) } / Double(runs.count)
        let stdDev: Double
        if runs.count > 1 {
            let squares = runs.reduce(0.0) { $0 + (Double($1) - avg) * (Double($1) - avg) }
            stdDev = (squares / Double(runs.count)).squareRoot()
        } else {
            stdDev = 0.0
        }
        return SessionSummary(
            timestamp: timestamp,
            ballCount: ballCount,
            runCount: runs.count,
            avgThrows: avg,
            stdDevThrows: stdDev,
            bestRun: runs.max() ?? 0,
            totalThrows: runs.reduce(0, +),
            runHistory: runs,
            durationSeconds: durationSeconds,
            runDurationsMillis: normalizeRunDurations(runCount: runs.count, runDurationsMillis),
            shapeConsistency: shapeConsistency.flatMap { (0...100).contains($0) ? $0 : nil }
        )
    }

    /// Forces one duration per run: extra entries dropped, missing ones
    /// zero-filled, negatives clamped. A watch can send a `runDurationsMillis`
    /// list that disagrees with `runs`.
    public static func normalizeRunDurations(runCount: Int, _ runDurationsMillis: [Int64]) -> [Int64] {
        let sanitized = runDurationsMillis.prefix(runCount).map { max($0, 0) }
        return sanitized + Array(repeating: 0, count: runCount - sanitized.count)
    }

    /// The session a watch's `session` payload carries, or nil when it holds
    /// none. Payload shape: { type: "session", countMode: "watch_hand",
    /// balls, timestamp (epoch s), durationSeconds, runDurationsMillis, runs,
    /// shapeConsistency (optional whole percent) }. Same rules as the Android
    /// app's `WatchInbox.importSession`.
    public static func fromWatchPayload(_ payload: [String: Any]) -> SessionSummary? {
        guard let balls = WatchProtocol.int(payload["balls"]),
              let seconds = WatchProtocol.int64(payload["timestamp"]),
              let runs = WatchProtocol.intList(payload["runs"]), !runs.isEmpty
        else { return nil }
        let durationSeconds = max(WatchProtocol.int64(payload["durationSeconds"]) ?? 0, 0)
        let durations = (WatchProtocol.int64List(payload["runDurationsMillis"]) ?? []).map { max($0, 0) }
        // The watch sends a whole percent; anything outside 0...100 is not one.
        let shape = WatchProtocol.int(payload["shapeConsistency"]).flatMap { (0...100).contains($0) ? $0 : nil }
        return summarize(
            timestamp: seconds * 1000,
            ballCount: balls,
            runs: runs,
            durationSeconds: durationSeconds,
            runDurationsMillis: durations,
            shapeConsistency: shape
        )
    }
}
