import Foundation
import JugglingCore
import Observation

/// Holds the app's models and wires them together; each screen talks to the
/// models it shows:
///
/// - `watchStatus`: the watch card on the home screen
/// - `history`: the session history
/// - `phoneSession`: a session counted with the phone's own sensor
/// - `rawRecording`: raw recordings for the detection corpus
/// - `settings`: everything on the Settings screen but the recordings
///
/// What the watches send reaches them through the `WatchInbox`. The Android
/// app's `JugglingViewModel` holds the same models.
@MainActor
@Observable
final class TrackerModel {
    let watchStatus: WatchStatusModel
    let history: HistoryModel
    let settings: SettingsModel
    let phoneSession: PhoneSessionModel
    let rawRecording: RawRecordingModel
    @ObservationIgnored let inbox: WatchInbox
    /// Speaks announcements; the app wires it to `Voice`.
    @ObservationIgnored var speak: (String) -> Void = { _ in }

    /// The short message banner, like an Android toast.
    let toasts = ToastCenter()

    @ObservationIgnored private var inboxListener: UUID?

    init(settings: AppSettings, sessionStore: SessionStore?, recordingStore: RecordingStore?, inbox: WatchInbox? = nil) {
        let history = HistoryModel(sessionStore: sessionStore)
        watchStatus = WatchStatusModel()
        self.history = history
        self.settings = SettingsModel(values: settings, history: history)
        phoneSession = PhoneSessionModel(history: history)
        rawRecording = RawRecordingModel(recordingStore: recordingStore) { [settings] in settings.currentJuggler }
        self.inbox = inbox ?? WatchInbox(sessions: sessionStore, recordings: recordingStore) { [settings] in
            settings.currentJuggler
        }
        phoneSession.onCatch = { [weak self] in self?.announce($0) }
        rawRecording.onCatch = { [weak self] in self?.announce($0) }
        inboxListener = self.inbox.addListener { [weak self] event in
            MainActor.assumeIsolated { self?.handle(event) }
        }
    }

    private func handle(_ event: WatchInbox.Event) {
        switch event {
        case let .receiving(source, more):
            watchStatus.showReceiving(source, more: more)
        case let .sessionStored(session):
            history.showReceived(session)
            say(String(localized: "Received \(session.runCount) runs for \(session.ballCount) balls"))
        case .recordingStored:
            rawRecording.refresh()
        }
    }

    private func announce(_ count: Int) {
        if settings.shouldAnnounce(count) { speak(String(count)) }
    }

    private func say(_ text: String) {
        toasts.show(text)
        speak(text)
    }

    /// True while the accelerometer has to keep streaming, so the screen holds
    /// the display awake for exactly that long: nobody touches the phone while
    /// juggling, and a locked phone stops delivering samples.
    var shouldKeepScreenOn: Bool { isUsingPhoneSensor }

    /// Whether a phone session or raw recording still wants accelerometer
    /// samples. False once a frozen sensor ended the session, so the app can
    /// let go of the sensor.
    var isUsingPhoneSensor: Bool {
        phoneSession.state.isRecording || rawRecording.state.step == .recording
    }

    /// Hands a batch of accelerometer samples to whichever is capturing.
    func processPhoneSamples(_ samples: [PhoneAccelSample]) {
        guard !samples.isEmpty else { return }
        rawRecording.process(samples)
        phoneSession.process(samples)
    }

    /// Ends the phone session and stores it. Returns false when there was no run to save.
    @discardableResult
    func stopPhoneSessionAndSave(at stop: Date = Date()) -> Bool {
        let balls = phoneSession.state.selectedBallCount
        guard let runs = phoneSession.stopAndSave(at: stop) else { return false }
        say(String(localized: "Saved \(runs) phone runs for \(balls) balls"))
        return true
    }
}
