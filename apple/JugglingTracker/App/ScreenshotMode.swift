import Foundation
import JugglingCore

/// App Store screenshots. Launched with `-screenshots`, the app shows a made-up
/// history in place of the user's, so the trend graph has something to show
/// for several ball counts; nothing is read from or written to the real
/// history or settings, and no watch link starts. Only
/// `.github/workflows/apple-screenshots.yml` launches the app this way.
///
/// - `-screenshotBalls N` selects the N-balls chip.
/// - `-screenshotDetails` opens the newest session of that ball count.
/// - `-screenshotWatch apple` shows the Apple Watch card instead of Garmin's.
enum ScreenshotMode {
    static var isOn: Bool { arguments.contains("-screenshots") }

    static var ballCount: Int? { value(after: "-screenshotBalls").flatMap(Int.init) }

    static var showsDetails: Bool { arguments.contains("-screenshotDetails") }

    static var watchType: WatchType { value(after: "-screenshotWatch") == "apple" ? .appleWatch : .garmin }

    private static var arguments: [String] { ProcessInfo.processInfo.arguments }

    private static func value(after flag: String) -> String? {
        guard let index = arguments.firstIndex(of: flag), index + 1 < arguments.count else { return nil }
        return arguments[index + 1]
    }

    /// A store in a scratch file, filled with `demoSessions`.
    static func sessionStore(now: Date = Date()) -> SessionStore {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("screenshot-sessions.jsonl")
        try? FileManager.default.removeItem(at: url)
        let store = SessionStore(fileURL: url)
        for session in demoSessions(now: now) { store.store(session) }
        return store
    }

    /// Settings that live only for this launch.
    static func settings() -> AppSettings {
        let name = "com.juggling.tracker.screenshots"
        UserDefaults().removePersistentDomain(forName: name)
        let settings = AppSettings(defaults: UserDefaults(suiteName: name) ?? .standard)
        settings.watchType = watchType
        return settings
    }

    /// Three months of practice: 3 balls getting steady, 4 balls coming
    /// along, 5 balls just started. Watch-hand counts, as the watch reports
    /// them. The same every time, so retakes match.
    static func demoSessions(now: Date) -> [SessionSummary] {
        struct Plan {
            let balls: Int
            let sessions: Int
            let firstDaysAgo: Double
            let averageFrom: Double
            let averageTo: Double
            let catchMs: Int64
        }
        let plans = [
            Plan(balls: 3, sessions: 16, firstDaysAgo: 88, averageFrom: 9, averageTo: 38, catchMs: 500),
            Plan(balls: 4, sessions: 12, firstDaysAgo: 62, averageFrom: 4, averageTo: 16, catchMs: 560),
            Plan(balls: 5, sessions: 9, firstDaysAgo: 34, averageFrom: 2, averageTo: 8, catchMs: 620),
        ]
        var random = SeededRandom(seed: 2026)
        var result: [SessionSummary] = []
        for plan in plans {
            for i in 0..<plan.sessions {
                let progress = Double(i) / Double(plan.sessions - 1)
                // Quick gains first, then a slower climb.
                let curve = 1 - (1 - progress) * (1 - progress)
                let mean = (plan.averageFrom + (plan.averageTo - plan.averageFrom) * curve) * random.next(in: 0.85...1.15)
                let runs = (0..<Int(random.next(in: 8...16))).map { _ in
                    max(1, Int((mean * random.next(in: 0.35...1.65)).rounded()))
                }
                let durations = runs.map { Int64($0) * plan.catchMs + Int64(random.next(in: -150...150)) }
                // An evening practice, from yesterday back to the first day.
                let daysAgo = max(1, Int((plan.firstDaysAgo * (1 - progress)).rounded()))
                let day = Calendar.current.startOfDay(for: now).addingTimeInterval(-Double(daysAgo) * 86_400)
                let timestamp = day.addingTimeInterval(random.next(in: 17.5...20.5) * 3_600)
                result.append(SessionSummary.summarize(
                    timestamp: Int64(timestamp.timeIntervalSince1970 * 1000),
                    ballCount: plan.balls,
                    runs: runs,
                    durationSeconds: durations.reduce(0, +) / 1000 + Int64(runs.count * 25),
                    runDurationsMillis: durations,
                    shapeConsistency: Int(58 + 28 * progress + random.next(in: -4...4))
                ))
            }
        }
        return result
    }
}

/// SplitMix64, so the made-up history is the same on every run.
private struct SeededRandom {
    private var state: UInt64

    init(seed: UInt64) { state = seed }

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }

    mutating func next(in range: ClosedRange<Double>) -> Double {
        let unit = Double(next() >> 11) / Double(1 << 53)
        return range.lowerBound + (range.upperBound - range.lowerBound) * unit
    }
}
