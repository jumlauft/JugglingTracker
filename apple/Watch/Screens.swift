import SwiftUI
import WatchKit
import WatchLogic

/// The Garmin palette the watch app draws with.
enum WatchColors {
    static let green = Color(red: 0, green: 1, blue: 0)
    static let yellow = Color(red: 1, green: 1, blue: 0)
    static let red = Color(red: 1, green: 0, blue: 0)
    static let lightGray = Color(white: 0xAA / 255)
    static let white = Color.white
}

/// Picks the screen, and maps the Garmin's four buttons onto the watch:
///
/// | Garmin | Apple Watch |
/// |---|---|
/// | UP / DOWN | turn the Digital Crown, or tap ▲ / ▼ |
/// | START / STOP | the green button on screen (Start, Stop, Confirm); End on the Juggle controls page |
/// | BACK | the button in the top corner; Discard run on the Juggle controls page |
struct RootView: View {
    @ObservedObject var runtime: WatchRuntime

    var body: some View {
        NavigationStack {
            content
                .toolbar {
                    if let back = backButton {
                        ToolbarItem(placement: .topBarLeading) {
                            Button(action: runtime.navigator.onBack) {
                                Image(systemName: back.symbol)
                            }
                            .accessibilityLabel(back.label)
                        }
                    }
                }
        }
    }

    @ViewBuilder
    private var content: some View {
        let nav = runtime.navigator
        switch runtime.screen {
        case let .modeSelect(isRecordMode):
            ModeSelectScreen(isRecordMode: isRecordMode, onUp: nav.onUp, onDown: nav.onDown, onStart: nav.onStart)
        case let .ballSelect(_, ballCount):
            BallSelectScreen(ballCount: ballCount, onUp: nav.onUp, onDown: nav.onDown, onStart: nav.onStart)
        case let .tracker(session):
            if let menu = session.state.menu {
                MenuScreen(menu: menu, onSelect: session.onMenuSelect)
            } else if runtime.sensorFailed {
                SensorErrorScreen(button: "End", onStart: nav.onStart)
            } else {
                TrackerScreen(state: session.state, onEnd: nav.onStart, onDiscard: nav.onBack)
            }
        case let .recording(session):
            if let menu = session.state.menu {
                MenuScreen(menu: menu, onSelect: session.onMenuSelect)
            } else if runtime.sensorFailed {
                // SENS-4: START does nothing while idle; there is nothing to record.
                SensorErrorScreen(button: session.state.phase == .idle ? nil : "Stop", onStart: nav.onStart)
            } else {
                RecordingScreen(state: session.state, onStart: nav.onStart, onUp: nav.onUp, onDown: nav.onDown)
            }
        }
    }

    /// The first screen has none: pressing the Digital Crown leaves the app.
    /// The Juggle screen has none either: a stray touch there would open a
    /// menu and pause counting, so discarding a run (JUG-3) lives on its
    /// controls page. Over a menu it backs out of the menu (JUG-6, REC-10).
    private var backButton: (symbol: String, label: String)? {
        switch runtime.screen {
        case .modeSelect:
            return nil
        case .ballSelect:
            return ("chevron.backward", "Back")
        case let .tracker(session):
            if session.state.sending || session.state.menu == nil { return nil }
            return ("chevron.backward", "Back")
        case let .recording(session):
            let state = session.state
            if state.menu == nil && state.phase == .syncing { return nil } // REC-9
            return ("chevron.backward", "Back")
        }
    }
}

// MARK: Building blocks

private struct WatchText: View {
    let text: String
    let color: Color
    let size: CGFloat
    var weight: Font.Weight = .regular

    init(_ text: String, _ color: Color, _ size: CGFloat, weight: Font.Weight = .regular) {
        self.text = text
        self.color = color
        self.size = size
        self.weight = weight
    }

    var body: some View {
        Text(text)
            .font(.system(size: size, weight: weight))
            .foregroundStyle(color)
            .multilineTextAlignment(.center)
            // Every line is written to fit the smallest watch; shrink rather
            // than wrap words mid-screen.
            .lineLimit(1)
            .minimumScaleFactor(0.6)
    }
}

/// A screen's text, centred, with an optional START button under it.
private struct CenteredColumn<Content: View>: View {
    let button: String?
    let onStart: () -> Void
    let content: Content
    @Environment(\.isLuminanceReduced) private var isLuminanceReduced

    init(button: String?, onStart: @escaping () -> Void, @ViewBuilder content: () -> Content) {
        self.button = button
        self.onStart = onStart
        self.content = content()
    }

