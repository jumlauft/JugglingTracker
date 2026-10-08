import JugglingCore

/// Thins the phone's 100 Hz accelerometer to the detector's 25 Hz, keeping
/// one sample per 40 ms slot on a fixed grid. Measuring 40 ms from the last
/// kept sample instead lets timestamp jitter push every slot later and drops
/// about one slot in twenty. Same logic as the Android app's `SampleThrottle`.
struct SampleThrottle {
    let periodMs: Int64
    private var nextDueMs: Int64?

    init(periodMs: Int64 = JugglingDetector.samplePeriodMs) {
        self.periodMs = periodMs
    }

    /// Whether the sample stamped `timeMs` should go to the detector.
    mutating func accept(_ timeMs: Int64) -> Bool {
        if let due = nextDueMs, timeMs < due { return false }
        if let due = nextDueMs, timeMs - due < periodMs {
            nextDueMs = due + periodMs
        } else {
            // First sample, or the stream stalled: restart the grid here.
            nextDueMs = timeMs + periodMs
        }
        return true
    }

    mutating func reset() {
        nextDueMs = nil
    }
}
