import JugglingCore
import XCTest
@testable import JugglingTracker

/// Stands in for Garmin's SDK: watches, their status and the watch app's
/// answers are set by the test, and what the link sends is kept.
private final class FakeConnectIQClient: ConnectIQClient {
    weak var delegate: ConnectIQClientDelegate?
    var initializedWith: String?
    var deviceSelectionShown = 0
    var selection: [GarminDevice]?
    var statuses: [UUID: GarminDeviceStatus] = [:]
    var installedApps: [UUID: Set<UUID>] = [:]
    /// Watches that do not answer app-status questions.
    var silent: Set<UUID> = []
    var eventRegistrations: [UUID] = []
    var messageRegistrations: [(app: UUID, device: UUID)] = []
    var sent: [(message: [String: Any], app: UUID, device: UUID)] = []

    func initialize(urlScheme: String) { initializedWith = urlScheme }
    func showDeviceSelection() { deviceSelectionShown += 1 }
    func parseDeviceSelection(_ url: URL) -> [GarminDevice]? { selection }
    func registerForEvents(of device: GarminDevice) { eventRegistrations.append(device.id) }
    func registerForMessages(fromApp appId: UUID, on device: GarminDevice) {
        messageRegistrations.append((appId, device.id))
    }

    func unregisterAll() {
        eventRegistrations = []
        messageRegistrations = []
    }

    func status(of device: GarminDevice) -> GarminDeviceStatus {
        eventRegistrations.contains(device.id) ? statuses[device.id] ?? .notConnected : .invalid
    }

    func isAppInstalled(_ appId: UUID, on device: GarminDevice, completion: @escaping (Bool?) -> Void) {
        if silent.contains(device.id) || status(of: device) != .connected {
            completion(nil)
        } else {
            completion(installedApps[device.id, default: []].contains(appId))
        }
    }

    func send(_ message: [String: Any], toApp appId: UUID, on device: GarminDevice) {
        sent.append((message, appId, device.id))
    }
}

/// The iPhone's Garmin link, against a fake SDK. What a real watch and the
/// real SDK do over Bluetooth is not covered here.
final class GarminLinkTests: XCTestCase {
    private let storeApp = GarminLink.watchAppIds[0]
    private let betaApp = GarminLink.watchAppIds[1]
    private let forerunner = GarminDevice(
        id: UUID(), modelName: "Forerunner 245", friendlyName: "Jonas's Forerunner", partNumber: "006-B3076-00"
    )
    private let instinct = GarminDevice(id: UUID(), modelName: "Instinct 2", friendlyName: "")

    private var client: FakeConnectIQClient!
    private var defaults: UserDefaults!
    private var sessions: SessionStore!
    private var recordings: RecordingStore!
    private var inbox: WatchInbox!
    private var link: GarminLink!
    private var statuses: [GarminConnectionStatus] = []
    private let selectionURL = URL(string: "jugglingtracker-ciq://device-select-resp?d=1")!

    override func setUp() {
        super.setUp()
        let dir = makeTempDirectory(self)
        let suite = "GarminLinkTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)!
        addTeardownBlock { UserDefaults().removePersistentDomain(forName: suite) }
        sessions = SessionStore(fileURL: dir.appendingPathComponent("sessions.jsonl"))
        recordings = RecordingStore(directory: dir.appendingPathComponent("recordings"))
        inbox = WatchInbox(sessions: sessions, recordings: recordings)
        client = FakeConnectIQClient()
        statuses = []
        link = makeLink()
    }

    private func makeLink() -> GarminLink {
        GarminLink(client: client, inbox: inbox, defaults: defaults) { [unowned self] status, _ in
            self.statuses.append(status)
        }
    }

    /// The user picks `devices` in Garmin Connect, which reopens the app.
    private func choose(_ devices: [GarminDevice]) {
        client.selection = devices
        XCTAssertTrue(link.handleOpenURL(selectionURL))
    }

    // MARK: - Finding the watch

    func testWithNoWatchChosenTheCardAsksToChooseOne() {
        link.start()
        XCTAssertEqual(client.initializedWith, GarminLink.urlScheme)
        XCTAssertEqual(link.status, .noPairedDevices)
        XCTAssertTrue(link.message.contains("choose"))
    }

    func testChoosingOpensGarminConnect() {
        link.start()
        link.chooseWatch()
        XCTAssertEqual(client.deviceSelectionShown, 1)
    }