    var body: some View {
        VStack(spacing: 2) {
            Spacer(minLength: 0)
            content
            Spacer(minLength: 0)
            if let button {
                Button(action: onStart) {
                    Text(button).font(.system(size: 15, weight: .bold)).foregroundStyle(.black)
                }
                .tint(WatchColors.green)
                .buttonStyle(.borderedProminent)
                // Hidden with the wrist down, where it would light up the
                // screen; it keeps its space so nothing moves.
                .opacity(isLuminanceReduced ? 0 : 1)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// UP and DOWN either side of the value they change, also driven by the
/// Digital Crown: each detent is one press.
private struct ArrowStepper<Value: View>: View {
    let onDown: () -> Void
    let onUp: () -> Void
    let value: Value
    @State private var crown = 0.0
    @State private var lastDetent = 0

    init(onDown: @escaping () -> Void, onUp: @escaping () -> Void, @ViewBuilder value: () -> Value) {
        self.onDown = onDown
        self.onUp = onUp
        self.value = value()
    }

    var body: some View {
        HStack(spacing: 4) {
            arrow("chevron.down", "Down", onDown)
            value.frame(minWidth: 40)
            arrow("chevron.up", "Up", onUp)
        }
        .focusable()
        .digitalCrownRotation(
            $crown, from: -1_000_000, through: 1_000_000, by: 1,
            sensitivity: .low, isContinuous: true, isHapticFeedbackEnabled: true
        )
        .onChange(of: crown) { _, newValue in
            let detent = Int(newValue.rounded())
            while lastDetent < detent { lastDetent += 1; onUp() }
            while lastDetent > detent { lastDetent -= 1; onDown() }
        }
    }

    private func arrow(_ symbol: String, _ label: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: symbol) }
            .buttonStyle(.bordered)
            .frame(width: 40, height: 40)
            .accessibilityLabel(label)
    }
}

// MARK: Start-up screens

/// `ModeSelectView.mc`: Juggle or Record.
struct ModeSelectScreen: View {
    let isRecordMode: Bool
    let onUp: () -> Void
    let onDown: () -> Void
    let onStart: () -> Void

    var body: some View {
        CenteredColumn(button: "Start", onStart: onStart) {
            WatchText("Mode", WatchColors.green, 14)
            ArrowStepper(onDown: onDown, onUp: onUp) {
                WatchText(isRecordMode ? "Record" : "Juggle", WatchColors.white, 18, weight: .bold)
            }
            WatchText(isRecordMode ? "Save raw sensor data" : "Track catches live", WatchColors.lightGray, 11)
        }
    }
}

/// `BallSelectView.mc`: 3 to 9 balls.
struct BallSelectScreen: View {
    let ballCount: Int
    let onUp: () -> Void
    let onDown: () -> Void
    let onStart: () -> Void

    var body: some View {
        CenteredColumn(button: "Start", onStart: onStart) {
            WatchText("Balls", WatchColors.green, 14)
            ArrowStepper(onDown: onDown, onUp: onUp) {
                WatchText(String(ballCount), WatchColors.green, 40, weight: .bold)
            }
        }
    }
}

// MARK: Juggle

/// `MainView.mc`: run state, live count and session stats (JUG-1), on three
/// pages the way the Workout app lays out a workout:
///
/// - controls (swipe right): End, Discard run and Lock,
/// - the count, which the screen opens on,
/// - the session stats (swipe left).
///
/// Nothing on the count page reacts to a touch. A sleeve, a ball or the heel
/// of the hand brushing the screen mid-juggle would otherwise open a menu,
/// and detection pauses while a menu is up, cutting the run short unnoticed.
struct TrackerScreen: View {
    let state: TrackerUiState
    let onEnd: () -> Void
    let onDiscard: () -> Void

    enum Page: Hashable { case controls, count, stats }

    @State private var page: Page = .count

    var body: some View {
        if state.sending {
            WatchText(Format.syncing(state.syncDots), WatchColors.yellow, 18)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            TabView(selection: $page) {
                TrackerControlsPage(canDiscard: canDiscard, onEnd: onEnd, onDiscard: onDiscard)
                    .tag(Page.controls)
                TrackerCountPage(state: state)
                    .tag(Page.count)
                TrackerStatsPage(state: state)
                    .tag(Page.stats)
            }
            .tabViewStyle(.page)
            // The page stays where it was put between runs, so the stats can
            // be read in peace; a new run brings the count back.
            .onChange(of: state.runActive) { _, active in
                if active { page = .count }
            }
        }
    }

    /// The same test `TrackerSession.onBack` makes: a run in progress or a
    /// finished one to take back.
    private var canDiscard: Bool { state.runActive || state.runs > 0 }
}

/// The count, as large as the screen allows, readable at a glance mid-juggle.
private struct TrackerCountPage: View {
    let state: TrackerUiState

    var body: some View {
        VStack(spacing: 0) {
            WatchText(
                state.runActive ? "RUN ACTIVE" : "WAITING",
                state.runActive ? WatchColors.green : WatchColors.yellow,
                13, weight: .semibold
            )
            Text(String(state.currentCount))
                .font(.system(size: 96, weight: .bold, design: .rounded))
                // Digits of equal width, so the number does not shift as it counts.
                .monospacedDigit()
                .foregroundStyle(WatchColors.green)
                .lineLimit(1)
                .minimumScaleFactor(0.4)
                .frame(maxHeight: .infinity)
            WatchText("Catches per hand", WatchColors.lightGray, 13)
            if let error = state.errorMessage {
                WatchText(error, WatchColors.red, 12)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .combine)
    }
}

/// The session stats, one per line, large enough to read at rest.
private struct TrackerStatsPage: View {
    let state: TrackerUiState

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            row("Prev", Format.countOrDash(state.previousCount))
            row("Runs", String(state.runs))
            row("Avg", Format.averageOrDash(state.average))
            row("Max", Format.countOrDash(state.max))
            row("Time", Format.elapsed(state.elapsedSeconds))
            row("Regularity", Format.percentOrDash(state.shapeConsistency))
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.horizontal, 4)
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).foregroundStyle(WatchColors.lightGray)
            Spacer(minLength: 4)
            Text(value).monospacedDigit().foregroundStyle(WatchColors.white)
        }
        .font(.system(size: 16, weight: .medium))
        .lineLimit(1)
        .minimumScaleFactor(0.7)
        .accessibilityElement(children: .combine)
    }
}

