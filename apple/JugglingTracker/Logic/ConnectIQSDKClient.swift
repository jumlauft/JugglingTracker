import ConnectIQ
import Foundation

/// `ConnectIQClient` on Garmin's Connect IQ Mobile SDK for iOS
/// (github.com/garmin/connectiq-companion-app-sdk-ios, added in project.yml).
/// A thin translation: what to do with the watch is decided in `GarminLink`.
/// Everything it reports reaches the delegate on the main thread.
final class ConnectIQSDKClient: NSObject, ConnectIQClient {
    weak var delegate: ConnectIQClientDelegate?

    private var sdk: ConnectIQ { ConnectIQ.sharedInstance() }
    /// The SDK's objects for each watch and app. It knows a watch by its
    /// UUID, so a watch rebuilt from what `GarminLink` kept works like the
    /// one Garmin Connect sent; reusing them keeps one of each.
    private var iqDevices: [UUID: IQDevice] = [:]
    private var iqApps: [String: IQApp] = [:]

    func initialize(urlScheme: String) {
        // This app is the override delegate, so a missing Garmin Connect
        // shows on the watch card instead of in an alert from the SDK.
        sdk.initialize(withUrlScheme: urlScheme, uiOverrideDelegate: self)
    }

    func showDeviceSelection() {
        sdk.showConnectIQDeviceSelection()
    }

    func parseDeviceSelection(_ url: URL) -> [GarminDevice]? {
        guard let parsed = sdk.parseDeviceSelectionResponse(from: url) else { return nil }
        let devices = parsed.compactMap { $0 as? IQDevice }
        // Garmin Connect's list replaces everything known before.
        iqDevices = [:]
        iqApps = [:]
        for device in devices { iqDevices[device.uuid] = device }
        return devices.map(Self.garminDevice)
    }

    func registerForEvents(of device: GarminDevice) {
        sdk.register(forDeviceEvents: iqDevice(device), delegate: self)
    }

    func registerForMessages(fromApp appId: UUID, on device: GarminDevice) {
        sdk.register(forAppMessages: iqApp(appId, on: device), delegate: self)
    }

    func unregisterAll() {
        sdk.unregister(forAllDeviceEvents: self)
        sdk.unregister(forAllAppMessages: self)
    }

    func status(of device: GarminDevice) -> GarminDeviceStatus {
        Self.status(sdk.getDeviceStatus(iqDevice(device)))
    }

    func isAppInstalled(_ appId: UUID, on device: GarminDevice, completion: @escaping (Bool?) -> Void) {
        sdk.getAppStatus(iqApp(appId, on: device)) { appStatus in
            // nil when the watch is not connected or did not answer in time.
            let installed = appStatus.map { $0.isInstalled }
            Self.onMain { completion(installed) }
        }
    }

    func send(_ message: [String: Any], toApp appId: UUID, on device: GarminDevice) {
        sdk.sendMessage(message, to: iqApp(appId, on: device), progress: { _, _ in }, completion: { _ in })
    }

    // MARK: - SDK objects

    private func iqDevice(_ device: GarminDevice) -> IQDevice {
        if let known = iqDevices[device.id] { return known }
        let made: IQDevice = IQDevice(id: device.id, modelName: device.modelName, friendlyName: device.friendlyName)
        iqDevices[device.id] = made
        return made
    }

    private func iqApp(_ appId: UUID, on device: GarminDevice) -> IQApp {
        let key = "\(device.id.uuidString)/\(appId.uuidString)"
        if let known = iqApps[key] { return known }
        let made: IQApp = IQApp(uuid: appId, storeUuid: nil, device: iqDevice(device))
        iqApps[key] = made
        return made
    }

    private static func garminDevice(_ device: IQDevice) -> GarminDevice {
        GarminDevice(id: device.uuid, modelName: device.modelName ?? "", friendlyName: device.friendlyName ?? "")
    }

    private static func status(_ status: IQDeviceStatus) -> GarminDeviceStatus {
        switch status {
        case .invalidDevice: .invalid
        case .bluetoothNotReady: .bluetoothOff
        case .notFound: .notFound
        case .notConnected: .notConnected
        case .connected: .connected
        @unknown default: .invalid
        }
    }

    private static func onMain(_ work: @escaping () -> Void) {
        if Thread.isMainThread { work() } else { DispatchQueue.main.async(execute: work) }
    }
}

// The SDK's delegate methods are optional, so a misspelled one would simply
// never be called: each one names its Objective-C selector.
extension ConnectIQSDKClient: IQDeviceEventDelegate, IQAppMessageDelegate, IQUIOverrideDelegate {
    @objc(deviceStatusChanged:status:)
    func deviceStatusChanged(_ device: IQDevice?, status: IQDeviceStatus) {
        guard let device else { return }
        let changed = Self.garminDevice(device)
        let mapped = Self.status(status)
        Self.onMain { self.delegate?.connectIQ(device: changed, changedTo: mapped) }
    }

    @objc(deviceCharacteristicsDiscovered:)
    func deviceCharacteristicsDiscovered(_ device: IQDevice?) {
        guard let device else { return }
        let ready = Self.garminDevice(device)
        Self.onMain { self.delegate?.connectIQDeviceReady(ready) }
    }

    @objc(receivedMessage:fromApp:)
    func receivedMessage(_ message: Any?, from app: IQApp?) {
        guard let message, let app, let device = app.device else { return }
        let appId: UUID = app.uuid
        let from = Self.garminDevice(device)
        Self.onMain { self.delegate?.connectIQ(received: message, fromApp: appId, on: from) }
    }

    @objc(needsToInstallConnectMobile)
    func needsToInstallConnectMobile() {
        Self.onMain { self.delegate?.connectIQNeedsGarminConnect() }
    }
}
