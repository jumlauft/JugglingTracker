import Foundation

public enum TrackingMode: Equatable, Sendable {
    case juggle, record
}

/// The screen currently showing.
public enum Screen: Equatable {
    /// APP-1: Juggle or Record, defaulting to Juggle.
    case modeSelect(isRecordMode: Bool)
    /// APP-3: 3 to 9 balls, opening on 3.
    case ballSelect(mode: TrackingMode, ballCount: Int)
    case tracker(TrackerSession)
    case recording(RecordingSession)

    public static let minBalls = 3
    public static let maxBalls = 9

    /// Whether a juggling or record session is open.
    public var isTracking: Bool {
        switch self {
        case .tracker, .recording: return true
        case .modeSelect, .ballSelect: return false
        }
    }

    public static func == (lhs: Screen, rhs: Screen) -> Bool {
        switch (lhs, rhs) {
        case let (.modeSelect(a), .modeSelect(b)): return a == b
        case let (.ballSelect(m1, b1), .ballSelect(m2, b2)): return m1 == m2 && b1 == b2
        case let (.tracker(a), .tracker(b)): return a === b
        case let (.recording(a), .recording(b)): return a === b
        default: return false
        }
    }
}

/// The start-up flow of `JugglingTrackerApp.mc`, `ModeSelectView.mc` and
/// `BallSelectView.mc`, plus routing of the four Garmin buttons to whichever
/// screen is showing. Ported from `wearos/.../AppNavigator.kt`. UP and DOWN
/// come from the Digital Crown or the on-screen arrows, START from the
/// on-screen button, BACK from the back button on the screen's corner.
public final class AppNavigator {
    /// Record mode ships enabled on purpose, as on the Garmin (APP-1): it is
    /// how the labelled corpus grows. A test pins this value so turning it off
    /// is a deliberate decision.
    public static let enableRecordingMode = true

    private let newTracker: (Int) -> TrackerSession
    private let newRecording: (Int) -> RecordingSession
    private let effects: WatchEffects
    private let recordingModeEnabled: Bool

    public private(set) var screen: Screen {
        didSet { onChange?() }
    }

    /// Called after every change to `screen`.
    public var onChange: (() -> Void)?

    public init(
        newTracker: @escaping (Int) -> TrackerSession,
        newRecording: @escaping (Int) -> RecordingSession,
        effects: WatchEffects,
        enableRecordingMode: Bool = AppNavigator.enableRecordingMode
    ) {
        self.newTracker = newTracker
        self.newRecording = newRecording
        self.effects = effects
        recordingModeEnabled = enableRecordingMode
        screen = enableRecordingMode
            ? .modeSelect(isRecordMode: false)
            : .ballSelect(mode: .juggle, ballCount: Screen.minBalls)
    }

    /// UP. On mode select both UP and DOWN toggle (APP-2).
    public func onUp() {
        switch screen {
        case let .modeSelect(isRecord): screen = .modeSelect(isRecordMode: !isRecord)
        case let .ballSelect(mode, balls): screen = .ballSelect(mode: mode, ballCount: increment(balls))
        case let .recording(session): session.onUp()
        case .tracker: break
        }
    }

    /// DOWN.
    public func onDown() {
        switch screen {
        case let .modeSelect(isRecord): screen = .modeSelect(isRecordMode: !isRecord)
        case let .ballSelect(mode, balls): screen = .ballSelect(mode: mode, ballCount: decrement(balls))
        case let .recording(session): session.onDown()
        case .tracker: break
        }
    }

    /// START.
    public func onStart() {
        switch screen {
        case let .modeSelect(isRecord):
            screen = .ballSelect(mode: isRecord ? .record : .juggle, ballCount: Screen.minBalls)
        // APP-4: open the tracker for the mode chosen earlier.
        case let .ballSelect(mode, balls):
            switch mode {
            case .juggle: screen = .tracker(newTracker(balls))
            case .record: screen = .recording(newRecording(balls))
            }
        case let .tracker(session): session.onStartStop()
        case let .recording(session): session.onStart()
        }
    }

    /// BACK steps back one screen (APP-5): ball selection returns to the mode
    /// screen, and the idle record screen returns to ball selection. The first
    /// screen leaves the app; the tracking screens otherwise never let it
    /// through.
    public func onBack() {
        switch screen {
        case .modeSelect:
            effects.exit()
        case let .ballSelect(mode, _):
            if recordingModeEnabled {
                screen = .modeSelect(isRecordMode: mode == .record)
            } else {
                effects.exit()
            }
        case let .tracker(session):
            if session.state.menu != nil { session.onMenuBack() } else { session.onBack() }
        case let .recording(session):
            let state = session.state
            if state.menu != nil {
                session.onMenuBack()
            } else if session.onBack() {
                screen = .ballSelect(mode: .record, ballCount: state.ballCount)
            }
        }
    }

    /// Ends the open session and returns to the first screen. The watch has
    /// no way to close an app, so where the Garmin quits after a sync or on
    /// "Quit", the Apple Watch app starts over here instead.
    public func restart() {
        screen = recordingModeEnabled
            ? .modeSelect(isRecordMode: false)
            : .ballSelect(mode: .juggle, ballCount: Screen.minBalls)
    }

    /// APP-3: UP walks 3..9 and wraps from 9 to 3.
    private func increment(_ balls: Int) -> Int { balls < Screen.maxBalls ? balls + 1 : Screen.minBalls }

    /// DOWN walks 9..3 and wraps from 3 to 9.
    private func decrement(_ balls: Int) -> Int { balls > Screen.minBalls ? balls - 1 : Screen.maxBalls }
}
