import Foundation
import JugglingCore

public enum RecordingPhase: Equatable, Sendable {
    case idle, recording, labeling, syncing
}

/// Everything the Record screen draws.
public struct RecordingUiState: Equatable {
    public var ballCount: Int
    public var phase = RecordingPhase.idle
    public var runsCompleted = 0
    public var recordedSamples = 0
    public var liveCount = 0
    public var detectedCount = 0
    public var labelCount = 0
    public var syncDots = 0
    public var headerSent = false
    public var transferDone = false
    public var chunkIndex = 0
    public var totalChunks = 0
    public var errorMessage: String?
    public var failReason: String?
    public var dataPending = false
    public var menu: MenuSpec?
    public var exited = false
}

/// Record mode (developer): the Apple Watch counterpart of
/// `connectiq/source/RecordingView.mc`, ported from
/// `wearos/.../RecordingSession.kt`. It captures raw accelerometer runs, lets
/// the user correct the detector's count to the true watch-hand count, and
/// transfers each run to the phone in chunks. Requirement ids refer to
/// `connectiq/REQUIREMENTS.md`.
///
/// Inputs map onto the Garmin buttons: `onStart` is START, `onBack` is BACK,
/// `onUp` / `onDown` adjust the label, and a menu reports through
/// `onMenuSelect` / `onMenuBack`.
public final class RecordingSession {
    public static let sampleRate = JugglingDetector.sampleRate
    public static let maxRunSamples = 3000 // 120 s at 25 Hz (REC-2)
    public static let syncTimeoutMs: Int64 = 10_000
    public static let statusTickMs: Int64 = 1_000
    // The Garmin sends 50 samples per chunk because its link slows down past
    // ~150 integers (REC-5). A WatchConnectivity message, like a Wear OS Data
    // Layer message, carries tens of kilobytes at about the same cost, so a
    // chunk is 1000 samples as on Wear OS: a full 120 s run goes over in 3
    // chunks instead of 60.
    public static let chunkSamples = 1000
    public static let nextPartDelayMs: Int64 = 50

    public static let menuDiscard = "rec_discard"
    public static let menuQuit = "rec_quit"
    public static let menuSync = "rec_sync"

    public static let itemDiscardConfirm = "discard_confirm"
    public static let itemDiscardCancel = "discard_cancel"
    public static let itemQuitConfirm = "quit_confirm"
    public static let itemQuitContinue = "quit_continue"
    public static let itemSyncRetry = "sync_retry"
    public static let itemSyncSkip = "sync_skip"
    public static let itemSyncQuit = "sync_quit"

    private let ballCount: Int
    private let link: PhoneLink
    private let scheduler: Scheduler
    private let effects: WatchEffects
    private let epochSeconds: () -> Int64
    private let log: (String) -> Void

    private var phase = RecordingPhase.idle
    private var accelX: [Int] = []
    private var accelY: [Int] = []
    private var accelZ: [Int] = []
    // Runs the detector alongside, to compare it with the user's label.
    private var detector: JugglingDetector
    private var labelCount = 0
    private var detectedCount = 0
    private var runsCompleted = 0

    private var syncTimer: Cancellable?
    private var statusTimer: Cancellable?
    private var nextPartTimer: Cancellable?
    private var syncDots = 0
    private var menu: MenuSpec?
    private var errorMessage: String?
    private var failReason: String?
    private var pendingPayload: [String: Any]?
    // REC-6: each transmit attempt carries its own generation.
    private var syncGeneration = 0
    private var sessionId: Int64 = 0
    private var chunkIndex = 0
    private var totalChunks = 0
    private var headerSent = false
    private var transferDone = false
    private var exited = false

    public private(set) var state: RecordingUiState
    /// Called after every change to `state`.
    public var onChange: (() -> Void)?

