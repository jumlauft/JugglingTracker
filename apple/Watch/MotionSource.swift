import CoreMotion
import Foundation
import WatchLogic

/// The accelerometer, as the Garmin's `Sensor.registerSensorDataListener`
/// delivers it to the detector: milli-g including gravity at 25 Hz (DET-1),
/// in batches of about one second.
///
/// CoreMotion reports in g. It is asked for 50 Hz and thinned to 25 Hz on a
/// fixed 40 ms grid, because asking for 25 Hz directly gives samples a little
/// early or late, and the grid would then drop every other one.
final class MotionSource {
    static let requestedHz = 50.0
    static let batchSize = 25

    private let manager = CMMotionManager()
    private let queue: OperationQueue = {
        let queue = OperationQueue()
        queue.name = "JugglingTracker.motion"
        queue.maxConcurrentOperationCount = 1
        return queue
    }()
    private let onBatch: ([AccelSample]) -> Void

    // Touched only on `queue`.
    private var throttle = SampleThrottle()
    private var batch: [AccelSample] = []
    private var startTimestamp: TimeInterval?

    init(onBatch: @escaping ([AccelSample]) -> Void) {
        self.onBatch = onBatch
    }

    var isAvailable: Bool { manager.isAccelerometerAvailable }
    var isRunning: Bool { manager.isAccelerometerActive }

    /// Starts the sensor, unless it already runs (SENS-2). Returns false when
    /// the watch has no accelerometer (SENS-4).
    @discardableResult
    func start() -> Bool {
        guard manager.isAccelerometerAvailable else { return false }
        if manager.isAccelerometerActive { return true }
        queue.addOperation { [self] in
            throttle.reset()
            batch.removeAll()
            startTimestamp = nil
        }
        manager.accelerometerUpdateInterval = 1.0 / Self.requestedHz
        manager.startAccelerometerUpdates(to: queue) { [weak self] data, _ in
            guard let self, let data else { return } // SENS-3: skip missing samples
            receive(data)
        }
        return true
    }

    func stop() {
        manager.stopAccelerometerUpdates()
        queue.addOperation { [self] in flush() }
    }

    private func receive(_ data: CMAccelerometerData) {
        // SENS-5: times count from when the sensor started, not since boot.
        let start = startTimestamp ?? data.timestamp
        startTimestamp = start
        let timeMs = Int64(((data.timestamp - start) * 1000).rounded())
        guard throttle.accept(timeMs) else { return }
        let a = data.acceleration
        batch.append(AccelSample(x: milliG(a.x), y: milliG(a.y), z: milliG(a.z), timeMs: timeMs))
        if batch.count >= Self.batchSize { flush() }
    }

    private func flush() {
        guard !batch.isEmpty else { return }
        let ready = batch
        batch.removeAll(keepingCapacity: true)
        DispatchQueue.main.async { [onBatch] in onBatch(ready) }
    }

    private func milliG(_ g: Double) -> Int {
        guard g.isFinite else { return 0 }
        return Int((g * 1000).rounded())
    }
}
