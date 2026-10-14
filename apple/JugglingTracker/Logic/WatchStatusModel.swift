import Foundation
import Observation

/// What the watch card on the home screen shows: the Garmin and Apple Watch
/// links' state, held on "receiving" while a watch sends. Counterpart of the
/// Android app's `WatchStatusModel`.
@MainActor
@Observable
final class WatchStatusModel {
    /// How long the watch card shows "receiving" after a transfer's last message.
    static let receivingDisplayMs: UInt64 = 1500
    /// How long the card waits for a transfer's next message before giving up
    /// on it. Longer than the watches' own 10 s sync timeout.
    static let receivingStallMs: UInt64 = 15_000

    private(set) var garminStatus: GarminConnectionStatus = .notInitialized
    /// The Garmin link's last message, such as the connected watch's name.
    private(set) var garminMessage = ""
    private(set) var appleWatchStatus: AppleWatchConnectionStatus = .checking

    // While a watch is sending, its card stays on "receiving"; a status its
    // link reports meanwhile waits here and shows once the transfer ends.
    @ObservationIgnored private var garminReceiving: Task<Void, Never>?
    @ObservationIgnored private var appleWatchReceiving: Task<Void, Never>?
    @ObservationIgnored private var garminWhileReceiving: GarminConnectionStatus?
    @ObservationIgnored private var appleWatchWhileReceiving: AppleWatchConnectionStatus?

    func onGarminLinkStatus(_ status: GarminConnectionStatus, message: String) {
        garminMessage = message
        if garminReceiving != nil { garminWhileReceiving = status } else { garminStatus = status }
    }

    func onAppleWatchLinkStatus(_ status: AppleWatchConnectionStatus) {
        if appleWatchReceiving != nil { appleWatchWhileReceiving = status } else { appleWatchStatus = status }
    }

    // The card shows "receiving" from a transfer's first message until a
    // moment after its last. A run arrives in several messages; between them
    // the card holds on for up to receivingStallMs, longer than the watch
    // itself waits, so a transfer that died cannot leave it orange.
    func showReceiving(_ source: WatchInbox.Source, more: Bool) {
        let hold = (more ? Self.receivingStallMs : Self.receivingDisplayMs) * 1_000_000
        switch source {
        case .garmin:
            garminStatus = .receiving
            garminReceiving?.cancel()
            garminReceiving = Task { [weak self] in
                try? await Task.sleep(nanoseconds: hold)
                guard !Task.isCancelled, let self else { return }
                self.garminReceiving = nil
                self.garminStatus = self.garminWhileReceiving ?? .ready
                self.garminWhileReceiving = nil
            }
        case .appleWatch:
            appleWatchStatus = .receiving
            appleWatchReceiving?.cancel()
            appleWatchReceiving = Task { [weak self] in
                try? await Task.sleep(nanoseconds: hold)
                guard !Task.isCancelled, let self else { return }
                self.appleWatchReceiving = nil
                self.appleWatchStatus = self.appleWatchWhileReceiving ?? .ready
                self.appleWatchWhileReceiving = nil
            }
        }
    }
}
