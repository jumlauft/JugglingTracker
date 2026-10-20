import Foundation
import WatchLogic

/// Promo videos and demos in the simulator, which has no accelerometer.
/// Launched with `-replay <path to a recording .csv>`, the app feeds that
/// recording to the session at 25 Hz instead of the sensor, in batches of
/// five samples every 0.2 s rather than `MotionSource`'s one-second batches,
/// so the count on screen goes up one catch at a time. With `-replayBalls <n>` as well, the app
/// steps through Juggle, n balls and Start by itself, a second apart, so the
/// whole flow can be filmed without touching the screen.
final class ReplaySource {
    static var ballCount: Int? {
        let arguments = ProcessInfo.processInfo.arguments
        guard let index = arguments.firstIndex(of: "-replayBalls"), index + 1 < arguments.count else { return nil }
        return Int(arguments[index + 1])
    }

    static var path: String? {
        let arguments = ProcessInfo.processInfo.arguments
        guard let index = arguments.firstIndex(of: "-replay"), index + 1 < arguments.count else { return nil }
        return arguments[index + 1]
    }

    private let samples: [AccelSample]
    private let onBatch: ([AccelSample]) -> Void
    private static let batchSize = 5
    private var timer: Timer?
    private var next = 0

    /// Nil when the file cannot be read.
    init?(path: String, onBatch: @escaping ([AccelSample]) -> Void) {
        guard let text = try? String(contentsOfFile: path, encoding: .utf8) else { return nil }
        // A recording: a `# run=...` header, an `x,y,z` line, then milli-g rows.
        var samples: [AccelSample] = []
        for line in text.split(whereSeparator: \.isNewline) {
            let fields = line.split(separator: ",").compactMap { Int($0.trimmingCharacters(in: .whitespaces)) }
            guard fields.count == 3 else { continue }
            samples.append(AccelSample(x: fields[0], y: fields[1], z: fields[2], timeMs: Int64(samples.count) * 40))
        }
        self.samples = samples
        self.onBatch = onBatch
    }

    var isRunning: Bool { timer != nil }

    func start() {
        guard timer == nil else { return }
        next = 0
        timer = Timer.scheduledTimer(withTimeInterval: Double(Self.batchSize) / 25, repeats: true) { [weak self] _ in self?.feed() }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    private func feed() {
        guard next < samples.count else { return stop() }
        let end = min(next + Self.batchSize, samples.count)
        onBatch(Array(samples[next..<end]))
        next = end
    }
}
