import Foundation
import WatchKit
import WatchLogic

/// Timers on the main queue.
final class MainQueueScheduler: Scheduler {
    private final class Task: Cancellable {
        let item: DispatchWorkItem
        init(_ item: DispatchWorkItem) { self.item = item }
        func cancel() { item.cancel() }
    }

    func schedule(afterMs delayMs: Int64, _ action: @escaping () -> Void) -> Cancellable {
        let item = DispatchWorkItem(block: action)
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(delayMs)), execute: item)
        return Task(item)
    }
}

/// Milliseconds since boot, which keeps counting while the watch sleeps.
struct UptimeClock: MonotonicClock {
    func nowMs() -> Int64 {
        Int64(ProcessInfo.processInfo.systemUptime * 1000)
    }
}

/// Buzz and quit. The watch has no way for an app to close itself, so where
/// the Garmin app quits (after a sync, or on Quit) this one ends the session
/// and starts over at the first screen.
final class AppleWatchEffects: WatchEffects {
    private let onExit: () -> Void

    init(onExit: @escaping () -> Void) {
        self.onExit = onExit
    }

    func vibrate() {
        WKInterfaceDevice.current().play(.notification)
    }

    func exit() {
        // Leave the session's own call stack before tearing it down.
        DispatchQueue.main.async(execute: onExit)
    }
}

/// The link to the iPhone app, not built yet: WatchConnectivity comes in its
/// own change. Until then every message reports that it did not leave the
/// watch, so ending a session shows "Sync failed" with Retry, Quit without
/// sync and Continue, exactly as a Garmin out of reach of its phone does.
final class PendingPhoneLink: PhoneLink {
    func send(_ payload: [String: Any], onResult: @escaping (Bool) -> Void) {
        DispatchQueue.main.async { onResult(false) }
    }

    func setMessageListener(_ listener: (([String: Any]) -> Void)?) {}
}
