import SwiftUI

@main
struct JugglingTrackerApp: App {
    @State private var app = AppServices()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(app.model)
                .environment(app.model.watchStatus)
                .environment(app.model.history)
                .environment(app.model.phoneSession)
                .environment(app.model.rawRecording)
                .environment(app.model.settings)
                .environment(app.model.toasts)
                .environment(app)
                // Garmin Connect hands back the watches the user chose.
                .onOpenURL { url in app.garminLink.handleOpenURL(url) }
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
    @ObservationIgnored let garminLink: GarminLink
    @ObservationIgnored private var appleWatchLink: WatchLink?

    init() {
        let screenshots = ScreenshotMode.isOn
        let settings = screenshots ? ScreenshotMode.settings() : AppSettings()
        if !screenshots { Telemetry.shared.start(enabled: settings.isAnalyticsEnabled) }
        let sessions = screenshots ? ScreenshotMode.sessionStore() : SessionStore.standard { Telemetry.shared.record($0) }
        let model = TrackerModel(settings: settings, sessionStore: sessions, recordingStore: screenshots ? nil : .standard())
        let garmin = GarminLink(client: ConnectIQSDKClient(), inbox: model.inbox) { [weak model] status, message in
            Task { @MainActor in model?.watchStatus.onGarminLinkStatus(status, message: message) }
        }
        self.model = model
        garminLink = garmin

        let voice = voice
        model.speak = { voice.speak($0) }

        let apple = AppleWatchLink(inbox: model.inbox, session: PhoneWatchSession.makeIfSupported()) { [weak model] status in
            Task { @MainActor in model?.watchStatus.onAppleWatchLinkStatus(status) }
        }
        appleWatchLink = apple
        if screenshots {
            model.watchStatus.onGarminLinkStatus(.ready, message: "Connected to Forerunner 265")
            model.watchStatus.onAppleWatchLinkStatus(.ready)
            if let balls = ScreenshotMode.ballCount { model.history.selectHistoryBallCount(balls) }
            return
        }
        // The Garmin link only starts once Garmin is the chosen watch, as on
        // Android, where it also asks for Bluetooth permissions.
        if settings.watchType == .garmin { garmin.start() }
        settings.onWatchTypeChange = { [weak garmin] type in
            if type == .garmin { garmin?.start() }
        }
        apple.start()
    }

    func didBecomeActive() {
        model.settings.backUpIfDue()
    }

    func didEnterBackground() {
        if model.settings.values.isBackupEnabled { SessionBackup.schedule() }
    }

    func runScheduledBackup() async {
        if model.settings.values.isBackupEnabled {
            _ = model.settings.backUpNow()
            SessionBackup.schedule()
        }
    }

    // MARK: Accelerometer

    /// Starts sampling for a phone session. Returns false when the phone has no accelerometer.
    func startPhoneSession(ballCount: Int) -> Bool {
        model.phoneSession.start(ballCount: ballCount)
        return startSensor()
    }

    func stopPhoneSession() -> Bool {
        if !(model.rawRecording.state.step == .recording) { accelerometer.stop() }
        return model.stopPhoneSessionAndSave()
    }

    func cancelPhoneSession() {
        if !(model.rawRecording.state.step == .recording) { accelerometer.stop() }
        model.phoneSession.cancel()
    }

    func startRawRecording(ballCount: Int) -> Bool {
        model.rawRecording.confirmBalls(ballCount)
        return startSensor()
    }

    func stopRawRecording() {
        if !model.phoneSession.state.isRecording { accelerometer.stop() }
        model.rawRecording.stop()
    }

    func cancelRawRecording() {
        if !model.phoneSession.state.isRecording { accelerometer.stop() }
        model.rawRecording.cancel()
    }

    private func startSensor() -> Bool {
        let model = model
        let accelerometer = accelerometer
        // PhoneAccelerometer delivers its batches on the main thread.
        let started = accelerometer.start { samples in
            MainActor.assumeIsolated {
                model.processPhoneSamples(samples)
                // A frozen sensor ends the session from inside the model; stop
                // listening then rather than at full rate until it is left.
                if !model.isUsingPhoneSensor { accelerometer.stop() }
            }
        }
        if !started {
            model.phoneSession.markSensorUnavailable()
            if model.rawRecording.state.step == .recording { model.rawRecording.cancel() }
        }
        return started
    }
}
