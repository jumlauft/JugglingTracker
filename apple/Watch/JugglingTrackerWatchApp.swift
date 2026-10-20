import SwiftUI
import WatchLogic

/// The Apple Watch app: the Garmin watch app (`connectiq/`) for watchOS. The
/// behaviour lives in the WatchLogic package; this target draws it and
/// connects it to the watch's accelerometer, haptics and workout session.
@main
struct JugglingTrackerWatchApp: App {
    var body: some Scene {
        WindowGroup {
            if let shot = WatchScreenshot.current {
                WatchScreenshotView(shot: shot)
            } else {
                MainView()
            }
        }
    }
}

private struct MainView: View {
    @StateObject private var runtime = WatchRuntime()

    var body: some View {
        RootView(runtime: runtime)
    }
}

/// App Store screenshots. Launched with `-screenshot mode|balls|count|stats`,
/// the app shows that screen with made-up numbers and starts nothing else.
/// Only `.github/workflows/apple-screenshots.yml` launches the app this way.
enum WatchScreenshot: String {
    case mode, balls, count, stats

    static var current: WatchScreenshot? {
        let arguments = ProcessInfo.processInfo.arguments
        guard let index = arguments.firstIndex(of: "-screenshot"), index + 1 < arguments.count else { return nil }
        return WatchScreenshot(rawValue: arguments[index + 1])
    }

    /// Mid-session with 3 balls: a run under way after a few good ones.
    static var juggling: TrackerUiState {
        var state = TrackerUiState(ballCount: 3)
        state.runActive = true
        state.currentCount = 27
        state.previousCount = 34
        state.runs = 6
        state.average = 24.5
        state.max = 41
        state.elapsedSeconds = 7 * 60 + 42
        state.shapeConsistency = 84
        return state
    }
}

private struct WatchScreenshotView: View {
    let shot: WatchScreenshot

    var body: some View {
        NavigationStack {
            switch shot {
            case .mode:
                ModeSelectScreen(isRecordMode: false, onUp: {}, onDown: {}, onStart: {})
            case .balls:
                // 5, so the wheel shows a value either side of the chosen one.
                BallSelectScreen(ballCount: 5, onUp: {}, onDown: {}, onStart: {})
                    .toolbar { ToolbarItem(placement: .topBarLeading) { Image(systemName: "chevron.backward") } }
            case .count:
                TrackerScreen(state: WatchScreenshot.juggling, onEnd: {}, onDiscard: {})
            case .stats:
                TrackerScreen(state: WatchScreenshot.juggling, onEnd: {}, onDiscard: {}, initialPage: .stats)
            }
        }
    }
}
