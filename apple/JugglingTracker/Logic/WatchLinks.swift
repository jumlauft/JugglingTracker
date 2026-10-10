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

/// The Garmin link, until the Connect IQ Mobile SDK for iOS is wired in (a
/// later stream). It reports that the watch cannot be reached, so the card
/// shows the setup checklist rather than a link that does not exist yet.
final class GarminLinkStub: WatchLink {
    static let message = "The Garmin link is not part of this build yet"

    private let onStatus: (GarminConnectionStatus, String) -> Void

    init(inbox: WatchInbox, onStatus: @escaping (GarminConnectionStatus, String) -> Void) {
        self.onStatus = onStatus
    }

    func start() {
        onStatus(.sdkError, Self.message)
    }

    func stop() {}
}
