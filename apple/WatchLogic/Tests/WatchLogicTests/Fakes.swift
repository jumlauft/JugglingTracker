import JugglingCore
@testable import WatchLogic

/// Synthetic accelerometer input, the same shapes `connectiq/test/DetectorTest.mc`
/// and the Wear OS tests use: a still watch with gravity on Z, and catch-like
/// impulses against it. Everything is in milli-g at 25 Hz, as the sensor
/// delivers it.
enum Feeds {
    static let milliGPerMs2 = 1000.0 / 9.80665
    static let baselineZ = 1000
    static let periodMs = JugglingDetector.samplePeriodMs

    static func baseline(_ startMs: Int64, _ samples: Int) -> [AccelSample] {
        (0..<samples).map { AccelSample(x: 0, y: 0, z: baselineZ, timeMs: startMs + Int64($0) * periodMs) }
    }

    /// One impulse: three spiked samples, then twelve still ones to commit it.
    static func burst(_ startMs: Int64, amplitudeMs2: Double = 50.0) -> [AccelSample] {
        let spikeZ = baselineZ - Int(amplitudeMs2 * milliGPerMs2)
        return (0..<3).map { AccelSample(x: 0, y: 0, z: spikeZ, timeMs: startMs + Int64($0) * periodMs) } +
            baseline(startMs + 3 * periodMs, 12)
    }

    /// `catches` watch-hand catches need 2 * catches - 1 bursts (DET-8).
    static func catches(_ startMs: Int64, _ catches: Int) -> [AccelSample] {
        var out: [AccelSample] = []
        var t = startMs
        for _ in 0..<(catches * 2 - 1) {
            let b = burst(t)
            out += b
            t = b.last!.timeMs + periodMs
        }
        return out
    }

    static func warmup() -> [AccelSample] { baseline(0, 55) }

    static func next(_ samples: [AccelSample]) -> Int64 { samples.last!.timeMs + periodMs }
}

extension Array {
    /// Splits into consecutive pieces of at most `size`, like Kotlin's `chunked`.
    func chunked(_ size: Int) -> [[Element]] {
        stride(from: 0, to: count, by: size).map { Array(self[$0..<Swift.min($0 + size, count)]) }
    }
}

final class FakeScheduler: Scheduler, MonotonicClock {
    private final class Task: Cancellable {
        let dueMs: Int64
        let seq: Int
        let action: () -> Void
        var cancelled = false

        init(dueMs: Int64, seq: Int, action: @escaping () -> Void) {
            self.dueMs = dueMs
            self.seq = seq
            self.action = action
        }

        func cancel() { cancelled = true }
    }

    private(set) var now: Int64 = 0
    private var seq = 0
    private var tasks: [Task] = []

    func schedule(afterMs delayMs: Int64, _ action: @escaping () -> Void) -> Cancellable {
        let task = Task(dueMs: now + delayMs, seq: seq, action: action)
        seq += 1
        tasks.append(task)
        return task
    }

    func nowMs() -> Int64 { now }

    /// Runs every task that falls due within `ms`, in order.
    func advance(_ ms: Int64) {
        let target = now + ms
        while let next = tasks
            .filter({ !$0.cancelled && $0.dueMs <= target })
            .min(by: { ($0.dueMs, $0.seq) < ($1.dueMs, $1.seq) }) {
            tasks.removeAll { $0 === next }
            now = next.dueMs
            next.action()
        }
        now = target
    }
}

final class FakePhoneLink: PhoneLink {
    private(set) var sent: [[String: Any]] = []
    private var callbacks: [(Bool) -> Void] = []
    private(set) var listener: (([String: Any]) -> Void)?

    /// When set, every send reports this result at once.
    var autoResult: Bool?

    func send(_ payload: [String: Any], onResult: @escaping (Bool) -> Void) {
        sent.append(payload)
        if let auto = autoResult { onResult(auto) } else { callbacks.append(onResult) }
    }

    func setMessageListener(_ listener: (([String: Any]) -> Void)?) {
        self.listener = listener
    }

    /// Reports the result of the send at `index` (default: the latest).
    func complete(_ delivered: Bool, index: Int? = nil) {
        callbacks[index ?? callbacks.count - 1](delivered)
    }

    func ack(_ timestamp: Int64? = nil) {
        listener?(WatchProtocol.ack(timestamp: timestamp))
    }

    var types: [String] { sent.map { $0["type"] as? String ?? "?" } }
}

final class FakeEffects: WatchEffects {
    var vibrations = 0
    var exits = 0
    func vibrate() { vibrations += 1 }
    func exit() { exits += 1 }
}
