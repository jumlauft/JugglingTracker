import Foundation
import os
import WatchConnectivity

/// `WCSession` as `AppleWatchLink`'s `WatchConnectivitySession`. It only
/// forwards, and on the main queue, where the inbox and the screens live.
final class PhoneWatchSession: NSObject, WatchConnectivitySession, WCSessionDelegate {
    private static let log = Logger(subsystem: "com.juggling.tracker", category: "AppleWatchLink")

    /// The session, or nil on a device without WatchConnectivity (an iPad).
    static func makeIfSupported() -> PhoneWatchSession? {
        WCSession.isSupported() ? PhoneWatchSession() : nil
    }

    private let session = WCSession.default
    private(set) var activationFailed = false
    var onStateChange: (() -> Void)?
    var onMessage: (([String: Any], @escaping ([String: Any]) -> Void) -> Void)?

    var isActivated: Bool { session.activationState == .activated }
    var isPaired: Bool { session.isPaired }
    var isWatchAppInstalled: Bool { session.isWatchAppInstalled }

    func activate() {
        session.delegate = self
        session.activate()
    }

    private func stateChanged() {
        DispatchQueue.main.async { self.onStateChange?() }
    }

    func session(_ session: WCSession, activationDidCompleteWith state: WCSessionActivationState, error: Error?) {
        if let error {
            Self.log.warning("WatchConnectivity did not activate: \(error.localizedDescription, privacy: .public)")
        }
        let failed = state != .activated
        DispatchQueue.main.async {
            self.activationFailed = failed
            self.onStateChange?()
        }
    }

    func sessionDidBecomeInactive(_ session: WCSession) {
        stateChanged()
    }

    func sessionDidDeactivate(_ session: WCSession) {
        // The user switched to another Apple Watch: start over with it.
        session.activate()
        stateChanged()
    }

    func sessionWatchStateDidChange(_ session: WCSession) {
        stateChanged()
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any], replyHandler: @escaping ([String: Any]) -> Void) {
        DispatchQueue.main.async {
            if let onMessage = self.onMessage {
                onMessage(message, replyHandler)
            } else {
                replyHandler([:])
            }
        }
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        DispatchQueue.main.async { self.onMessage?(message) { _ in } }
    }
}
