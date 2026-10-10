import Foundation
import JugglingCore

/// A Garmin watch the user shared with this app in Garmin Connect.
struct GarminDevice: Codable, Equatable {
    var id: UUID
    var modelName: String
    var friendlyName: String

    var displayName: String { friendlyName.isEmpty ? modelName : friendlyName }
}

/// A Garmin watch's Bluetooth link, as the Connect IQ SDK reports it.
enum GarminDeviceStatus: Equatable {
    /// The SDK does not know the watch (it was never registered).
    case invalid
    case bluetoothOff
    /// iOS cannot find the watch, e.g. it was removed in Bluetooth settings.
    case notFound
    case notConnected
    case connected
}

/// What the Connect IQ SDK tells the link.
protocol ConnectIQClientDelegate: AnyObject {
    func connectIQ(device: GarminDevice, changedTo status: GarminDeviceStatus)
    /// The watch is connected and can now be asked things (app status) and sent messages.
    func connectIQDeviceReady(_ device: GarminDevice)
    func connectIQ(received message: Any, fromApp appId: UUID, on device: GarminDevice)
    /// Garmin Connect is not installed, and choosing a watch needs it.
    func connectIQNeedsGarminConnect()
}

/// The parts of Garmin's Connect IQ Mobile SDK the link uses. The app runs
/// on `ConnectIQSDKClient`; the tests drive `GarminLink` through a fake, since
/// the SDK only talks to a real watch over Bluetooth.
protocol ConnectIQClient: AnyObject {
    var delegate: ConnectIQClientDelegate? { get set }
    func initialize(urlScheme: String)
    /// Opens Garmin Connect, where the user picks the watches to share. It
    /// answers by opening this app's URL scheme with them.
    func showDeviceSelection()
    /// The watches in Garmin Connect's answer, or nil when `url` is not one.
    func parseDeviceSelection(_ url: URL) -> [GarminDevice]?
    /// Starts reporting status changes of `device`. Needed before anything else is asked about it.
    func registerForEvents(of device: GarminDevice)
    func registerForMessages(fromApp appId: UUID, on device: GarminDevice)
    /// Drops every device and message registration.
    func unregisterAll()
    func status(of device: GarminDevice) -> GarminDeviceStatus
    /// Whether the watch has the app; nil when the watch did not answer.
    func isAppInstalled(_ appId: UUID, on device: GarminDevice, completion: @escaping (Bool?) -> Void)
    func send(_ message: [String: Any], toApp appId: UUID, on device: GarminDevice)
}

/// Turning messages from the Garmin app into watch payloads and back.
enum GarminMessage {
    /// The payload dictionary in a message from the watch. The watch app
    /// transmits one dictionary; the Android SDK hands it over wrapped in a
    /// list, so a list holding one is read too.
    static func payload(_ message: Any) -> [String: Any]? {
        if let dictionary = message as? [String: Any] { return dictionary }
        if let dictionary = message as? NSDictionary {
            var payload: [String: Any] = [:]
            for (key, value) in dictionary {
                guard let key = key as? String else { continue }
                payload[key] = value
            }
            return payload
        }
        if let list = message as? [Any], let first = list.first { return payload(first) }
        return nil
    }

    /// `payload` ready for the SDK. Whole numbers that fit in 32 bits go as
    /// 32-bit numbers, so the watch gets back a Monkey C Number, the type its
    /// own ids are, and compares the ack's timestamp like for like.
    static func outgoing(_ payload: [String: Any]) -> [String: Any] {
        payload.mapValues { value -> Any in
            if value is String { return value }
            if let number = WatchProtocol.int64(value), let small = Int32(exactly: number) {
                return NSNumber(value: small)
            }
            return value
        }
    }
}

/// The Garmin watch's link to this phone, through the Connect IQ Mobile SDK.
/// Counterpart of the Android app's `GarminLink.kt`: it lives as long as the
/// app, reports the watch card's status, and hands every message to the
/// `WatchInbox`, sending back the ack the inbox returns once the data is stored.
///
/// Unlike Android, the iOS SDK does not get the watches from Garmin Connect
/// by itself: the user picks them once in Garmin Connect (`chooseWatch`),
/// which sends them back through this app's URL scheme (`handleOpenURL`).
/// They are kept, so that happens only once per watch. Use from the main thread.
final class GarminLink: WatchLink, ConnectIQClientDelegate {
    /// The URL scheme Garmin Connect opens with the chosen watches. It is
    /// registered in the app's Info.plist (project.yml).
    static let urlScheme = "jugglingtracker-ciq"

    /// The store build and the older beta build of the watch app carry
    /// different manifest ids, and a watch may have either one on it.
    static let watchAppIds = [
        UUID(uuidString: "fa298da6-29c7-46d2-9d76-e07f62d16539")!,
        UUID(uuidString: "88fa4344-0c76-40a9-83e7-e7fc21328822")!,
    ]

    static let devicesKey = "garminDevices"

    private let client: ConnectIQClient
    private let inbox: WatchInbox
    private let defaults: UserDefaults
    private let onStatus: (GarminConnectionStatus, String) -> Void

    private var initialized = false
    /// Every watch the user shared; the card follows `device`.
    private(set) var devices: [GarminDevice] = []
    private(set) var device: GarminDevice?
    private(set) var status = GarminConnectionStatus.notInitialized
    private(set) var message = ""
    /// Bumped by every app-status check, so a late answer to an earlier one is ignored.
    private var probe = 0

