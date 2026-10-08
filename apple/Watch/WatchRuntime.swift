import Combine
import Foundation
import os
import WatchLogic

/// The running app: the navigator and sessions from WatchLogic, the
/// accelerometer, the workout session that keeps it running with the wrist
/// down, and the link to the phone. Counterpart of the Wear OS app's
/// `WatchRuntime`. All of it runs on the main queue.
final class WatchRuntime: ObservableObject {
    // Record mode writes a one-line RUN_DATA summary of each confirmed run to
    // the log under this category (REC-4).
    private static let recordingLog = Logger(subsystem: "com.juggling.tracker.watchkitapp", category: "JugglingRecording")

    let navigator: AppNavigator

    /// True when the accelerometer is missing or would not start, so the
    /// tracking screens show an error instead of a count stuck at 0 (SENS-4).
    @Published private(set) var sensorFailed = false

    private let scheduler: MainQueueScheduler
    private let link: PhoneLink
    private let workout = WorkoutKeeper()
    private var motion: MotionSource!
    private var clockTick: Cancellable?

    init() {
        let scheduler = MainQueueScheduler()
        let link = PendingPhoneLink()
        self.scheduler = scheduler
        self.link = link
        let clock = UptimeClock()
        var restart: () -> Void = {}
        let effects = AppleWatchEffects(onExit: { restart() })
        navigator = AppNavigator(
            newTracker: { balls in
                TrackerSession(ballCount: balls, link: link, scheduler: scheduler, clock: clock, effects: effects)
            },
            newRecording: { balls in
                RecordingSession(
                    ballCount: balls, link: link, scheduler: scheduler, effects: effects,
                    log: { WatchRuntime.recordingLog.info("\($0, privacy: .public)") }
                )
            },
            effects: effects
        )
        restart = { [weak self] in self?.navigator.restart() }
        motion = MotionSource { [weak self] batch in self?.onSamples(batch) }
        navigator.onChange = { [weak self] in self?.screenChanged() }
    }

    var screen: Screen { navigator.screen }

    private func screenChanged() {
        objectWillChange.send()
        switch navigator.screen {
        case let .tracker(session):
            session.onChange = { [weak self] in self?.objectWillChange.send() }
        case let .recording(session):
            session.onChange = { [weak self] in self?.objectWillChange.send() }
        case .modeSelect, .ballSelect:
            break
        }

        // The sensor, the workout session and the clock run exactly while a
        // session is open.
        if navigator.screen.isTracking {
            sensorFailed = !motion.start()
            workout.start()
            startClockTick()
        } else {
            motion.stop()
            workout.stop()
            clockTick?.cancel()
            clockTick = nil
        }
    }

    private func onSamples(_ batch: [AccelSample]) {
        switch navigator.screen {
        case let .tracker(session): session.onSamples(batch)
        case let .recording(session): session.onSamples(batch)
        case .modeSelect, .ballSelect: break
        }
    }

    /// Redraws the session time once a second.
    private func startClockTick() {
        clockTick?.cancel()
        clockTick = scheduler.schedule(afterMs: 1_000) { [weak self] in
            guard let self else { return }
            if case let .tracker(session) = navigator.screen { session.tick() }
            if navigator.screen.isTracking { startClockTick() }
        }
    }
}
