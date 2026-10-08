import CoreMotion
import Foundation

/// One phone accelerometer reading in m/s², in the Android sensor's
/// convention, with its sensor timestamp.
struct PhoneAccelSample: Equatable {
    var ax: Double
    var ay: Double
    var az: Double
    var timestampNanos: Int64

    /// CoreMotion reports in g and with the opposite sign to Android (a phone
    /// lying face up reads z = -1 g on iOS, +9.81 m/s² on Android). Flipping
    /// and scaling here means the detector, the raw recordings and the corpus
    /// all see the same numbers from either phone.
    static func fromCoreMotion(x: Double, y: Double, z: Double, timestamp: TimeInterval) -> PhoneAccelSample {
        let g = 9.80665
        return PhoneAccelSample(ax: -x * g, ay: -y * g, az: -z * g, timestampNanos: Int64((timestamp * 1_000_000_000).rounded()))
    }
}

/// Runs the phone accelerometer at 100 Hz (the iPhone's usual maximum) on its
/// own queue and hands the samples to `onBatch` on the main thread about ten
/// times a second, as the Android app's `PhoneAccelerometerSource` does.
final class PhoneAccelerometer {
    static let requestedSampleRate = 100
    private static let flushPeriodNanos: Int64 = 100_000_000

    private let motion = CMMotionManager()
    private let queue: OperationQueue = {
        let queue = OperationQueue()
        queue.maxConcurrentOperationCount = 1
        queue.name = "com.juggling.tracker.accelerometer"
        return queue
    }()
    private var batch: [PhoneAccelSample] = []
    // Bumped on every start and stop, so a batch still on its way to the main
    // thread from an earlier run is dropped.
    private var generation = 0

    var isAvailable: Bool { motion.isAccelerometerAvailable }

    /// Starts delivering samples. Returns false when the phone has no accelerometer.
    @discardableResult
    func start(onBatch: @escaping ([PhoneAccelSample]) -> Void) -> Bool {
        guard motion.isAccelerometerAvailable else { return false }
        stop()
        generation += 1
        let current = generation
        motion.accelerometerUpdateInterval = 1.0 / Double(Self.requestedSampleRate)
        motion.startAccelerometerUpdates(to: queue) { [weak self] data, _ in
            guard let self, let data else { return }
            let sample = PhoneAccelSample.fromCoreMotion(
                x: data.acceleration.x, y: data.acceleration.y, z: data.acceleration.z, timestamp: data.timestamp
            )
            self.batch.append(sample)
            if sample.timestampNanos - self.batch[0].timestampNanos >= Self.flushPeriodNanos {
                let out = self.batch
                self.batch.removeAll(keepingCapacity: true)
                DispatchQueue.main.async {
                    guard self.generation == current else { return }
                    onBatch(out)
                }
            }
        }
        return true
    }

    func stop() {
        generation += 1
        if motion.isAccelerometerActive { motion.stopAccelerometerUpdates() }
        queue.addOperation { [weak self] in self?.batch.removeAll() }
    }
}
