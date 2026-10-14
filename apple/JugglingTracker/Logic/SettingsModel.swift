import Foundation
import JugglingCore
import Observation

/// Everything the Settings screen changes: the watch, voice, privacy, the
/// juggler and the backup. The screen talks only to this and to the
/// recordings (`RawRecordingModel`). Counterpart of the Android app's
/// `SettingsModel`.
@MainActor
@Observable
final class SettingsModel {
    /// The stored choices; the screen binds its controls to them.
    let values: AppSettings
    @ObservationIgnored private let history: HistoryModel

    init(values: AppSettings, history: HistoryModel) {
        self.values = values
        self.history = history
    }

    var currentJuggler: Juggler? { values.currentJuggler }

    func setAnalyticsEnabled(_ enabled: Bool) {
        values.isAnalyticsEnabled = enabled
        Telemetry.shared.setEnabled(enabled)
    }

    /// Whether a voice announcement is due at `count` catches.
    func shouldAnnounce(_ count: Int) -> Bool {
        values.isVoiceEnabled && values.voiceInterval > 0 && count > 0 && count % values.voiceInterval == 0
    }

    // MARK: - Backup (see SessionBackup)

    /// Turns the weekly backup on or off. Turning it on needs a folder; returns
    /// false when none has been picked yet, leaving the backup off.
    func setBackupEnabled(_ enabled: Bool) -> Bool {
        if !enabled {
            values.isBackupEnabled = false
            SessionBackup.cancelSchedule()
            return true
        }
        guard SessionBackup.folder(settings: values) != nil else { return false }
        values.isBackupEnabled = true
        SessionBackup.schedule()
        return true
    }

    /// Uses `folder` for the backup and turns the weekly backup on; false when
    /// the app may not keep writing there.
    func connectBackupFolder(_ folder: URL) -> Bool {
        guard SessionBackup.connect(folder: folder, settings: values) else { return false }
        values.isBackupEnabled = true
        SessionBackup.schedule()
        return true
    }

    func backUpNow() -> SessionBackup.Result {
        SessionBackup.backUp(sessions: history.sessions, settings: values)
    }

    /// Writes the backup when the week is up, and keeps the next one scheduled.
    func backUpIfDue() {
        if SessionBackup.isDue(settings: values) { _ = backUpNow() }
        if values.isBackupEnabled { SessionBackup.schedule() }
    }

    /// Adds a backup's sessions to the history, skipping those already here.
    /// Returns how many were added.
    func restore(_ backup: [SessionSummary]) -> Int {
        history.restore(backup)
    }
}
