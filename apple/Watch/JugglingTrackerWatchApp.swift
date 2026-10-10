import SwiftUI

/// The Apple Watch app: the Garmin watch app (`connectiq/`) for watchOS. The
/// behaviour lives in the WatchLogic package; this target draws it and
/// connects it to the watch's accelerometer, haptics and workout session.
@main
struct JugglingTrackerWatchApp: App {
    @StateObject private var runtime = WatchRuntime()

    var body: some Scene {
        WindowGroup {
            RootView(runtime: runtime)
        }
    }
}