    public init(
        ballCount: Int,
        link: PhoneLink,
        scheduler: Scheduler,
        effects: WatchEffects,
        epochSeconds: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970) },
        log: @escaping (String) -> Void = { _ in }
    ) {
        self.ballCount = ballCount
        self.link = link
        self.scheduler = scheduler
        self.effects = effects
        self.epochSeconds = epochSeconds
        self.log = log
        detector = JugglingDetector(ballCount: ballCount)
        // A full run's samples, reserved once and reused for every run (REC-2).
        accelX.reserveCapacity(Self.maxRunSamples)
        accelY.reserveCapacity(Self.maxRunSamples)
        accelZ.reserveCapacity(Self.maxRunSamples)
        state = RecordingUiState(ballCount: ballCount)
        link.setMessageListener { [weak self] in self?.onPhoneMessage($0) }
    }

    // MARK: Buttons

    /// START drives every transition (REC-1). Returns whether it was used.
    @discardableResult
    public func onStart() -> Bool {
        if menu != nil || exited { return false }
        let handled: Bool
        switch phase {
        case .idle:
            startRun()
            handled = true
        case .recording:
            finishRun()
            handled = true
        case .labeling:
            confirmLabel()
            handled = true
        case .syncing:
            handled = false
        }
        publish()
        return handled
    }

    /// BACK on the idle "Press Start" screen closes this session and returns
    /// true, so the caller goes back to ball selection (REC-11): every run has
    /// already synced or been dropped, so nothing is lost. Otherwise it offers
    /// to discard while labelling and to quit while recording. While syncing
    /// it is consumed and does nothing (REC-9): falling through would close
    /// the session mid-transfer.
    @discardableResult
    public func onBack() -> Bool {
        if menu != nil || exited { return false }
        switch phase {
        case .idle:
            close()
            return true
        case .labeling: promptDiscard()
        case .syncing: break
        case .recording: promptQuit()
        }
        publish()
        return false
    }

    public func onUp() {
        guard phase == .labeling, menu == nil else { return }
        labelCount += 1
        publish()
    }

    /// REC-3: the label never goes below 0.
    public func onDown() {
        guard phase == .labeling, menu == nil else { return }
        if labelCount > 0 { labelCount -= 1 }
        publish()
    }

    // MARK: Menus

    private func promptDiscard() {
        menu = MenuSpec(
            kind: Self.menuDiscard,
            title: "Discard run?",
            items: [
                MenuItemSpec(id: Self.itemDiscardConfirm, label: "Yes"),
                MenuItemSpec(id: Self.itemDiscardCancel, label: "Continue"),
            ]
        )
    }

    private func promptQuit() {
        menu = MenuSpec(
            kind: Self.menuQuit,
            title: "Quit? Lose run",
            items: [
                MenuItemSpec(id: Self.itemQuitConfirm, label: "Yes"),
                MenuItemSpec(id: Self.itemQuitContinue, label: "Continue"),
            ]
        )
    }

    private func showSyncMenu() {
        if menu != nil { return }
        menu = MenuSpec(
            kind: Self.menuSync,
            title: failReason ?? "Sync failed",
            items: [
                MenuItemSpec(id: Self.itemSyncRetry, label: "Retry sync"),
                MenuItemSpec(id: Self.itemSyncSkip, label: "Skip (lose data)"),
                MenuItemSpec(id: Self.itemSyncQuit, label: "Quit"),
            ]
        )
    }

    public func onMenuSelect(_ itemId: String) {
        guard let open = menu else { return }
        menu = nil
        switch open.kind {
        case Self.menuDiscard:
            if itemId == Self.itemDiscardConfirm { discardRun() }
        case Self.menuQuit:
            if itemId == Self.itemQuitConfirm { exit() }
        case Self.menuSync:
            switch itemId {
            case Self.itemSyncRetry: onSyncRetry()
            case Self.itemSyncSkip: onSyncSkip()
            case Self.itemSyncQuit:
                cancelSyncTimer()
                cancelStatusTimer()
                exit()
            default: break
            }
        default:
            break
        }
        publish()
    }

    /// REC-10: backing out of any menu clears it. Out of the sync-failure menu
    /// it retries, the only choice that keeps the run; out of the quit or
    /// discard prompt it means Continue.
    public func onMenuBack() {
        guard let open = menu else { return }
        menu = nil
        if open.kind == Self.menuSync {
            onSyncRetry()
        }
        publish()
    }

    // MARK: Samples

    /// Samples count only while recording, and not while a menu is up.
    public func onSamples(_ samples: [AccelSample]) {
        if exited || menu != nil { return }
        for s in samples {
            if phase != .recording { break }
            // REC-2: cap the run length.
            if accelX.count >= Self.maxRunSamples {
                finishRun()
                break
            }
            accelX.append(s.x)
            accelY.append(s.y)
            accelZ.append(s.z)
            detector.processSample(s.x, s.y, s.z, nowMs: s.timeMs)
        }
        publish()
    }

    // MARK: Run lifecycle

    private func startRun() {
        failReason = nil
        errorMessage = nil
        phase = .recording
        clearSamples()
        detector = JugglingDetector(ballCount: ballCount)
    }

    /// REC-3: the detector's own count is the starting label.
    private func finishRun() {
        detectedCount = detector.currentCount
        labelCount = detectedCount
        phase = .labeling
    }

    /// Drop the unlabelled run; nothing was sent, so runsCompleted stays.
    private func discardRun() {
        cancelNextPartTimer()
        pendingPayload = nil
        resetRun()
        labelCount = 0
        detectedCount = 0
    }

    private func confirmLabel() {
        // REC-4: a one-line summary; the samples themselves go to the phone.
        log(
            "RUN_DATA,balls=\(ballCount),catches=\(labelCount),detected=\(detectedCount)," +
                "rate=\(Self.sampleRate),countMode=watch_hand,samples=\(accelX.count)"
        )

        sessionId = epochSeconds()
        totalChunks = (accelX.count + Self.chunkSamples - 1) / Self.chunkSamples
        chunkIndex = 0
        headerSent = false
        transferDone = false
        pendingPayload = buildPart()
        phase = .syncing
        attemptSync()
    }

    /// The part due next: header, one chunk per `chunkSamples` samples, then the end marker.
    private func buildPart() -> [String: Any] {
        if !headerSent {
            return [
                "type": WatchProtocol.typeRecStart,
                "id": sessionId,
                "countMode": "watch_hand",
                "balls": ballCount,
                "catches": labelCount,
                "detected": detectedCount,
                "sampleRate": Self.sampleRate,
                "samples": accelX.count,
                "chunks": totalChunks,
                "timestamp": sessionId,
            ]
        }
        if chunkIndex < totalChunks {
            let from = chunkIndex * Self.chunkSamples
            let to = min(from + Self.chunkSamples, accelX.count)
            return [
                "type": WatchProtocol.typeRecChunk,
                "id": sessionId,
                "i": chunkIndex,
                "x": Array(accelX[from..<to]),
                "y": Array(accelY[from..<to]),
                "z": Array(accelZ[from..<to]),
            ]
        }
        return ["type": WatchProtocol.typeRecEnd, "id": sessionId]
    }

    // MARK: Transfer

    private func attemptSync() {
        guard let payload = pendingPayload else { return }
        errorMessage = nil
        syncGeneration += 1
        let generation = syncGeneration
        startSyncTimer()
        startStatusTimer()
        link.send(payload) { [weak self] delivered in
            if delivered {
                self?.onTransmitDone(generation)
            } else {
                self?.onTransmitError(generation)
            }
        }
    }

    private func onTransmitDone(_ generation: Int) {
        guard generation == syncGeneration, phase == .syncing else { return }
        cancelSyncTimer()

        if !headerSent {
            headerSent = true
        } else if chunkIndex < totalChunks {
            chunkIndex += 1
        } else {
            // The end marker landed; the phone acks once it has written the run.
            transferDone = true
            startSyncTimer()
            publish()
            return
        }
        pendingPayload = buildPart()
        scheduleNextPart()
        publish()
    }

    private func onTransmitError(_ generation: Int) {
        if generation != syncGeneration { return } // late report from an abandoned attempt
        if phase != .syncing { return }
        cancelSyncTimer()
        cancelStatusTimer()
        failReason = "send failed"
        promptRetryOrQuit()
        publish()
    }

    private func onSyncTimeout() {
        syncTimer = nil
        if phase == .syncing {
            cancelStatusTimer()
            failReason = "no reply from phone"
            promptRetryOrQuit()
            publish()
        }
    }

    private func promptRetryOrQuit() {
        errorMessage = "Sync failed"
        showSyncMenu()
    }

    private func onPhoneMessage(_ data: [String: Any]) {
        guard data["type"] as? String == WatchProtocol.typeAck else { return }
        guard phase == .syncing else { return }
        // REC-8: accept only the ack for the run that is syncing now. A late
        // ack for a timed-out or skipped run must not mark this one delivered.
        guard WatchProtocol.int64(data["timestamp"]) == sessionId else { return }

        cancelSyncTimer()
        cancelStatusTimer()
        menu = nil
        cancelNextPartTimer()
        pendingPayload = nil
        resetRun()
        runsCompleted += 1
        publish()
    }

    private func onSyncRetry() {
        errorMessage = nil
        if pendingPayload == nil {
            // The ack landed after the menu opened; nothing is left to send.
            failReason = nil
            phase = .idle
            runsCompleted += 1
            return
        }
        // Restart from the header: the phone discards a partial transfer
        // when a new rec_start for the same run arrives.
        headerSent = false
        chunkIndex = 0
        transferDone = false
        pendingPayload = buildPart()
        scheduleNextPart()
    }

    private func onSyncSkip() {
        syncGeneration += 1
        failReason = nil
        cancelSyncTimer()
        cancelStatusTimer()
        cancelNextPartTimer()
        pendingPayload = nil
        resetRun()
        runsCompleted += 1
    }

    /// REC-7: the next part goes out from a timer rather than from inside the
    /// delivery callback, as on the Garmin, where sending from the callback
    /// wedged the transmit slot.
    private func scheduleNextPart() {
        cancelNextPartTimer()
        nextPartTimer = scheduler.schedule(afterMs: Self.nextPartDelayMs) { [weak self] in
            guard let self else { return }
            nextPartTimer = nil
            if phase == .syncing {
                attemptSync()
                publish()
            }
        }
    }

    private func resetRun() {
        detector = JugglingDetector(ballCount: ballCount)
        clearSamples()
        phase = .idle
        errorMessage = nil
    }

    private func clearSamples() {
        accelX.removeAll(keepingCapacity: true)
        accelY.removeAll(keepingCapacity: true)
        accelZ.removeAll(keepingCapacity: true)
    }

    private func startSyncTimer() {
        cancelSyncTimer()
        syncTimer = scheduler.schedule(afterMs: Self.syncTimeoutMs) { [weak self] in self?.onSyncTimeout() }
    }

    private func cancelSyncTimer() {
        syncTimer?.cancel()
        syncTimer = nil
    }

    private func cancelNextPartTimer() {
        nextPartTimer?.cancel()
        nextPartTimer = nil
    }

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
        close()
        effects.exit()
    }

    /// Stops every timer and the phone listener; the session ignores all input after.
    private func close() {
        exited = true
        cancelSyncTimer()
        cancelStatusTimer()
        cancelNextPartTimer()
        link.setMessageListener(nil)
        publish()
    }

    private func publish() {
        state = snapshot()
        onChange?()
    }

    private func snapshot() -> RecordingUiState {
        RecordingUiState(
            ballCount: ballCount,
            phase: phase,
            runsCompleted: runsCompleted,
            recordedSamples: accelX.count,
            liveCount: detector.currentCount,
            detectedCount: detectedCount,
            labelCount: labelCount,
            syncDots: syncDots,
            headerSent: headerSent,
            transferDone: transferDone,
            chunkIndex: chunkIndex,
            totalChunks: totalChunks,
            errorMessage: errorMessage,
            failReason: failReason,
            dataPending: pendingPayload != nil,
            menu: menu,
            exited: exited
        )
    }
}
