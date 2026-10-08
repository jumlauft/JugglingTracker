import Foundation
#if canImport(FirebaseCore)
import FirebaseAnalytics
import FirebaseCore
import FirebaseCrashlytics
#endif

/// Usage events and crash reports, sent to Firebase only while the user allows
/// it under Settings > Privacy ("Share app activity and crash reports"), the
/// same switch and the same events as the Android app.
///
/// Firebase needs the app's GoogleService-Info.plist from the Firebase console
/// (see README.md). Without it, or in unit tests, everything here does nothing.
final class Telemetry {
    static let shared = Telemetry()

    private(set) var isConfigured = false

    /// Starts Firebase when the app's config file is there. Call once at launch.
    func start(enabled: Bool) {
        #if canImport(FirebaseCore)
        guard !isConfigured, Self.hasFirebaseConfig else { return }
        FirebaseApp.configure()
        isConfigured = true
        setEnabled(enabled)
        #endif
    }

    /// Turns collection on or off, as the Privacy switch says.
    func setEnabled(_ enabled: Bool) {
        #if canImport(FirebaseCore)
        guard isConfigured else { return }
        Analytics.setAnalyticsCollectionEnabled(enabled)
        Crashlytics.crashlytics().setCrashlyticsCollectionEnabled(enabled)
        #endif
    }

    func log(_ event: String, _ parameters: [String: Any] = [:]) {
        #if canImport(FirebaseCore)
        guard isConfigured else { return }
        Analytics.logEvent(event, parameters: parameters)
        #endif
    }

    /// A handled error, as `CrashlyticsUtils.recordException` on Android.
    func record(_ error: Error) {
        #if canImport(FirebaseCore)
        guard isConfigured else { return }
        Crashlytics.crashlytics().record(error: error)
        #endif
    }

    /// True when a real GoogleService-Info.plist is bundled.
    static var hasFirebaseConfig: Bool {
        guard let url = Bundle.main.url(forResource: "GoogleService-Info", withExtension: "plist"),
              let plist = NSDictionary(contentsOf: url),
              let appID = plist["GOOGLE_APP_ID"] as? String
        else { return false }
        return !appID.isEmpty && !appID.contains("PLACEHOLDER")
    }
}