    func testAChosenConnectedWatchWithTheAppIsReady() {
        client.statuses[forerunner.id] = .connected
        client.installedApps[forerunner.id] = [storeApp]
        link.start()
        choose([forerunner])
        XCTAssertEqual(link.status, .ready)
        XCTAssertEqual(link.message, "Connected to Jonas's Forerunner")
        XCTAssertEqual(link.device, forerunner)
    }

    func testTheChosenWatchIsKeptForTheNextLaunch() {
        client.statuses[forerunner.id] = .connected
        client.installedApps[forerunner.id] = [storeApp]
        link.start()
        choose([forerunner])

        let relaunched = makeLink()
        relaunched.start()
        // With its part number, so the SDK gets the watch as Garmin Connect sent it.
        XCTAssertEqual(relaunched.devices, [forerunner])
        XCTAssertEqual(relaunched.devices.first?.partNumber, "006-B3076-00")
        XCTAssertEqual(relaunched.status, .ready)
    }

    func testOtherURLsAreNotTaken() {
        link.start()
        XCTAssertFalse(link.handleOpenURL(URL(string: "https://example.com/?d=1")!))
        client.selection = nil
        XCTAssertFalse(link.handleOpenURL(selectionURL))
        XCTAssertEqual(link.status, .noPairedDevices)
    }

    func testANewChoiceReplacesTheWatchesKnownBefore() {
        link.start()
        choose([forerunner, instinct])
        choose([instinct])
        XCTAssertEqual(link.devices, [instinct])
        XCTAssertEqual(Set(client.eventRegistrations), [instinct.id])
    }

    func testMessagesAreTakenFromBothAppIdsOnEveryChosenWatch() {
        link.start()
        choose([forerunner, instinct])
        let registered = Set(client.messageRegistrations.map { "\($0.device)/\($0.app)" })
        XCTAssertEqual(registered, Set([forerunner, instinct].flatMap { device in
            [storeApp, betaApp].map { "\(device.id)/\($0)" }
        }))
        // Started again (e.g. a refresh), nothing is registered twice.
        link.start()
        XCTAssertEqual(client.messageRegistrations.count, 4)
    }

    func testTheCardFollowsAConnectedWatchWhenSeveralAreShared() {
        client.statuses[instinct.id] = .connected
        client.installedApps[instinct.id] = [storeApp]
        link.start()
        choose([forerunner, instinct])
        XCTAssertEqual(link.device, instinct)
        XCTAssertEqual(link.message, "Connected to Instinct 2 (2 shared)")
    }

    // MARK: - Watch status

    func testAWatchAwayFromThePhoneIsDisconnected() {
        client.statuses[forerunner.id] = .notConnected
        link.start()
        choose([forerunner])
        XCTAssertEqual(link.status, .disconnected)
    }

    func testBluetoothOffIsShown() {
        client.statuses[forerunner.id] = .bluetoothOff
        link.start()
        choose([forerunner])
        XCTAssertEqual(link.status, .bluetoothDisabled)
    }

    func testTheCardFollowsTheWatchComingAndGoing() {
        client.statuses[forerunner.id] = .notConnected
        client.installedApps[forerunner.id] = [betaApp]
        link.start()
        choose([forerunner])

        client.statuses[forerunner.id] = .connected
        link.connectIQ(device: forerunner, changedTo: .connected)
        XCTAssertEqual(link.status, .ready)

        client.statuses[forerunner.id] = .notConnected
        link.connectIQ(device: forerunner, changedTo: .notConnected)
        XCTAssertEqual(link.status, .disconnected)
    }

    func testAnotherSharedWatchConnectingTakesOverWhileTheShownOneIsAway() {
        link.start()
        choose([forerunner, instinct])
        XCTAssertEqual(link.device, forerunner)
        client.statuses[instinct.id] = .connected
        client.installedApps[instinct.id] = [storeApp]
        link.connectIQ(device: instinct, changedTo: .connected)
        XCTAssertEqual(link.device, instinct)
        XCTAssertEqual(link.status, .ready)
    }

    func testAWatchWithoutEitherAppSaysSo() {
        client.statuses[forerunner.id] = .connected
        link.start()
        choose([forerunner])
        XCTAssertEqual(link.status, .watchAppMissing)
    }

