import Foundation
import JugglingCore
import XCTest
@testable import WatchLogic

/// Replays the shared watch scenarios in `connectiq/scenarios` through the
/// whole Apple Watch logic layer: the navigator, both sessions, a fake phone,
/// a fake clock and fake effects. The Wear OS `ScenarioTest` replays the same
/// files, so a workflow that drifts on one watch fails here or there. The
/// format is described in `connectiq/scenarios/README.md`.
final class ScenarioTests: XCTestCase {
    private func scenarioDirectory() -> URL {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while dir.path != "/" {
            let candidate = dir.appendingPathComponent("connectiq/scenarios")
            if FileManager.default.fileExists(atPath: candidate.path) { return candidate }
            dir = dir.deletingLastPathComponent()
        }
        fatalError("connectiq/scenarios not found above \(#filePath)")
    }

    func testEverySharedWatchScenarioPasses() throws {
        let dir = scenarioDirectory()
        let files = try FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "json" }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        XCTAssertFalse(files.isEmpty, "no scenarios found")
        var failures: [String] = []
        var ran = 0
        for file in files {
            let scenario = try XCTUnwrap(
                JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any]
            )
            if let platforms = scenario["platforms"] as? [String], !platforms.contains("apple") { continue }
            ran += 1
            if let failure = ScenarioRun(scenario).run() {
                failures.append("\(file.lastPathComponent): \(failure)")
            }
        }
        XCTAssertTrue(failures.isEmpty, failures.joined(separator: "\n"))
        XCTAssertGreaterThan(ran, 0, "no scenario ran")
    }
}

/// A JSON or session value in one comparable form.
private indirect enum Value: Equatable, CustomStringConvertible {
    case null
    case bool(Bool)
    case int(Int64)
    case double(Double)
    case string(String)
    case list([Value])

    init(_ any: Any?) {
        switch any {
        case nil, is NSNull: self = .null
        case let n as NSNumber where String(cString: n.objCType) == "c": self = .bool(n.boolValue)
        case let b as Bool: self = .bool(b)
        case let i as Int: self = .int(Int64(i))
        case let i as Int64: self = .int(i)
        case let i as Int32: self = .int(Int64(i))
        case let d as Double: self = d == d.rounded() ? .int(Int64(d)) : .double(d)
        case let n as NSNumber: self = .int(n.int64Value)
        case let s as String: self = .string(s)
        case let list as [Any]: self = .list(list.map { Value($0) })
        default: self = .string(String(describing: any!))
        }
    }

    var description: String {
        switch self {
        case .null: "null"
        case let .bool(b): "\(b)"
        case let .int(i): "\(i)"
        case let .double(d): "\(d)"
        case let .string(s): "\"\(s)\""
        case let .list(l): "[" + l.map(\.description).joined(separator: ", ") + "]"
        }
    }
}

/// One scenario, run against fresh fakes. `run` returns the first failure, or nil.
private final class ScenarioRun {
    private let scenario: [String: Any]
    private let scheduler = FakeScheduler()
    private let link = FakePhoneLink()
    private let effects = FakeEffects()
    private var epoch: Int64
    private var t: Int64 = 0
    private var navigator: AppNavigator!

    init(_ scenario: [String: Any]) {
        self.scenario = scenario
        epoch = WatchProtocol.int64(scenario["epochSeconds"]) ?? 1_700_000_000
        navigator = AppNavigator(
            newTracker: { [unowned self] balls in
                TrackerSession(
                    ballCount: balls, link: self.link, scheduler: self.scheduler, clock: self.scheduler,
                    effects: self.effects, epochSeconds: { [unowned self] in self.epoch }
                )
            },
            newRecording: { [unowned self] balls in
                RecordingSession(
                    ballCount: balls, link: self.link, scheduler: self.scheduler, effects: self.effects,
                    epochSeconds: { [unowned self] in self.epoch }
                )
            },
            effects: effects
        )
    }

    private struct Failure: Error {
        let message: String
    }

    func run() -> String? {
        let steps = scenario["steps"] as? [[String: Any]] ?? []
        for (i, step) in steps.enumerated() {
            do {
                try perform(step)
            } catch let failure as Failure {
                return "step \(i + 1) \(step): \(failure.message)"
            } catch {
                return "step \(i + 1) \(step): \(error)"
            }
        }
        return nil
    }

    private func feed(_ samples: [AccelSample]) {
        // The sensor delivers about one batch a second.
        for batch in samples.chunked(25) {
            switch navigator.screen {
            case let .tracker(session): session.onSamples(batch)
            case let .recording(session): session.onSamples(batch)
            default: break
            }
        }
        t = Feeds.next(samples)
    }

