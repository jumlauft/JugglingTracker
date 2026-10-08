import Foundation
import HealthKit

/// Keeps the app running with the wrist down. watchOS suspends an app soon
/// after the screen turns off; a running workout session keeps it and its
/// accelerometer going, as the Garmin app keeps counting. Nothing is saved to
/// the Health app: the session only runs while a juggling or record session
/// is open.
///
/// Without Health permission the session cannot start. The app then still
/// counts while it is on screen, which is how it behaves in the simulator.
final class WorkoutKeeper: NSObject, HKWorkoutSessionDelegate {
    private let store = HKHealthStore()
    private var session: HKWorkoutSession?
    private var wanted = false

    func start() {
        wanted = true
        guard session == nil, HKHealthStore.isHealthDataAvailable() else { return }
        store.requestAuthorization(toShare: [HKObjectType.workoutType()], read: []) { [weak self] _, _ in
            DispatchQueue.main.async { self?.begin() }
        }
    }

    func stop() {
        wanted = false
        session?.end()
        session = nil
    }

    private func begin() {
        guard wanted, session == nil else { return }
        let configuration = HKWorkoutConfiguration()
        configuration.activityType = .other
        configuration.locationType = .unknown
        guard let session = try? HKWorkoutSession(healthStore: store, configuration: configuration) else { return }
        session.delegate = self
        self.session = session
        session.startActivity(with: Date())
    }

    func workoutSession(
        _ workoutSession: HKWorkoutSession,
        didChangeTo toState: HKWorkoutSessionState,
        from fromState: HKWorkoutSessionState,
        date: Date
    ) {}

    func workoutSession(_ workoutSession: HKWorkoutSession, didFailWithError error: Error) {
        DispatchQueue.main.async { [weak self] in
            if self?.session === workoutSession { self?.session = nil }
        }
    }
}
