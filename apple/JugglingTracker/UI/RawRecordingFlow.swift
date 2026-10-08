import SwiftUI

/// The raw-capture flow from Settings: pick a ball count, record, then enter
/// the real catch count. Shown over whatever screen is open.
private struct RawRecordingFlow: ViewModifier {
    @Environment(TrackerModel.self) private var model
    @Environment(AppServices.self) private var services

    func body(content: Content) -> some View {
        content.sheet(
            isPresented: Binding(
                get: { model.rawRecording.step != .idle },
                set: { shown in if !shown { services.cancelRawRecording() } }
            )
        ) {
            RawRecordingSheet()
                .interactiveDismissDisabled(model.rawRecording.step == .recording)
                .presentationDetents([.medium])
        }
    }
}

extension View {
    func rawRecordingFlow() -> some View {
        modifier(RawRecordingFlow())
    }
}

private struct RawRecordingSheet: View {
    @Environment(TrackerModel.self) private var model
    @Environment(AppServices.self) private var services
    @State private var balls = 3
    @State private var catchesText = ""

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                switch model.rawRecording.step {
                case .idle:
                    EmptyView()
                case .selectBalls:
                    Text("Select the number of balls, mount the phone to your wrist/forearm, and press start to initiate a juggling session.")
                    BallCountPicker(selection: $balls)
                    Button("Start") { _ = services.startRawRecording(ballCount: balls) }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                case .recording:
                    Text("\(model.rawRecording.sampleCount) samples recorded")
                        .font(.body.monospacedDigit())
                    Text("Detected: \(model.phoneSession.currentCount)")
                        .font(.title2.bold())
                    Button(role: .destructive) {
                        services.stopRawRecording()
                    } label: {
                        Text("STOP").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.red)
                    .controlSize(.large)
                case .enterCatches:
                    Text("How many catches did you make during this run?")
                    TextField("Actual Catches", text: $catchesText)
                        .keyboardType(.numberPad)
                        .textFieldStyle(.roundedBorder)
                        .onChange(of: catchesText) { _, text in
                            let digits = text.filter(\.isNumber)
                            if digits != text { catchesText = digits }
                        }
                    Button("Save") {
                        model.saveRawRecording(actualCatches: Int(catchesText) ?? 0)
                        catchesText = ""
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(catchesText.isEmpty)
                }
                Spacer(minLength: 0)
            }
            .padding()
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if model.rawRecording.step != .recording {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { services.cancelRawRecording() }
                    }
                }
            }
        }
        .onAppear { balls = model.rawRecording.selectedBallCount }
    }

    private var title: String {
        switch model.rawRecording.step {
        case .idle, .selectBalls: "Ball Count"
        case .recording: "Record Acceleration Data"
        case .enterCatches: "Recording Stopped"
        }
    }
}
