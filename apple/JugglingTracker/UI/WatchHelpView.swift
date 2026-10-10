import SwiftUI

/// Which instructions the watch card opens.
struct WatchHelp: Identifiable {
    enum Kind { case link, session }

    let watchType: WatchType
    let kind: Kind

    var id: String { "\(watchType.rawValue)-\(kind)" }
}

/// The checklist for connecting the watch, or how to start a session on it.
struct WatchHelpView: View {
    let help: WatchHelp
    /// Opens Garmin Connect to choose the watch this app talks to.
    var chooseGarminWatch: () -> Void = {}
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    /// Garmin Connect on the App Store.
    static let garminConnectURL = URL(string: "https://apps.apple.com/app/garmin-connect/id583446403")!

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    switch (help.watchType, help.kind) {
                    case (.garmin, .link):
                        intro("To sync with your watch, please ensure:")
                        Text("1. Bluetooth is turned ON.")
                        Text("2. This app may use Bluetooth.")
                        Text("3. The \"Garmin Connect\" app is installed and your watch is paired within it.")
                        Text("4. Your watch is chosen for this app in Garmin Connect (button below). Choose it again after pairing a new watch.")
                        Text("5. The \"Juggling Tracker\" watch app is installed via the \"Connect IQ Store\".")
                        Text("6. The Juggling activity is open on your watch.")
                        Text("7. Your watch is within Bluetooth range of your phone.")
                        Button("Choose Watch in Garmin Connect") {
                            dismiss()
                            chooseGarminWatch()
                        }
                        .buttonStyle(.borderedProminent)
                        .padding(.top, 8)
                        Button("Get Garmin Connect") { openURL(Self.garminConnectURL) }
                            .buttonStyle(.bordered)
                    case (.garmin, .session):
                        Text("""
                        Your setup is looking good. Download the "Juggling Tracker" app from the Connect IQ Store to your Garmin watch, then on your watch:
                        1. Start the activity "Juggling".
                        2. Select Juggle (not Record) with Up/Down and press Start.
                        3. Select the number of balls with Up/Down and press Start. The session starts.

                        To end the session, press Start and choose "Sync and quit" while the phone is nearby and this app is opened to transfer the session data to the phone.
                        """)
                    case (.appleWatch, .link):
                        intro("To sync with your watch, please ensure:")
                        Text("1. Bluetooth is turned ON.")
                        Text("2. Your Apple Watch is paired with this iPhone in the Watch app.")
                        Text("3. The \"Juggling Tracker\" app is installed on the watch. In the Watch app, find it under Available Apps and tap Install.")
                        Text("4. Your watch is within Bluetooth range of your phone.")
                    case (.appleWatch, .session):
                        Text("""
                        Your watch is connected. On your watch:
                        1. Open the "Juggling Tracker" app.
                        2. Select Juggle (not Record) and tap Start.
                        3. Select the number of balls and tap Start. The session starts.

                        To end the session, tap End and choose "Sync and quit" while the phone is nearby to transfer the session data to the phone.
                        """)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
    }

    private var title: String {
        switch (help.watchType, help.kind) {
        case (.garmin, .link): "Garmin Connectivity Checklist"
        case (.garmin, .session): "Start Garmin Session"
        case (.appleWatch, .link): "Apple Watch Connectivity Checklist"
        case (.appleWatch, .session): "Start Apple Watch Session"
        }
    }

    private func intro(_ text: String) -> some View {
        Text(text).bold()
    }
}
