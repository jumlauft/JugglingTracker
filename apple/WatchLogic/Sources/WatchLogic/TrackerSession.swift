import Foundation
import JugglingCore

/// Everything the Juggle screen draws (JUG-1).
public struct TrackerUiState: Equatable {
    public var ballCount: Int
    public var runActive = false
    public var currentCount = 0
    public var previousCount = 0
    public var runs = 0
    public var average = 0.0
    public var max = 0
    public var elapsedSeconds: Int64 = 0
    /// Session shape consistency in percent, -1 until a run has been scored.
    public var shapeConsistency = -1
    public var errorMessage: String?
    public var sending = false
    public var syncDots = 0
    public var menu: MenuSpec?
    public var exited = false
}

/// Juggle mode: the Apple Watch counterpart of `connectiq/source/MainView.mc`
/// and its delegates, ported from `wearos/.../TrackerSession.kt`. It owns the
/// detector, the session-end and discard menus, and the sync to the phone.
/// Requirement ids refer to `connectiq/REQUIREMENTS.md`.
///
/// Inputs map onto the Garmin buttons: `onStartStop` is START/STOP, `onBack`
/// is BACK, and a menu reports through `onMenuSelect` / `onMenuBack`.
public final class TrackerSession {
    // The full round trip (watch -> phone -> ack -> watch) can take a few
    // seconds, so keep this generous to avoid false "sync failed".
    public static let syncTimeoutMs: Int64 = 10_000
    public static let statusTickMs: Int64 = 1_000
    public static let vibrateEvery = 10

    public static let menuSessionEnd = "session_end"
    public static let menuDiscard = "discard_run"

    public static let itemSyncQuit = "sync_quit"
    public static let itemSyncRetry = "sync_retry"
    public static let itemNoSyncQuit = "nosync_quit"
    public static let itemContinue = "continue_session"
    public static let itemDiscardYes = "discard_yes"
    public static let itemDiscardNo = "discard_no"

    public static let syncFailed = "Sync failed"

    private let link: PhoneLink
    private let scheduler: Scheduler
    private let clock: MonotonicClock
    private let effects: WatchEffects
    private let epochSeconds: () -> Int64

    private let detector: JugglingDetector
    private let sessionStartMs: Int64
    private var sessionEndMs: Int64?
    // Time spent in the session-end menu before picking Continue. The session
    // clock stands still while that menu is up, so this is left out of the
    // elapsed time shown and sent to the phone.
    private var pausedMs: Int64 = 0
    // The session's id on the phone, fixed by the first sync. Ending again
    // after Continue resends the whole session under the same id, so the
    // phone replaces its first copy instead of storing the runs twice (SYNC-5).
    private var sessionTimestamp: Int64?

    private var sending = false
    private var errorMessage: String?
    private var pendingPayload: [String: Any]?
    // Bumped on every transmit attempt so a late report from an abandoned
    // attempt cannot land on the current one (SYNC-4).
    private var syncGeneration = 0
    private var syncTimer: Cancellable?
    private var statusTimer: Cancellable?
    private var syncDots = 0
    private var lastVibrateCount = 0
    private var menu: MenuSpec?
    private var exited = false

    public private(set) var state: TrackerUiState
    /// Called after every change to `state`.
    public var onChange: (() -> Void)?