    func testTheBetaBuildCountsAsInstalled() {
        client.statuses[forerunner.id] = .connected
        client.installedApps[forerunner.id] = [betaApp]
        link.start()
        choose([forerunner])
        XCTAssertEqual(link.status, .ready)
    }

    func testAWatchThatDoesNotAnswerIsNotCalledMissing() {
        client.statuses[forerunner.id] = .connected
        client.silent = [forerunner.id]
        link.start()
        choose([forerunner])
        XCTAssertEqual(link.status, .ready)
    }

    func testTheAppIsCheckedAgainOnceTheWatchIsReady() {
        client.statuses[forerunner.id] = .connected
        client.silent = [forerunner.id]
        link.start()
        choose([forerunner])
        client.silent = []
        link.connectIQDeviceReady(forerunner)
        XCTAssertEqual(link.status, .watchAppMissing)
    }

    func testAMissingGarminConnectIsShown() {
        link.start()
        link.connectIQNeedsGarminConnect()
        XCTAssertEqual(link.status, .connectIQMissing)
    }

    // MARK: - Messages and acks

    /// A session as the Garmin app's MainView transmits it, in the types the
    /// SDK delivers (Foundation dictionaries and numbers).
    private func garminSession(timestamp: Int32) -> NSDictionary {
        [
            "type": "session", "countMode": "watch_hand", "balls": NSNumber(value: Int32(3)),
            "timestamp": NSNumber(value: timestamp), "durationSeconds": NSNumber(value: Int32(95)),
            "runDurationsMillis": [NSNumber(value: Int32(5200)), NSNumber(value: Int32(14000))],
            "runs": [NSNumber(value: Int32(12)), NSNumber(value: Int32(31))],
        ] as NSDictionary
    }

    func testAStoredSessionIsAckedToTheAppAndWatchItCameFrom() throws {
        client.statuses[forerunner.id] = .connected
        client.installedApps[forerunner.id] = [storeApp]
        link.start()
        choose([forerunner])

        link.connectIQ(received: garminSession(timestamp: 1_790_081_282), fromApp: betaApp, on: forerunner)

        XCTAssertEqual(sessions.sessions.count, 1)
        XCTAssertEqual(sessions.sessions[0].ballCount, 3)
        XCTAssertEqual(sessions.sessions[0].runHistory, [12, 31])
        XCTAssertEqual(client.sent.count, 1)
        let ack = try XCTUnwrap(client.sent.first)
        XCTAssertEqual(ack.app, betaApp)
        XCTAssertEqual(ack.device, forerunner.id)
        XCTAssertEqual(ack.message["type"] as? String, "ack")
        // A 32-bit number, as the watch's own id is.
        let timestamp = try XCTUnwrap(ack.message["timestamp"] as? NSNumber)
        XCTAssertEqual(timestamp.int64Value, 1_790_081_282)
        XCTAssertEqual(String(cString: timestamp.objCType), "i")
    }

    func testASessionWrappedInAListIsReadToo() {
        link.start()
        choose([forerunner])
        link.connectIQ(received: [garminSession(timestamp: 1_790_081_282)] as NSArray, fromApp: storeApp, on: forerunner)
        XCTAssertEqual(sessions.sessions.count, 1)
        XCTAssertEqual(client.sent.count, 1)
    }

    func testWhatCannotBeStoredIsNotAcked() {
        link.start()
        choose([forerunner])
        link.connectIQ(received: ["type": "session", "balls": 3] as NSDictionary, fromApp: storeApp, on: forerunner)
        link.connectIQ(received: "hello" as NSString, fromApp: storeApp, on: forerunner)
        link.connectIQ(received: NSArray(), fromApp: storeApp, on: forerunner)
        XCTAssertTrue(client.sent.isEmpty)
        XCTAssertTrue(sessions.sessions.isEmpty)
    }

    func testAMessageShowsTheWatchIsThereWhateverTheCardSaid() {
        client.statuses[forerunner.id] = .connected
        link.start()
        choose([forerunner])
        XCTAssertEqual(link.status, .watchAppMissing)
        link.connectIQ(received: garminSession(timestamp: 1_790_081_282), fromApp: storeApp, on: forerunner)
        XCTAssertEqual(link.status, .ready)
    }

