import Foundation
import HealthKit
import WatchLogic

/// The workout session that keeps the app running with the wrist down, and
/// the workout it becomes in the Health app (ACT-1).
///
/// watchOS suspends an app soon after the screen turns off; a running workout
/// session keeps it and its accelerometer going, as the Garmin app keeps
/// counting. The runtime starts it while a juggling or record session is
/// open. In Juggle mode `TrackerSession` drives it as its `ActivityRecorder`:
/// paused while the session-end menu is up, and when the session closes saved
/// as an "Other" workout with the ball count and the average catches per run
/// in its metadata, or discarded when no run was completed (ACT-2). Record
/// mode never saves one: stopping discards it.
///
/// A session continued later is a new workout each time it is opened, as on
/// the Garmin (ACT-3): the app leaves its workout when it goes back to the
/// first screen, and watchOS runs one workout at a time, so another workout
/// app in between would end it anyway.
///
/// Without Health permission the session cannot start. The app then still
/// counts while it is on screen, which is how it behaves in the simulator.
final class WorkoutKeeper: NSObject, HKWorkoutSessionDelegate, ActivityRecorder {
    /// Metadata keys of the saved workout.
    static let metadataName = "JugglingTrackerActivity"
    static let metadataBalls = "JugglingTrackerBalls"
    static let metadataAverage = "JugglingTrackerAverageCatchesPerRun"
    static let workoutName = "Juggling session"

    private let store = HKHealthStore()
    private var session: HKWorkoutSession?
    private var builder: HKLiveWorkoutBuilder?
    private var wanted = false
    private var pauseWanted = false

    /// Asks for Health permission up front, so the prompt shows on the mode
    /// screen at launch rather than over a session that has just started.
    /// watchOS shows it only once; later calls return at once.
    func requestAccess() {
        guard HKHealthStore.isHealthDataAvailable() else { return }
        store.requestAuthorization(toShare: [HKObjectType.workoutType()], read: []) { _, _ in }
    }

    /// Starts the workout session, if it is not running already.
    func start() {
        wanted = true
        pauseWanted = false
        guard session == nil, HKHealthStore.isHealthDataAvailable() else { return }
        store.requestAuthorization(toShare: [HKObjectType.workoutType()], read: []) { [weak self] _, _ in
            DispatchQueue.main.async { self?.begin() }
        }
    }

    func pause() {
        pauseWanted = true
        session?.pause()
    }

    func resume() {
        pauseWanted = false
        session?.resume()
    }

    /// Ends the workout and saves it to the Health app.
    func save(balls: Int, average: Double) {
        wanted = false
        guard let session, let builder else { return }
        release()
        let end = Date()
        session.end()
        let metadata: [String: Any] = [
            Self.metadataName: Self.workoutName,
            Self.metadataBalls: balls,
            Self.metadataAverage: (average * 10).rounded() / 10,
        ]
        builder.endCollection(withEnd: end) { _, _ in
            builder.addMetadata(metadata) { _, _ in
                builder.finishWorkout { _, _ in }
            }
        }
    }

    /// Ends the workout without saving it.
    func discard() {
        wanted = false
        guard let session else { return }
        let builder = self.builder
        release()
        session.end()
        builder?.discardWorkout()
    }

    /// Ends the workout session when no session is open any more. A workout
    /// still running here was never saved (Record mode), so it is discarded.
    func stop() {
        wanted = false
        discard()
    }

    private func release() {
        session = nil
        builder = nil
    }

    private func begin() {
        guard wanted, session == nil else { return }
        let configuration = HKWorkoutConfiguration()
        configuration.activityType = .other
        configuration.locationType = .unknown
        guard let session = try? HKWorkoutSession(healthStore: store, configuration: configuration) else { return }
        let builder = session.associatedWorkoutBuilder()
        builder.dataSource = HKLiveWorkoutDataSource(healthStore: store, workoutConfiguration: configuration)
        session.delegate = self
        self.session = session
        self.builder = builder
        let start = Date()
        session.startActivity(with: start)
        builder.beginCollection(withStart: start) { _, _ in }
        if pauseWanted { session.pause() }
    }

    func workoutSession(
        _ workoutSession: HKWorkoutSession,
        didChangeTo toState: HKWorkoutSessionState,
        from fromState: HKWorkoutSessionState,
        date: Date
    ) {}

    func workoutSession(_ workoutSession: HKWorkoutSession, didFailWithError error: Error) {
        DispatchQueue.main.async { [weak self] in
            if self?.session === workoutSession { self?.release() }
        }
    }
}
