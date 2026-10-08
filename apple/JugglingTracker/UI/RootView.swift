import JugglingCore
import SwiftUI

enum Screen: Hashable {
    case phoneSession
    case settings
}

/// The app's navigation: the home screen, with the phone session and Settings
/// pushed on top, plus what can appear over any of them (raw recording, the
/// short message banner). Counterpart of the Android `JugglingTrackerApp`.
struct RootView: View {
    @Environment(TrackerModel.self) private var model
    @State private var path: [Screen] = []

    var body: some View {
        NavigationStack(path: $path) {
            TrackerScreen(path: $path)
                .navigationTitle("Juggling Tracker")
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            path.append(.settings)
                        } label: {
                            Image(systemName: "gearshape")
                        }
                        .accessibilityLabel("Settings")
                    }
                }
                .navigationDestination(for: Screen.self) { screen in
                    switch screen {
                    case .phoneSession:
                        PhoneSessionScreen(path: $path)
                    case .settings:
                        SettingsScreen()
                    }
                }
        }
        .rawRecordingFlow()
        .toastBanner(message: Binding(get: { model.toast }, set: { model.toast = $0 }))
        // Hold the display awake while samples are being collected: juggling
        // never touches the screen, and a locked phone stops the accelerometer.
        .onChange(of: model.shouldKeepScreenOn, initial: true) { _, keepOn in
            UIApplication.shared.isIdleTimerDisabled = keepOn
        }
    }
}

/// A message shown at the bottom for a few seconds, like an Android toast.
private struct ToastBanner: ViewModifier {
    @Binding var message: String?

    func body(content: Content) -> some View {
        content.overlay(alignment: .bottom) {
            if let message {
                Text(message)
                    .font(.subheadline)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(.thinMaterial, in: Capsule())
                    .padding(.bottom, 24)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .task(id: message) {
                        try? await Task.sleep(nanoseconds: 3_000_000_000)
                        withAnimation { self.message = nil }
                    }
                    .accessibilityAddTraits(.isStaticText)
            }
        }
        .animation(.default, value: message)
    }
}

extension View {
    func toastBanner(message: Binding<String?>) -> some View {
        modifier(ToastBanner(message: message))
    }
}
