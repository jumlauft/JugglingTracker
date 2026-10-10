import JugglingCore

/// Thins a faster sensor stream to the detector's 25 Hz (DET-1). It keeps one
/// sample per 40 ms slot on a fixed grid; measuring 40 ms from the last kept
/// sample instead would drop a sensor running at, say, 30 ms intervals to
/// about 17 Hz.
public struct SampleThrottle {
    private let periodMs: Int64
    private var nextDueMs: Int64?

    public init(periodMs: Int64 = JugglingDetector.samplePeriodMs) {
        self.periodMs = periodMs
    }

    /// Whether the sample stamped `timeMs` should go to the detector.
    public mutating func accept(_ timeMs: Int64) -> Bool {
        if let due = nextDueMs, timeMs < due { return false }
        if let due = nextDueMs, timeMs - due < periodMs {
            nextDueMs = due + periodMs
        } else {
            // First sample, or the stream stalled: restart the grid here.
            nextDueMs = timeMs + periodMs
        }
        return true
    }

    public mutating func reset() {
        nextDueMs = nil
    }
}