    public init(
        ballCount: Int,
        link: PhoneLink,
        scheduler: Scheduler,
        clock: MonotonicClock,
        effects: WatchEffects,
        epochSeconds: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970) }
    ) {
        self.link = link
        self.scheduler = scheduler
        self.clock = clock
        self.effects = effects
        self.epochSeconds = epochSeconds
        detector = JugglingDetector(ballCount: ballCount)
        sessionStartMs = clock.nowMs()
        state = TrackerUiState(ballCount: ballCount)
        state = snapshot()
        // Listen for the phone's acknowledgement that a session was stored.
        link.setMessageListener { [weak self] in self?.onPhoneMessage($0) }
    }

    /// Feeds a batch of samples. On the Garmin, pushing a menu hides the view
    /// and drops its sensor listener, so detection pauses while a menu is up;
    /// this keeps that behaviour.
    public func onSamples(_ samples: [AccelSample]) {
        guard !exited, menu == nil, let last = samples.last else { return }
        for s in samples {
            detector.processSample(s.x, s.y, s.z, nowMs: s.timeMs)
        }

        // JUG-7: buzz once every 10 watch-hand catches.
        let count = detector.currentCount
        if count > 0 && count / Self.vibrateEvery > lastVibrateCount / Self.vibrateEvery {
            effects.vibrate()
            lastVibrateCount = count
        }

        if detector.checkAutoFinish(nowMs: last.timeMs) > 0 {
            lastVibrateCount = 0
        }
        publish()
    }

    /// Refreshes the elapsed-time display.
    public func tick() { publish() }

    // MARK: START/STOP: the session-end menu (JUG-2)

    public func onStartStop() { showSessionEndMenu(isRetry: false) }

    private func showSessionEndMenu(isRetry: Bool) {
        if menu != nil || exited { return }
        if !isRetry && sessionEndMs == nil {
            sessionEndMs = clock.nowMs()
        }
        var items: [MenuItemSpec] = []
        if isRetry {
            items.append(MenuItemSpec(id: Self.itemSyncRetry, label: "Retry sync"))
        } else {
            items.append(MenuItemSpec(id: Self.itemSyncQuit, label: "Sync and quit"))
        }
        items.append(MenuItemSpec(id: Self.itemNoSyncQuit, label: "Quit without sync"))
        items.append(MenuItemSpec(id: Self.itemContinue, label: "Continue"))
        menu = MenuSpec(kind: Self.menuSessionEnd, title: isRetry ? Self.syncFailed : "End session?", items: items)
        publish()
    }

    // MARK: BACK: discard one run, never exit (JUG-3, JUG-4)

    public func onBack() {
        if menu != nil || exited { return }
        let title: String
        let detail: String
        if detector.isRunActive {
            title = "Discard this run?"
            detail = "\(detector.currentCount) catches"
        } else if detector.sessionRuns > 0 {
            title = "Discard last run?"
            detail = "\(detector.previousCount) catches"
        } else {
            // Nothing recorded yet: swallow the press. Letting it through
            // would close the session, which is what this handler exists to stop.
            return
        }
        menu = MenuSpec(
            kind: Self.menuDiscard,
            title: title,
            items: [
                MenuItemSpec(id: Self.itemDiscardYes, label: "Discard", subLabel: detail),
                MenuItemSpec(id: Self.itemDiscardNo, label: "Keep"),
            ]
        )
        publish()
    }

    // MARK: Menu answers

    public func onMenuSelect(_ itemId: String) {
        guard let open = menu else { return }
        menu = nil
        switch open.kind {
        case Self.menuDiscard:
            onDiscardResponse(confirmed: itemId == Self.itemDiscardYes)
        case Self.menuSessionEnd:
            switch itemId {
            case Self.itemSyncQuit:
                doSync()
            case Self.itemSyncRetry:
                errorMessage = nil
                attemptSync()
            case Self.itemNoSyncQuit:
                cancelSyncTimer()
                exit()
            case Self.itemContinue:
                onContinueSession()
            default:
                break
            }
        default:
            break
        }
        publish()
    }

    /// Backing out of any menu clears it (JUG-6): out of the discard prompt it
    /// means Keep, out of the session-end menu it means Continue.
    public func onMenuBack() {
        guard let open = menu else { return }
        menu = nil
        switch open.kind {
        case Self.menuDiscard: onDiscardResponse(confirmed: false)
        case Self.menuSessionEnd: onContinueSession()
        default: break
        }
        publish()
    }

    private func onDiscardResponse(confirmed: Bool) {
        if confirmed {
            detector.discardLastRun()
            // The next run's 10-catch buzz counts from zero again.
            lastVibrateCount = 0
        }
    }

    private func onContinueSession() {
        // Resume the session clock from where the menu stopped it.
        if let end = sessionEndMs { pausedMs += clock.nowMs() - end }
        sessionEndMs = nil
        // JUG-8: nothing is failing any more once the user is juggling again.
        errorMessage = nil
        // JUG-8: abandon the sync. Its ack can still arrive late, and with the
        // payload kept it would close the session mid-juggle. Ending again
        // sends the whole session afresh under the same timestamp.
        sending = false
        cancelSyncTimer()
        cancelStatusTimer()
        pendingPayload = nil
        syncGeneration += 1
    }

    // MARK: Sync (SYNC-1..5)

    private func doSync() {
        if sending || menu != nil { return }

        // Fold any run still in progress into the session.
        detector.finishCurrentRun()
        if sessionEndMs == nil {
            sessionEndMs = clock.nowMs()
        }

        let runs = detector.runCatches
        if runs.isEmpty {
            // SYNC-3: nothing worth sending, just close.
            exit()
            return
        }

        let timestamp = sessionTimestamp ?? epochSeconds()
        sessionTimestamp = timestamp
        var payload: [String: Any] = [
            "type": WatchProtocol.typeSession,
            "countMode": "watch_hand",
            "balls": detector.ballCount,
            "timestamp": timestamp,
            "durationSeconds": sessionDurationSeconds(),
            "runDurationsMillis": detector.runDurationsMillis,
            "runs": runs,
        ]
        // Left out until a run has been long enough to score, so the phone
        // shows no value rather than a made-up one (SYNC-1).
        let shape = detector.shapeConsistencyPercent
        if shape >= 0 { payload["shapeConsistency"] = shape }
        pendingPayload = payload
        attemptSync()
    }

    private func attemptSync() {
        guard let payload = pendingPayload else { return }
        errorMessage = nil
        syncGeneration += 1
        let generation = syncGeneration

        sending = true
        startSyncTimer()
        startStatusTimer()
        link.send(payload) { [weak self] delivered in
            // Delivery alone closes nothing: only the phone's ack does.
            if !delivered { self?.onTransmitError(generation) }
        }
        publish()
    }

    private func onTransmitError(_ generation: Int) {
        if generation != syncGeneration { return } // late report from an abandoned attempt
        if !sending { return }
        sending = false
        cancelSyncTimer()
        cancelStatusTimer()
        promptRetryOrQuit()
        publish()
    }

    private func onSyncTimeout() {
        syncTimer = nil
        if sending {
            sending = false
            cancelStatusTimer()
            promptRetryOrQuit()
            publish()
        }
    }

    /// SYNC-2: only the phone's `ack` closes the session.
    private func onPhoneMessage(_ data: [String: Any]) {
        guard data["type"] as? String == WatchProtocol.typeAck else { return }
        guard pendingPayload != nil else { return }
        sending = false
        cancelSyncTimer()
        cancelStatusTimer()
        pendingPayload = nil
        menu = nil
        exit()
        publish()
    }

    private func promptRetryOrQuit() {
        errorMessage = Self.syncFailed
        showSessionEndMenu(isRetry: true)
    }

    private func startSyncTimer() {
        cancelSyncTimer()
        syncTimer = scheduler.schedule(afterMs: Self.syncTimeoutMs) { [weak self] in self?.onSyncTimeout() }
    }

    private func cancelSyncTimer() {
        syncTimer?.cancel()
        syncTimer = nil
    }

    // Animates "Sync to phone..." with one more dot each second.
    private func startStatusTimer() {
        cancelStatusTimer()
        syncDots = 1
        scheduleStatusTick()
    }

    private func scheduleStatusTick() {
        statusTimer = scheduler.schedule(afterMs: Self.statusTickMs) { [weak self] in
            guard let self else { return }
            syncDots = (syncDots % 3) + 1
            scheduleStatusTick()
            publish()
        }
    }

    private func cancelStatusTimer() {
        statusTimer?.cancel()
        statusTimer = nil
        syncDots = 0
    }

    private func exit() {
        if exited { return }
        exited = true
        cancelSyncTimer()
        cancelStatusTimer()
        link.setMessageListener(nil)
        effects.exit()
    }

    private func sessionDurationSeconds() -> Int64 {
        let end = sessionEndMs ?? clock.nowMs()
        return Swift.max(end - sessionStartMs - pausedMs, 0) / 1000
    }

    private func publish() {
        state = snapshot()
        onChange?()
    }

    private func snapshot() -> TrackerUiState {
        TrackerUiState(
            ballCount: detector.ballCount,
            runActive: detector.isRunActive,
            currentCount: detector.currentCount,
            previousCount: detector.previousCount,
            runs: detector.sessionRuns,
            average: detector.sessionAverage,
            max: detector.sessionMax,
            elapsedSeconds: sessionDurationSeconds(),
            shapeConsistency: detector.shapeConsistencyPercent,
            errorMessage: errorMessage,
            sending: sending,
            syncDots: syncDots,
            menu: menu,
            exited: exited
        )
    }
}
