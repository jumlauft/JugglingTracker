import SwiftUI

/// The live phone session: pick the balls and start, then the count, the
/// session stats and Save or Cancel.
struct PhoneSessionScreen: View {
    @Environment(TrackerModel.self) private var model
    @Environment(AppServices.self) private var services
    @Binding var path: [Screen]
    @State private var confirmQuit = false

    var body: some View {
        let state = model.phoneSession
        ScrollView {
            VStack(spacing: 16) {
                if state.isRecording {
                    recording(state)
                } else {
                    setup(state)
                }
            }
            .padding(16)
        }
        .navigationTitle("Record a Juggling Session")
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(state.isRecording)
        .toolbar {
            if state.isRecording {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        confirmQuit = true
                    } label: {
                        Label("Back", systemImage: "chevron.backward")
                    }
                }
            }
        }
        .confirmationDialog(
            "Quit Session?", isPresented: $confirmQuit, titleVisibility: .visible
        ) {
            Button("Save and quit session") {
                _ = services.stopPhoneSession()
                leave()
            }
            Button("Quit without saving data", role: .destructive) {
                services.cancelPhoneSession()
                leave()
            }
            Button("Continue session", role: .cancel) {}
        } message: {
            Text("You are currently recording a session. What would you like to do?")
        }
        .onDisappear {
            // Leaving with the system back gesture before starting, or after
            // the session ended, leaves nothing running.
            if !model.phoneSession.isRecording && model.rawRecording.step != .recording {
                services.accelerometer.stop()
            }
        }
    }

    private func leave() {
        if !path.isEmpty { path.removeLast() }
    }

    @ViewBuilder
    private func setup(_ state: PhoneSessionState) -> some View {
        Text("Select the number of balls, mount the phone to your wrist/forearm, and press start to initiate a juggling session.")
            .font(.body)
            .foregroundStyle(.secondary)

        Text("Ball Count")
            .font(.headline)
            .frame(maxWidth: .infinity, alignment: .leading)

        BallCountPicker(selection: Binding(
            get: { state.selectedBallCount },
            set: { model.selectPhoneBallCount($0) }
        ))

        if let error = state.sensorError {
            Text(error)
                .foregroundStyle(.red)
                .frame(maxWidth: .infinity, alignment: .leading)
        }

        Button {
            _ = services.startPhoneSession(ballCount: state.selectedBallCount)
        } label: {
            Label("Start", systemImage: "play.fill")
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
    }

    @ViewBuilder
    private func recording(_ state: PhoneSessionState) -> some View {
        Text(state.currentCount > 0 ? "JUGGLING..." : "READY")
            .font(.subheadline.bold())
            .padding(.horizontal, 12)
            .padding(.vertical, 4)
            .foregroundStyle(state.currentCount > 0 ? Color.accentColor : .secondary)
            .overlay(Capsule().stroke(state.currentCount > 0 ? Color.accentColor : .secondary))

        Text(state.statusMessage)
            .font(.headline)

        HStack(spacing: 16) {
            VStack {
                Text("Catches in one hand").font(.caption)
                Text("\(state.currentCount)")
                    .font(.system(size: 64, weight: .bold, design: .rounded))
                    .contentTransition(.numericText())
            }
            .frame(maxWidth: .infinity)
            VStack {
                Text("PREVIOUS RUN").font(.caption)
                Text(state.previousCount == 0 ? "-" : "\(state.previousCount)")
                    .font(.system(size: 44, weight: .medium, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity)
        }
        .padding(16)
        .background(
            state.currentCount > 0 ? Color.accentColor.opacity(0.15) : Color(uiColor: .secondarySystemBackground),
            in: RoundedRectangle(cornerRadius: 12)
        )

        HStack {
            StatItem(label: "Runs", value: "\(state.sessionRunCount)")
            StatItem(label: "Avg", value: state.sessionRunCount == 0 ? "-" : String(format: "%.1f", state.sessionAverage))
            StatItem(label: "Max", value: state.sessionMax == 0 ? "-" : "\(state.sessionMax)")
            StatItem(label: "Time", value: Self.formatElapsed(state.elapsedSeconds))
        }

        if !state.completedRuns.isEmpty {
            Text("Runs: " + state.completedRuns.map(String.init).joined(separator: "  "))
                .frame(maxWidth: .infinity, alignment: .leading)
        }

        HStack(spacing: 12) {
            Button {
                services.cancelPhoneSession()
                leave()
            } label: {
                Label("Cancel", systemImage: "xmark").frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            Button {
                if services.stopPhoneSession() { leave() }
            } label: {
                Label("Save", systemImage: "square.and.arrow.down").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
        }
        .controlSize(.large)
    }

    static func formatElapsed(_ total: Int64) -> String {
        let hours = total / 3600
        let minutes = (total / 60) % 60
        let seconds = total % 60
        return hours > 0
            ? String(format: "%lld:%02lld:%02lld", hours, minutes, seconds)
            : String(format: "%lld:%02lld", minutes, seconds)
    }
}

/// 3 to 9 balls as a row of chips.
struct BallCountPicker: View {
    @Binding var selection: Int

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(3...9, id: \.self) { count in
                    Button("\(count)") { selection = count }
                        .buttonStyle(BallChipStyle(isSelected: selection == count))
                        .accessibilityAddTraits(selection == count ? .isSelected : [])
                }
            }
        }
    }
}

/// A ball-count chip: the selected one is filled with the accent color and
/// bold contrasting text, the others are outlined and muted, so the choice
/// reads at a glance in light and dark mode.
struct BallChipStyle: ButtonStyle {
    let isSelected: Bool

    /// Text on the accent fill: white on the light-mode purple, the Android
    /// theme's dark onPrimary (#381E72) on the pale dark-mode purple.
    private static let onAccent = Color(UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0x38 / 255, green: 0x1E / 255, blue: 0x72 / 255, alpha: 1)
            : .white
    })

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(isSelected ? .bold : .regular))
            .foregroundStyle(isSelected ? AnyShapeStyle(Self.onAccent) : AnyShapeStyle(.secondary))
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .frame(minWidth: 44, minHeight: 36)
            .background {
                Capsule()
                    .fill(isSelected ? AnyShapeStyle(Color.accentColor) : AnyShapeStyle(.clear))
            }
            .overlay {
                Capsule()
                    .strokeBorder(isSelected ? Color.accentColor : Color.secondary.opacity(0.5),
                                  lineWidth: isSelected ? 2 : 1)
            }
            .contentShape(Capsule())
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

struct StatItem: View {
    let label: String
    let value: String

    var body: some View {
        VStack(spacing: 2) {
            Text(label).font(.caption)
            Text(value).font(.headline)
        }
        .frame(maxWidth: .infinity)
    }
}
