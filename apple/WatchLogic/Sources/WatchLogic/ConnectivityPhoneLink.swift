import Foundation
import JugglingCore

/// The watch's end of WatchConnectivity, as `ConnectivityPhoneLink` needs it.
/// The watch app wraps `WCSession` in one (`Watch/PhoneConnectivity.swift`);
/// the tests use a fake, so the link's rules run without a watch. Every
/// callback arrives on the main thread.
public protocol PhoneConnectivity: AnyObject {
    /// True once the session is activated and messages can go out.
    var isActivated: Bool { get }
    /// Called when an activation finishes, with whether it succeeded.
    var onActivation: ((Bool) -> Void)? { get set }
    /// A message the phone sent on its own rather than as a reply.
    var onMessage: (([String: Any]) -> Void)? { get set }
    func activate()
    /// Sends one message to the iPhone app. Exactly one of `reply`, with the
    /// phone's answer, or `failure` follows.
    func sendMessage(
        _ message: [String: Any],
        reply: @escaping ([String: Any]) -> Void,
        failure: @escaping (Error) -> Void
    )
}

/// `PhoneLink` over WatchConnectivity, the counterpart of the Wear OS app's
/// `DataLayerPhoneLink`.
///
/// Each payload goes out as a WatchConnectivity message, which wakes the
/// iPhone app in the background if it is not open. The phone answers every
/// message: with its `ack` once it has stored a session or a whole recorded
/// run, or with an empty reply otherwise. An answer means the message was
/// delivered, and an ack in it then goes to the message listener, as the
/// Data Layer message back does on Wear OS. A message that cannot go (no
/// phone in reach, or no answer from it) reports not delivered, so the
/// session offers Retry sync, Quit without sync and Continue.
///
/// Sends made before the session has finished activating wait for it. If
/// activation fails they report not delivered, and the next send tries to
/// activate again.
public final class ConnectivityPhoneLink: PhoneLink {
    private let connectivity: PhoneConnectivity
    private var listener: (([String: Any]) -> Void)?
    private var waiting: [(payload: [String: Any], onResult: (Bool) -> Void)] = []
    private var activating = false

    public init(connectivity: PhoneConnectivity) {
        self.connectivity = connectivity
        connectivity.onActivation = { [weak self] activated in self?.activationFinished(activated) }
        connectivity.onMessage = { [weak self] message in self?.listener?(message) }
        activating = true
        connectivity.activate()
    }

    public func send(_ payload: [String: Any], onResult: @escaping (_ delivered: Bool) -> Void) {
        if connectivity.isActivated {
            transmit(payload, onResult: onResult)
            return
        }
        waiting.append((payload, onResult))
        if !activating {
            activating = true
            connectivity.activate()
        }
    }

    public func setMessageListener(_ listener: (([String: Any]) -> Void)?) {
        self.listener = listener
    }

    private func activationFinished(_ activated: Bool) {
        activating = false
        let sends = waiting
        waiting = []
        for send in sends {
            if activated {
                transmit(send.payload, onResult: send.onResult)
            } else {
                send.onResult(false)
            }
        }
    }

    private func transmit(_ payload: [String: Any], onResult: @escaping (Bool) -> Void) {
        // The session reports a send once; a transport that answered twice
        // must not close a later attempt.
        var reported = false
        connectivity.sendMessage(
            payload,
            reply: { [weak self] answer in
                guard !reported else { return }
                reported = true
                onResult(true)
                if answer["type"] as? String == WatchProtocol.typeAck {
                    self?.listener?(answer)
                }
            },
            failure: { _ in
                guard !reported else { return }
                reported = true
                onResult(false)
            }
        )
    }
}
