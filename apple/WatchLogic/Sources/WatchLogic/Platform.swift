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

/// The watch's own activity for the stretch being juggled (ACT-1, ACT-2): a
/// workout in the Health app, the counterpart of the Garmin's Garmin Connect
/// activity. `TrackerSession` starts it when juggling starts, pauses it while
/// the session-end menu is up and ends it when the session closes: saved
/// with the stretch's stats when the stretch has a run, else discarded.
public protocol ActivityRecorder: AnyObject {
    func start()
    func pause()
    func resume()
    /// `average` is the stretch's catches per run.
    func save(balls: Int, average: Double)
    func discard()
}

/// Records nothing.
public final class NoActivityRecorder: ActivityRecorder {
    public init() {}
    public func start() {}
    public func pause() {}
    public func resume() {}
    public func save(balls: Int, average: Double) {}
    public func discard() {}
}

/// Where a session kept with Continue later waits for the app to open again (LATER-1).
public protocol SessionStore: AnyObject {
    func load() -> KeptSession?
    func save(_ session: KeptSession)
    func clear()
}

/// Keeps nothing, for sessions that never continue later.
public final class NoSessionStore: SessionStore {
    public init() {}
    public func load() -> KeptSession? { nil }
    public func save(_ session: KeptSession) {}
    public func clear() {}
}

/// A session kept with Continue later (LATER-1).
public struct KeptSession: Equatable, Codable, Sendable {
    public var balls: Int
    public var elapsedSeconds: Int64
    public var runs: [Int]
    public var runDurationsMillis: [Int64]
    public var shapeSums: [Double]
    public var shapeCounts: [Int]
    /// The phone timestamp once the session has synced (SYNC-5).
    public var timestamp: Int64?

    public init(
        balls: Int, elapsedSeconds: Int64, runs: [Int], runDurationsMillis: [Int64],
        shapeSums: [Double], shapeCounts: [Int], timestamp: Int64? = nil
    ) {
        self.balls = balls
        self.elapsedSeconds = elapsedSeconds
        self.runs = runs
        self.runDurationsMillis = runDurationsMillis
        self.shapeSums = shapeSums
        self.shapeCounts = shapeCounts
        self.timestamp = timestamp
    }
}

/// A kept session in `UserDefaults`, so it survives the app closing and the
/// watch restarting (LATER-1).
public final class DefaultsSessionStore: SessionStore {
    private let defaults: UserDefaults
    private let key = "kept_session"

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public func load() -> KeptSession? {
        guard let data = defaults.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(KeptSession.self, from: data)
    }

    public func save(_ session: KeptSession) {
        if let data = try? JSONEncoder().encode(session) { defaults.set(data, forKey: key) }
    }

    public func clear() {
        defaults.removeObject(forKey: key)
    }
}
