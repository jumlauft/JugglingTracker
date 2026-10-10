import Foundation

/// The iPhone's end of WatchConnectivity, as `AppleWatchLink` needs it. The
/// app wraps `WCSession` in one (`PhoneWatchSession`); the tests use a fake,
/// so the link's rules run without a watch. Every callback arrives on the
/// main thread.
protocol WatchConnectivitySession: AnyObject {
    var isActivated: Bool { get }
    /// True when the last activation ended without a session.
    var activationFailed: Bool { get }
    var isPaired: Bool { get }
    var isWatchAppInstalled: Bool { get }
    /// Called when activation finishes, or the paired watch or its apps change.
    var onStateChange: (() -> Void)? { get set }
    /// A message from the watch app, with the function that answers it. Every
    /// message must be answered exactly once.
    var onMessage: (([String: Any], @escaping ([String: Any]) -> Void) -> Void)? { get set }
    func activate()
}

/// The Apple Watch's link to this phone, the counterpart of the Android app's
/// `WatchMessageService` and its Wear OS status checks.
///
/// Each message from the watch app goes to the inbox, and the answer carries
/// what the inbox returns: the ack once a session or a whole recorded run is
/// stored, or an empty reply, which tells the watch the message arrived but
/// closes nothing. The watch keeps its data until it sees an ack.
///
/// The status follows the session: checking until it is activated, then
/// whether an Apple Watch is paired and has the app. `session` is nil where
/// WatchConnectivity is not supported (an iPad).
final class AppleWatchLink: WatchLink {
    private let inbox: WatchInbox
    private let session: WatchConnectivitySession?
    private let onStatus: (AppleWatchConnectionStatus) -> Void

    init(inbox: WatchInbox, session: WatchConnectivitySession?, onStatus: @escaping (AppleWatchConnectionStatus) -> Void) {
        self.inbox = inbox
        self.session = session
        self.onStatus = onStatus
    }

    func start() {
        guard let session else {
            onStatus(.unavailable)
            return
        }
        session.onStateChange = { [weak self] in self?.reportStatus() }
        session.onMessage = { [weak self] payload, answer in
            answer(self?.receive(payload) ?? [:])
        }
        reportStatus()
        session.activate()
    }

    func stop() {
        session?.onStateChange = nil
        session?.onMessage = nil
    }

    /// The answer to one message from the watch.
    func receive(_ payload: [String: Any]) -> [String: Any] {
        inbox.receive(payload, from: .appleWatch) ?? [:]
    }

    private func reportStatus() {
        onStatus(Self.status(of: session))
    }

    static func status(of session: WatchConnectivitySession?) -> AppleWatchConnectionStatus {
        guard let session else { return .unavailable }
        if session.isActivated {
            return AppleWatchConnectionStatus.classify(paired: session.isPaired, appInstalled: session.isWatchAppInstalled)
        }
        return session.activationFailed ? .unavailable : .checking
    }
}
