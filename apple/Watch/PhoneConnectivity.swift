import Foundation
import os
import WatchConnectivity
import WatchLogic

/// `WCSession` as WatchLogic's `PhoneConnectivity`. It only forwards, and on
/// the main queue: what to do with a reply, a failure or a failed activation
/// is decided in `ConnectivityPhoneLink`, where the tests can reach it.
final class WatchSessionConnectivity: NSObject, PhoneConnectivity, WCSessionDelegate {
    private static let log = Logger(subsystem: "com.juggling.tracker.watchkitapp", category: "PhoneLink")

    private let session = WCSession.default
    var onActivation: ((Bool) -> Void)?
    var onMessage: (([String: Any]) -> Void)?

    var isActivated: Bool { session.activationState == .activated }

    func activate() {
        session.delegate = self
        session.activate()
    }

    func sendMessage(
        _ message: [String: Any],
        reply: @escaping ([String: Any]) -> Void,
        failure: @escaping (Error) -> Void
    ) {
        session.sendMessage(
            message,
            replyHandler: { answer in DispatchQueue.main.async { reply(answer) } },
            errorHandler: { error in
                Self.log.warning("Send of \(message["type"] as? String ?? "?", privacy: .public) failed: \(error.localizedDescription, privacy: .public)")
                DispatchQueue.main.async { failure(error) }
            }
        )
    }

    func session(_ session: WCSession, activationDidCompleteWith state: WCSessionActivationState, error: Error?) {
        if let error {
            Self.log.warning("WatchConnectivity did not activate: \(error.localizedDescription, privacy: .public)")
        }
        let activated = state == .activated
        DispatchQueue.main.async { self.onActivation?(activated) }
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        DispatchQueue.main.async { self.onMessage?(message) }
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any], replyHandler: @escaping ([String: Any]) -> Void) {
        replyHandler([:])
        DispatchQueue.main.async { self.onMessage?(message) }
    }
}