    /// A real recording from connectiq/data, sent the way the Garmin app's
    /// RecordingView sends it: rec_start, 50-sample rec_chunk parts, rec_end.
    func testARealRecordingArrivesWholeAndOnlyItsEndIsAcked() throws {
        let runId = "20260603_201719"
        let samples = loadRun(runId)
        XCTAssertGreaterThan(samples.count, 100)
        let id = Int32(1_780_510_639)
        let chunk = 50
        let chunks = (samples.count + chunk - 1) / chunk
        func number(_ value: Int) -> NSNumber { NSNumber(value: Int32(value)) }

        link.start()
        choose([forerunner])
        link.connectIQ(received: [
            "type": "rec_start", "id": NSNumber(value: id), "countMode": "watch_hand",
            "balls": number(3), "catches": number(20), "detected": number(12), "sampleRate": number(25),
            "samples": number(samples.count), "chunks": number(chunks), "timestamp": NSNumber(value: id),
        ] as NSDictionary, fromApp: storeApp, on: forerunner)
        for i in 0..<chunks {
            let part = samples[(i * chunk)..<min((i + 1) * chunk, samples.count)]
            link.connectIQ(received: [
                "type": "rec_chunk", "id": NSNumber(value: id), "i": number(i),
                "x": part.map { number($0.0) }, "y": part.map { number($0.1) }, "z": part.map { number($0.2) },
            ] as NSDictionary, fromApp: storeApp, on: forerunner)
        }
        XCTAssertTrue(client.sent.isEmpty, "Only rec_end is acked")
        link.connectIQ(received: ["type": "rec_end", "id": NSNumber(value: id)] as NSDictionary, fromApp: storeApp, on: forerunner)

        let ack = try XCTUnwrap(client.sent.first)
        XCTAssertEqual((ack.message["timestamp"] as? NSNumber)?.int64Value, Int64(id))
        let stored = try XCTUnwrap(recordings.listRecordings().first)
        XCTAssertEqual(stored.samples, samples.count)
        XCTAssertEqual(stored.balls, 3)
        XCTAssertEqual(stored.catches, 20)
        XCTAssertEqual(stored.detected, 12)

        // The file holds the same samples, so it can go back into connectiq/data.
        let written = try String(contentsOf: recordings.directory.appendingPathComponent(stored.fileName), encoding: .utf8)
        let rows = written.split(separator: "\n").filter { !$0.hasPrefix("#") && !$0.hasPrefix("x,") }
        XCTAssertEqual(rows.count, samples.count)
        XCTAssertEqual(rows.first.map(String.init), "\(samples[0].0),\(samples[0].1),\(samples[0].2)")
        XCTAssertEqual(rows.last.map(String.init), "\(samples.last!.0),\(samples.last!.1),\(samples.last!.2)")
    }

    func testARecordingWithALostChunkIsNotAcked() {
        func number(_ value: Int) -> NSNumber { NSNumber(value: Int32(value)) }
        link.start()
        choose([forerunner])
        link.connectIQ(received: [
            "type": "rec_start", "id": number(7), "balls": number(3), "catches": number(4),
            "samples": number(4), "chunks": number(2),
        ] as NSDictionary, fromApp: storeApp, on: forerunner)
        link.connectIQ(received: [
            "type": "rec_chunk", "id": number(7), "i": number(1),
            "x": [number(1), number(2)], "y": [number(1), number(2)], "z": [number(1), number(2)],
        ] as NSDictionary, fromApp: storeApp, on: forerunner)
        link.connectIQ(received: ["type": "rec_end", "id": number(7)] as NSDictionary, fromApp: storeApp, on: forerunner)
        XCTAssertTrue(client.sent.isEmpty)
        XCTAssertTrue(recordings.listRecordings().isEmpty)
    }

    func testWatchesKeptBeforePartNumbersStillLoad() throws {
        let old = #"[{"id":"\#(forerunner.id.uuidString)","modelName":"Forerunner 245","friendlyName":"F"}]"#
        defaults.set(Data(old.utf8), forKey: GarminLink.devicesKey)
        link.start()
        XCTAssertEqual(link.devices.map(\.id), [forerunner.id])
        XCTAssertNil(link.devices.first?.partNumber)
    }

    // MARK: - Message conversion

    func testAcksKeepLargeNumbersAndStrings() {
        let out = GarminMessage.outgoing(["type": "ack", "timestamp": Int64(1_790_081_282_000)])
        XCTAssertEqual(out["type"] as? String, "ack")
        XCTAssertEqual(WatchProtocol.int64(out["timestamp"]), 1_790_081_282_000)
    }
}
