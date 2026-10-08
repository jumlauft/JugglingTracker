import Foundation

/// A pending timer that can be stopped, like Garmin's `Timer.Timer`.
public protocol Cancellable {
    func cancel()
}

/// Runs actions later on the main thread. The sessions take this instead of
/// `Timer` so their timeouts can be driven by a fake clock in tests.
public protocol Scheduler {
    func schedule(afterMs delayMs: Int64, _ action: @escaping () -> Void) -> Cancellable
}

/// Monotonic milliseconds, like Garmin's `System.getTimer()`.
public protocol MonotonicClock {
    func nowMs() -> Int64
}

/// The transport to the phone app, standing in for Garmin's
/// `Communications.transmit` / `registerForPhoneAppMessages`.
///
/// `send` reports once whether the message left the watch, like a
/// ConnectionListener's onComplete / onError. That is delivery only: a session
/// counts as synced only when the phone answers with an `ack` through the
/// message listener.
public protocol PhoneLink: AnyObject {
    func send(_ payload: [String: Any], onResult: @escaping (_ delivered: Bool) -> Void)
    func setMessageListener(_ listener: (([String: Any]) -> Void)?)
}

/// The watch-side effects a session has besides drawing: buzz and quit.
public protocol WatchEffects: AnyObject {
    func vibrate()
    /// Close the session and go back to the start, like `System.exit()` on the Garmin.
    func exit()
}

/// One accelerometer reading in milli-g including gravity, stamped in ms.
public struct AccelSample: Equatable, Sendable {
    public let x: Int
    public let y: Int
    public let z: Int
    public let timeMs: Int64

    public init(x: Int, y: Int, z: Int, timeMs: Int64) {
        self.x = x
        self.y = y
        self.z = z
        self.timeMs = timeMs
    }
}

/// A list of choices shown over a tracking screen, the counterpart of a Garmin
/// `Menu2`. Labels are the app's own English strings (JUG-5).
public struct MenuSpec: Equatable, Sendable {
    public let kind: String
    public let title: String
    public let items: [MenuItemSpec]
}

public struct MenuItemSpec: Equatable, Sendable, Identifiable {
    public let id: String
    public let label: String
    public let subLabel: String?

    public init(id: String, label: String, subLabel: String? = nil) {
        self.id = id
        self.label = label
        self.subLabel = subLabel
    }
}
