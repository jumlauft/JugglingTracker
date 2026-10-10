import SwiftUI

@main
struct JugglingTrackerApp: App {
    @State private var app = AppServices()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(app.model)
                .environment(app)
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active: app.didBecomeActive()
            case .background: app.didEnterBackground()
            default: break
            }
        }
        .backgroundTask(.appRefresh(SessionBackup.taskIdentifier)) {
            await app.runScheduledBackup()
        }
    }
}

/// One of each per app: the stores, the watch links, the accelerometer and
/// the voice, wired to the model. Counterpart of the Android app's
/// `JugglingTrackerApplication` and `MainActivity`.
@MainActor
@Observable
final class AppServices {
    let model: TrackerModel
    @ObservationIgnored let accelerometer = PhoneAccelerometer()
    @ObservationIgnored private let voice = Voice()
    @ObservationIgnored private var garminLink: WatchLink?
    @ObservationIgnored private var appleWatchLink: WatchLink?

    init() {
        let settings = AppSettings()
        Telemetry.shared.start(enabled: settings.isAnalyticsEnabled)
        let sessions = SessionStore.standard { Telemetry.shared.record($0) }
        model = TrackerModel(settings: settings, sessionStore: sessions, recordingStore: .standard())
        let voice = voice
        model.speak = { voice.speak($0) }

        let garmin = GarminLinkStub(inbox: model.inbox) { [weak model] status, message in
            Task { @MainActor in model?.onGarminLinkStatus(status, message: message) }
        }
        let apple = AppleWatchLink(inbox: model.inbox, session: PhoneWatchSession.makeIfSupported()) { [weak model] status in
            Task { @MainActor in model?.onAppleWatchLinkStatus(status) }
        }
        garminLink = garmin
        appleWatchLink = apple
        garmin.start()
        apple.start()
    }

    func didBecomeActive() {
        if SessionBackup.isDue(settings: model.settings) {
            _ = SessionBackup.backUp(sessions: model.sessions, settings: model.settings)
        }
        if model.settings.isBackupEnabled { SessionBackup.schedule() }
    }

    func didEnterBackground() {
        if model.settings.isBackupEnabled { SessionBackup.schedule() }
    }

    func runScheduledBackup() async {
        if model.settings.isBackupEnabled {
            _ = SessionBackup.backUp(sessions: model.sessions, settings: model.settings)
            SessionBackup.schedule()
        }
    }

    // MARK: Accelerometer

    /// Starts sampling for a phone session. Returns false when the phone has no accelerometer.
    func startPhoneSession(ballCount: Int) -> Bool {
        model.startPhoneSession(ballCount: ballCount)
        return startSensor()
    }

    func stopPhoneSession() -> Bool {
        if !(model.rawRecording.step == .recording) { accelerometer.stop() }
        return model.stopPhoneSessionAndSave()
    }

    func cancelPhoneSession() {
        if !(model.rawRecording.step == .recording) { accelerometer.stop() }
        model.cancelPhoneSession()
    }

    func startRawRecording(ballCount: Int) -> Bool {
        model.confirmRawRecordingBalls(ballCount)
        return startSensor()
    }

    func stopRawRecording() {
        if !model.phoneSession.isRecording { accelerometer.stop() }
        model.stopRawRecording()
    }

    func cancelRawRecording() {
        if !model.phoneSession.isRecording { accelerometer.stop() }
        model.cancelRawRecording()
    }

    private func startSensor() -> Bool {
        let model = model
        // PhoneAccelerometer delivers its batches on the main thread.
        let started = accelerometer.start { samples in
            MainActor.assumeIsolated { model.processPhoneSamples(samples) }
        }
        if !started {
            model.markPhoneSensorUnavailable("Phone accelerometer unavailable")
            if model.rawRecording.step == .recording { model.cancelRawRecording() }
        }
        return started
    }
}