    private func perform(_ step: [String: Any]) throws {
        guard let key = step.keys.first else { throw Failure(message: "empty step") }
        let value = step[key]
        switch key {
        case "press":
            switch value as? String {
            case "up": navigator.onUp()
            case "down": navigator.onDown()
            case "start": navigator.onStart()
            case "back": navigator.onBack()
            default: throw Failure(message: "unknown button \(String(describing: value))")
            }
        case "select":
            let id = value as? String ?? ""
            switch navigator.screen {
            case let .tracker(session): session.onMenuSelect(id)
            case let .recording(session): session.onMenuSelect(id)
            default: throw Failure(message: "no menu on \(screenName())")
            }
        case "warmup": feed(Feeds.baseline(t, 55))
        case "catches": feed(Feeds.catches(t, int(value)))
        case "burst": for _ in 0..<int(value) { feed(Feeds.burst(t)) }
        case "idle": feed(Feeds.baseline(t, int(value)))
        case "wait": scheduler.advance(Int64(int(value)))
        case "epochAdvance": epoch += Int64(int(value))
        case "deliver":
            let delivered = (value as? Bool) ?? false
            if let index = step["index"] { link.complete(delivered, index: int(index)) } else { link.complete(delivered) }
        case "deliverAll": deliverAll()
        case "phone": link.listener?(value as? [String: Any] ?? [:])
        case "expect": try expect(value as? [String: Any] ?? [:])
        default: throw Failure(message: "unknown step \(key)")
        }
    }

    private func int(_ value: Any?) -> Int { WatchProtocol.int(value) ?? 0 }

    /// Lets every message through: this one and all later ones report
    /// delivered, and time runs on in small steps until the run's rec_end
    /// (or the session) has gone out.
    private func deliverAll() {
        link.autoResult = true
        link.complete(true)
        var steps = 0
        while !["rec_end", "session"].contains(link.types.last ?? ""), steps < 400 {
            scheduler.advance(50)
            steps += 1
        }
    }

    private func screenName() -> String {
        switch navigator.screen {
        case .modeSelect: "modeSelect"
        case .ballSelect: "ballSelect"
        case .tracker: "tracker"
        case .recording: "recording"
        }
    }

    private func expect(_ want: [String: Any]) throws {
        var tracker: TrackerUiState?
        var recording: RecordingUiState?
        var recordMode: Bool?
        var mode: String?
        var selectBalls: Int?
        switch navigator.screen {
        case let .modeSelect(isRecord): recordMode = isRecord
        case let .ballSelect(m, balls):
            mode = m == .juggle ? "juggle" : "record"
            selectBalls = balls
        case let .tracker(session): tracker = session.state
        case let .recording(session): recording = session.state
        }
        let menu = tracker?.menu ?? recording?.menu
        for key in want.keys.sorted() {
            let expected = want[key]
            let actual: Any?
            switch key {
            case "screen": actual = screenName()
            case "recordMode": actual = recordMode
            case "mode": actual = mode
            case "balls": actual = selectBalls ?? tracker?.ballCount ?? recording?.ballCount
            case "menu": actual = menu?.kind
            case "menuTitle": actual = menu?.title
            case "menuItems": actual = menu?.items.map(\.id)
            case "menuLabels": actual = menu?.items.map(\.label)
            case "menuSubLabels": actual = menu.map { $0.items.map { item -> Any in item.subLabel.map { $0 as Any } ?? NSNull() } }
            case "runActive": actual = tracker?.runActive
            case "count": actual = tracker?.currentCount ?? recording?.liveCount
            case "previous": actual = tracker?.previousCount
            case "runs": actual = tracker?.runs
            case "max": actual = tracker?.max
            case "sending": actual = tracker?.sending
            case "phase": actual = recording.map { phaseName($0.phase) }
            case "runsCompleted": actual = recording?.runsCompleted
            case "detected": actual = recording?.detectedCount
            case "label": actual = recording?.labelCount
            case "recordedSamples": actual = recording?.recordedSamples
            case "error": actual = tracker?.errorMessage ?? recording?.errorMessage
            case "exits": actual = effects.exits
            case "vibrations": actual = effects.vibrations
            case "sentTypes": actual = collapsed(link.types)
            case "sentCount": actual = link.sent.count
            case "firstSent":
                try checkSubset(key, expected as? [String: Any] ?? [:], link.sent.first)
                continue
            case "lastSent":
                try checkSubset(key, expected as? [String: Any] ?? [:], link.sent.last)
                continue
            default: throw Failure(message: "unknown expectation \(key)")
            }
            if Value(expected) != Value(actual) {
                throw Failure(message: "\(key) expected \(Value(expected)) but was \(Value(actual))")
            }
        }
    }

    private func checkSubset(_ name: String, _ want: [String: Any], _ sent: [String: Any]?) throws {
        guard let sent else { throw Failure(message: "\(name): nothing was sent") }
        for (key, expected) in want {
            if expected is NSNull {
                if sent[key] != nil { throw Failure(message: "\(name).\(key) should be absent") }
            } else if Value(expected) != Value(sent[key]) {
                throw Failure(message: "\(name).\(key) expected \(Value(expected)) but was \(Value(sent[key]))")
            }
        }
    }

    private func phaseName(_ phase: RecordingPhase) -> String {
        switch phase {
        case .idle: "idle"
        case .recording: "recording"
        case .labeling: "labeling"
        case .syncing: "syncing"
        }
    }

    /// Consecutive repeats folded into one, so chunk counts do not matter.
    private func collapsed(_ types: [String]) -> [String] {
        types.reduce(into: []) { out, type in if out.last != type { out.append(type) } }
    }
}