/// End (START/STOP, JUG-2), Discard run (BACK, JUG-3) and Lock, out of reach
/// of a stray touch on the count page.
private struct TrackerControlsPage: View {
    let canDiscard: Bool
    let onEnd: () -> Void
    let onDiscard: () -> Void

    var body: some View {
        VStack(spacing: 6) {
            Button(action: onEnd) {
                Label("End", systemImage: "xmark")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(.black)
                    .frame(maxWidth: .infinity)
            }
            .tint(WatchColors.green)
            .buttonStyle(.borderedProminent)

            Button(action: onDiscard) {
                Label("Discard run", systemImage: "trash")
                    .frame(maxWidth: .infinity)
            }
            .tint(WatchColors.red)
            .buttonStyle(.bordered)
            .disabled(!canDiscard)

            // Water Lock ignores every touch until the Digital Crown is
            // turned. watchOS only allows it while the workout session runs,
            // which needs Health permission (see WorkoutKeeper).
            Button {
                WKInterfaceDevice.current().enableWaterLock()
            } label: {
                Label("Lock screen", systemImage: "drop.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// Shown instead of a tracking screen when the accelerometer is missing or
/// would not start (SENS-4), with the same wording as the Garmin.
struct SensorErrorScreen: View {
    var button: String?
    var onStart: () -> Void = {}

    var body: some View {
        CenteredColumn(button: button, onStart: onStart) {
            WatchText("Sensor error", WatchColors.red, 18)
            WatchText("Restart the app", WatchColors.lightGray, 11)
        }
    }
}

// MARK: Record

/// `RecordingView.mc`: idle, recording, labelling and syncing.
struct RecordingScreen: View {
    let state: RecordingUiState
    let onStart: () -> Void
    let onUp: () -> Void
    let onDown: () -> Void

    private var button: String? {
        switch state.phase {
        case .idle: return "Start"
        case .recording: return "Stop"
        case .labeling: return "Confirm"
        case .syncing: return nil
        }
    }

    var body: some View {
        CenteredColumn(button: button, onStart: onStart) {
            switch state.phase {
            case .idle:
                WatchText("Ready to record", WatchColors.yellow, 13)
                WatchText("Press Start", WatchColors.green, 20)
                if state.runsCompleted > 0 {
                    WatchText("Runs: \(state.runsCompleted)", WatchColors.lightGray, 11)
                }
            case .recording:
                WatchText("RECORDING", WatchColors.red, 13)
                WatchText("\(state.recordedSamples / RecordingSession.sampleRate)s", WatchColors.white, 24)
                WatchText("Press Start when finished", WatchColors.lightGray, 11)
                WatchText("Catches per hand: \(state.liveCount)", WatchColors.lightGray, 11)
            case .labeling:
                WatchText("Auto-detected \(state.detectedCount)", WatchColors.yellow, 11)
                WatchText("catches, watch hand", WatchColors.yellow, 11)
                WatchText("Actual:", WatchColors.lightGray, 11)
                ArrowStepper(onDown: onDown, onUp: onUp) {
                    WatchText(String(state.labelCount), WatchColors.green, 30, weight: .bold)
                }
                WatchText("Back = discard", WatchColors.lightGray, 10)
            case .syncing:
                WatchText(Format.recordingSyncText(state), WatchColors.yellow, 18)
                if let error = state.errorMessage {
                    WatchText(error, WatchColors.red, 11)
                    if let reason = state.failReason {
                        WatchText(reason, WatchColors.red, 11)
                    }
                    WatchText(state.dataPending ? "data: pending" : "data: sent", WatchColors.red, 11)
                }
            }
        }
    }
}

// MARK: Menus

/// A Garmin `Menu2`: a title and a short list of choices, with the app's own
/// English labels (JUG-5).
struct MenuScreen: View {
    let menu: MenuSpec
    let onSelect: (String) -> Void

    var body: some View {
        List {
            Section {
                ForEach(menu.items) { item in
                    Button { onSelect(item.id) } label: {
                        VStack(alignment: .leading) {
                            Text(item.label).lineLimit(1)
                            if let sub = item.subLabel {
                                Text(sub).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                            }
                        }
                    }
                }
            } header: {
                Text(menu.title).foregroundStyle(WatchColors.white).lineLimit(1)
            }
        }
    }
}
