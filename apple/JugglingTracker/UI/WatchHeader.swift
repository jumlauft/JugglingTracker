import SwiftUI

/// What the watch card shows and does in one state.
struct WatchCardState: Equatable {
    enum Tone: Equatable { case neutral, ready, receiving, error }
    enum Tap: Equatable { case none, linkHelp, sessionHelp }

    var tone: Tone
    var title: String
    var detail: String?
    var tap: Tap

    static func garmin(_ status: GarminConnectionStatus, message: String) -> WatchCardState {
        let notLinked = "Watch is not linked correctly to phone"
        switch status {
        case .ready:
            return .init(tone: .ready, title: "Ready to record with Garmin", detail: nil, tap: .sessionHelp)
        case .receiving:
            return .init(tone: .receiving, title: "Receiving data...", detail: nil, tap: .none)
        case .notInitialized:
            return .init(tone: .neutral, title: "Record with Garmin watch", detail: nil, tap: .none)
        case .watchAppMissing:
            return .init(tone: .error, title: "Juggling app is not installed on the watch", detail: nil, tap: .linkHelp)
        case .connectIQMissing, .disconnected:
            return .init(tone: .error, title: notLinked, detail: nil, tap: .linkHelp)
        case .bluetoothDisabled, .noPairedDevices, .sdkError:
            return .init(tone: .error, title: notLinked, detail: message.isEmpty ? nil : message, tap: .linkHelp)
        }
    }

    static func appleWatch(_ status: AppleWatchConnectionStatus) -> WatchCardState {
        let notConnected = "Watch is not connected to phone"
        switch status {
        case .ready:
            return .init(tone: .ready, title: "Ready to record with Apple Watch", detail: nil, tap: .sessionHelp)
        case .receiving:
            return .init(tone: .receiving, title: "Receiving data...", detail: nil, tap: .none)
        case .checking:
            return .init(tone: .neutral, title: "Record with Apple Watch", detail: nil, tap: .none)
        case .noWatch:
            return .init(tone: .error, title: notConnected, detail: "No Apple Watch is paired with this iPhone", tap: .linkHelp)
        case .watchAppMissing:
            return .init(tone: .error, title: "Juggling app is not installed on the watch", detail: nil, tap: .linkHelp)
        case .unavailable:
            return .init(tone: .error, title: notConnected, detail: "The Apple Watch link is not part of this build yet", tap: .linkHelp)
        }
    }
}

/// The two cards at the top of the home screen: the status of the watch
/// picked in Settings, and start-with-phone. Green when the watch is ready (a
/// tap explains how to start), red when not (a tap shows how to connect), and
/// inert while connecting or receiving. The card keeps one size whatever it
/// shows, sized for the longest text it can show.
struct WatchHeader: View {
    @Environment(TrackerModel.self) private var model
    @Environment(AppServices.self) private var services
    let onPhoneTap: () -> Void
    @State private var help: WatchHelp?

    private var watchType: WatchType { model.settings.watchType }

    private var card: WatchCardState {
        switch watchType {
        case .garmin: WatchCardState.garmin(model.garminStatus, message: model.garminMessage)
        case .appleWatch: WatchCardState.appleWatch(model.appleWatchStatus)
        }
    }

    private var allStates: [WatchCardState] {
        switch watchType {
        case .garmin: GarminConnectionStatus.allCases.map { WatchCardState.garmin($0, message: model.garminMessage) }
        case .appleWatch: AppleWatchConnectionStatus.allCases.map { WatchCardState.appleWatch($0) }
        }
    }

    var body: some View {
        HStack(spacing: 12) {
            Button {
                switch card.tap {
                case .none: break
                case .linkHelp: help = WatchHelp(watchType: watchType, kind: .link)
                case .sessionHelp: help = WatchHelp(watchType: watchType, kind: .session)
                }
            } label: {
                VStack(spacing: 8) {
                    Image(systemName: "applewatch")
                        .font(.title2)
                    // The other states' texts sit invisibly underneath, so the
                    // card is as tall as its tallest state in every state.
                    ZStack {
                        ForEach(Array(allStates.enumerated()), id: \.offset) { _, state in
                            CardText(title: state.title, detail: state.detail).hidden()
                        }
                        CardText(title: card.title, detail: card.detail)
                    }
                }
                .padding(12)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .foregroundStyle(cardForeground(card.tone))
                .background(cardBackground(card.tone), in: RoundedRectangle(cornerRadius: 12))
            }
            .buttonStyle(.plain)
            .disabled(card.tap == .none)

            Button(action: onPhoneTap) {
                VStack(spacing: 8) {
                    Image(systemName: "iphone")
                        .font(.title2)
                    Text("Start Session with Phone")
                        .font(.subheadline.bold())
                        .multilineTextAlignment(.center)
                }
                .padding(12)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .foregroundStyle(Color.accentColor)
                .background(Color.accentColor.opacity(0.15), in: RoundedRectangle(cornerRadius: 12))
            }
            .buttonStyle(.plain)
        }
        .fixedSize(horizontal: false, vertical: true)
        .animation(.default, value: card)
        .sheet(item: $help) { help in
            WatchHelpView(help: help) { services.garminLink.chooseWatch() }
                .presentationDetents([.medium, .large])
        }
    }

    private func cardBackground(_ tone: WatchCardState.Tone) -> Color {
        switch tone {
        case .neutral: Color(uiColor: .secondarySystemBackground)
        case .ready: .green.opacity(0.2)
        case .receiving: .orange.opacity(0.25)
        case .error: .red.opacity(0.15)
        }
    }

    private func cardForeground(_ tone: WatchCardState.Tone) -> Color {
        switch tone {
        case .neutral: .secondary
        case .ready: .green
        case .receiving: .orange
        case .error: .red
        }
    }
}

private struct CardText: View {
    let title: String
    let detail: String?

    var body: some View {
        VStack(spacing: 4) {
            Text(title)
                .font(.subheadline.bold())
            if let detail {
                Text(detail)
                    .font(.caption)
                    .opacity(0.8)
            }
        }
        .multilineTextAlignment(.center)
    }
}