    init(
        client: ConnectIQClient,
        inbox: WatchInbox,
        defaults: UserDefaults = .standard,
        onStatus: @escaping (GarminConnectionStatus, String) -> Void
    ) {
        self.client = client
        self.inbox = inbox
        self.defaults = defaults
        self.onStatus = onStatus
    }

    func start() {
        if !initialized {
            initialized = true
            client.delegate = self
            client.initialize(urlScheme: Self.urlScheme)
        }
        devices = loadDevices()
        connect()
    }

    func stop() {
        client.unregisterAll()
        device = nil
        probe += 1
    }

    /// Opens Garmin Connect to pick the watch. The app goes to the
    /// background; Garmin Connect brings it back through `handleOpenURL`.
    func chooseWatch() {
        if !initialized { start() }
        client.showDeviceSelection()
    }

    /// Takes the watches Garmin Connect sent back, replacing the ones known
    /// before. Returns false for any other URL.
    @discardableResult
    func handleOpenURL(_ url: URL) -> Bool {
        guard url.scheme == Self.urlScheme, let chosen = client.parseDeviceSelection(url) else { return false }
        devices = chosen
        saveDevices(chosen)
        connect()
        return true
    }

    // MARK: - Watches

    private func connect() {
        client.unregisterAll()
        probe += 1
        guard !devices.isEmpty else {
            device = nil
            setStatus(.noPairedDevices, "No Garmin watch chosen yet. Tap to choose it.")
            return
        }
        // Messages are taken from every shared watch and both app ids, so
        // none is missed whichever one the card shows.
        for device in devices {
            client.registerForEvents(of: device)
            for appId in Self.watchAppIds {
                client.registerForMessages(fromApp: appId, on: device)
            }
        }
        // Several watches may be shared. The card follows one that is
        // connected now, else the first.
        let chosen = devices.first { client.status(of: $0) == .connected } ?? devices[0]
        device = chosen
        show(client.status(of: chosen), of: chosen)
    }

    private func show(_ deviceStatus: GarminDeviceStatus, of device: GarminDevice) {
        switch deviceStatus {
        case .connected:
            let more = devices.count > 1 ? " (\(devices.count) shared)" : ""
            setStatus(.ready, "Connected to \(device.displayName)\(more)")
            checkWatchApp(on: device)
        case .notConnected, .notFound:
            probe += 1
            setStatus(.disconnected, "Watch disconnected from phone")
        case .bluetoothOff:
            probe += 1
            setStatus(.bluetoothDisabled, "Bluetooth is off. Turn it on to sync with your watch.")
        case .invalid:
            probe += 1
            setStatus(.sdkError, "Watch not found. Tap to choose it again.")
        }
    }

    /// Asks the watch for each app id in turn; the card turns to "not
    /// installed" only when it answered no to all. No answer leaves the card as it is.
    private func checkWatchApp(on device: GarminDevice) {
        probe += 1
        checkWatchApp(on: device, index: 0, probe: probe)
    }

    private func checkWatchApp(on device: GarminDevice, index: Int, probe: Int) {
        guard index < Self.watchAppIds.count else {
            setStatus(.watchAppMissing, "Install the Juggling Tracker watch app from the Connect IQ Store.")
            return
        }
        client.isAppInstalled(Self.watchAppIds[index], on: device) { [weak self] installed in
            guard let self, probe == self.probe, device == self.device else { return }
            switch installed {
            case true?: break
            case false?: self.checkWatchApp(on: device, index: index + 1, probe: probe)
            case nil: break
            }
        }
    }

    private func setStatus(_ status: GarminConnectionStatus, _ message: String) {
        self.status = status
        self.message = message
        onStatus(status, message)
    }

    // MARK: - ConnectIQClientDelegate

    func connectIQ(device: GarminDevice, changedTo deviceStatus: GarminDeviceStatus) {
        if device.id == self.device?.id {
            show(deviceStatus, of: device)
        } else if deviceStatus == .connected, let current = self.device, client.status(of: current) != .connected {
            // Another shared watch came in while the one shown is away.
            self.device = device
            show(deviceStatus, of: device)
        }
    }

    func connectIQDeviceReady(_ device: GarminDevice) {
        guard device.id == self.device?.id else { return }
        // The status check made on connecting may have come too early to be answered.
        checkWatchApp(on: device)
    }

    func connectIQ(received message: Any, fromApp appId: UUID, on device: GarminDevice) {
        guard let payload = GarminMessage.payload(message) else { return }
        // A message proves the watch and its app are there, whatever the card said.
        if status != .ready {
            self.device = device
            setStatus(.ready, "Connected to \(device.displayName)")
        }
        guard let ack = inbox.receive(payload, from: .garmin) else { return }
        // Tells the watch the session or run is stored; only then may it let go of it.
        client.send(GarminMessage.outgoing(ack), toApp: appId, on: device)
    }

    func connectIQNeedsGarminConnect() {
        setStatus(.connectIQMissing, "Garmin Connect app not found. Please install it from the App Store.")
    }

    // MARK: - Kept watches

    private func loadDevices() -> [GarminDevice] {
        guard let data = defaults.data(forKey: Self.devicesKey) else { return [] }
        return (try? JSONDecoder().decode([GarminDevice].self, from: data)) ?? []
    }

    private func saveDevices(_ devices: [GarminDevice]) {
        if let data = try? JSONEncoder().encode(devices) {
            defaults.set(data, forKey: Self.devicesKey)
        }
    }
}
