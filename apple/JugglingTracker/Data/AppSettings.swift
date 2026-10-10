import Foundation
import Observation

/// Which watch the user records with; it decides what the home screen's watch
/// card shows. The Android app offers Garmin and Wear OS; the iPhone app
/// offers Garmin and Apple Watch.
enum WatchType: String, CaseIterable, Identifiable {
    case garmin
    case appleWatch

    var id: String { rawValue }

    var label: String {
        switch self {
        case .garmin: "Garmin"
        case .appleWatch: "Apple Watch"
        }
    }
}

/// The user's choices, kept in UserDefaults. Counterpart of the Android app's
/// `data/SettingsManager.kt`, with the same defaults.
@Observable
final class AppSettings {
    @ObservationIgnored private let defaults: UserDefaults

    private enum Key {
        static let analytics = "analytics_enabled"
        static let voice = "voice_enabled"
        static let voiceInterval = "voice_interval"
        static let watchType = "watch_type"
        static let jugglerName = "juggler_name"
        static let watchHand = "watch_hand"
        static let firstThrow = "first_throw_hand"
        static let backupEnabled = "backup_enabled"
        static let backupBookmark = "backup_folder_bookmark"
        static let backupFolderName = "backup_folder_name"
        static let lastBackup = "last_backup_millis"
        static let lastBackupFailed = "last_backup_failed"
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        isAnalyticsEnabled = defaults.object(forKey: Key.analytics) as? Bool ?? true
        isVoiceEnabled = defaults.object(forKey: Key.voice) as? Bool ?? true
        voiceInterval = defaults.object(forKey: Key.voiceInterval) as? Int ?? 10
        watchType = defaults.string(forKey: Key.watchType).flatMap(WatchType.init(rawValue:)) ?? .garmin
        jugglerName = defaults.string(forKey: Key.jugglerName) ?? ""
        watchHand = defaults.string(forKey: Key.watchHand)
        firstThrowHand = defaults.string(forKey: Key.firstThrow)
        isBackupEnabled = defaults.bool(forKey: Key.backupEnabled)
        backupBookmark = defaults.data(forKey: Key.backupBookmark)
        backupFolderName = defaults.string(forKey: Key.backupFolderName)
        lastBackupMillis = Int64(defaults.double(forKey: Key.lastBackup))
        lastBackupFailed = defaults.bool(forKey: Key.lastBackupFailed)
    }

    var isAnalyticsEnabled: Bool {
        didSet { defaults.set(isAnalyticsEnabled, forKey: Key.analytics) }
    }

    var isVoiceEnabled: Bool {
        didSet { defaults.set(isVoiceEnabled, forKey: Key.voice) }
    }

    var voiceInterval: Int {
        didSet { defaults.set(voiceInterval, forKey: Key.voiceInterval) }
    }

    var watchType: WatchType {
        didSet { defaults.set(watchType.rawValue, forKey: Key.watchType) }
    }

    /// Who juggles, as last entered in Settings or when exporting; blank until then.
    private(set) var jugglerName: String
    /// The wrist the watch (or phone) is on; nil until picked.
    private(set) var watchHand: String?
    /// The hand that made the first throw; nil until picked.
    private(set) var firstThrowHand: String?

    /// Who is juggling, once a name and both hands have been entered.
    var currentJuggler: Juggler? {
        let name = jugglerName.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty, let watchHand, let firstThrowHand else { return nil }
        return Juggler(name: name, hand: watchHand, firstThrow: firstThrowHand)
    }

    func updateJuggler(name: String, hand: String, firstThrow: String) {
        jugglerName = name
        watchHand = hand
        firstThrowHand = firstThrow
        defaults.set(name, forKey: Key.jugglerName)
        defaults.set(hand, forKey: Key.watchHand)
        defaults.set(firstThrow, forKey: Key.firstThrow)
    }

    // MARK: Backup

    /// Whether the weekly backup is on. It writes into the folder the user picked.
    var isBackupEnabled: Bool {
        didSet { defaults.set(isBackupEnabled, forKey: Key.backupEnabled) }
    }

    /// A security-scoped bookmark to the backup folder (usually in iCloud Drive).
    private(set) var backupBookmark: Data?
    /// The folder's name as the picker showed it, for Settings to display.
    private(set) var backupFolderName: String?
    /// When the last backup was written, in epoch ms; 0 for never.
    private(set) var lastBackupMillis: Int64
    /// True when the last backup attempt could not write the file.
    private(set) var lastBackupFailed: Bool

    func updateBackupFolder(bookmark: Data, name: String?) {
        backupBookmark = bookmark
        backupFolderName = name
        lastBackupFailed = false
        defaults.set(bookmark, forKey: Key.backupBookmark)
        defaults.set(name, forKey: Key.backupFolderName)
        defaults.set(false, forKey: Key.lastBackupFailed)
    }

    func recordBackupResult(succeeded: Bool, at millis: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) {
        lastBackupFailed = !succeeded
        defaults.set(!succeeded, forKey: Key.lastBackupFailed)
        if succeeded {
            lastBackupMillis = millis
            defaults.set(Double(millis), forKey: Key.lastBackup)
        }
    }
}
