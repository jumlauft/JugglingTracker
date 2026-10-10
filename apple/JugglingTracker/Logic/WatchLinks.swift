import Foundation

/// The Garmin watch's link to this phone, as the home screen's card shows it.
/// Same states as the Android app's `GarminConnectionStatus`.
enum GarminConnectionStatus: CaseIterable {
    case ready
    case receiving
    case notInitialized
    case bluetoothDisabled
    case noPairedDevices
    case connectIQMissing
    case watchAppMissing
    case disconnected
    case sdkError
}

/// The Apple Watch's link to this phone over WatchConnectivity. Same states
/// as the Android app's `WearConnectionStatus`.
enum AppleWatchConnectionStatus: CaseIterable {
    case checking
    case ready
    case receiving
    case noWatch
    case watchAppMissing
    case unavailable

    /// `paired` is whether an Apple Watch is paired with this phone and
    /// `appInstalled` whether it has the watch app; nil `paired` when
    /// WatchConnectivity is not supported at all.
    static func classify(paired: Bool?, appInstalled: Bool) -> AppleWatchConnectionStatus {
        guard let paired else { return .unavailable }
        if !paired { return .noWatch }
        return appInstalled ? .ready : .watchAppMissing
    }
}

/// A connection to one kind of watch. It reports its status, and hands what
/// the watch sends to the inbox, sending back the ack the inbox returns.
protocol WatchLink: AnyObject {
    func start()
    func stop()
}
