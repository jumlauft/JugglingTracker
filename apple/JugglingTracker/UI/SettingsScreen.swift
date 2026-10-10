import JugglingCore
import SwiftUI
import UniformTypeIdentifiers

/// Settings, plus the backup and the raw recordings.
struct SettingsScreen: View {
    @Environment(TrackerModel.self) private var model

    var body: some View {
        @Bindable var settings = model.settings
        Form {
            Section {
                Picker("Watch", selection: $settings.watchType) {
                    ForEach(WatchType.allCases) { Text($0.label).tag($0) }
                }
            } footer: {
                Text("The watch you record with. The card on the home screen shows whether it is connected.")
            }

            Section("Voice Announcements") {
                Toggle("Enable Voice", isOn: $settings.isVoiceEnabled)
                if settings.isVoiceEnabled {
                    Picker("Announcement Interval", selection: $settings.voiceInterval) {
                        ForEach([1, 2, 5, 10, 20, 50, 100], id: \.self) { Text("\($0) catches").tag($0) }
                    }
                }
            }

            Section {
                Toggle("Share app activity and crash reports", isOn: Binding(
                    get: { settings.isAnalyticsEnabled },
                    set: { model.setAnalyticsEnabled($0) }
                ))
            } header: {
                Text("Privacy")
            } footer: {
                Text("Helps us improve the app by sending anonymous usage data and error reports.")
            }

            BackupSection()

            RecordingsSection()
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Backup

/// The weekly backup to a CSV file in a folder the user picked (iCloud Drive
/// or another Files location), and restoring from a backup or export.
private struct BackupSection: View {
    @Environment(TrackerModel.self) private var model
    @State private var pickingFolder = false
    @State private var pickingRestoreFile = false

    var body: some View {
        let settings = model.settings
        Section {
            Toggle("Weekly backup to iCloud Drive", isOn: Binding(
                get: { settings.isBackupEnabled },
                set: { enabled in
                    if !enabled {
                        settings.isBackupEnabled = false
                        SessionBackup.cancelSchedule()
                    } else if SessionBackup.folder(settings: settings) != nil {
                        settings.isBackupEnabled = true
                        SessionBackup.schedule()
                    } else {
                        pickingFolder = true
                    }
                }
            ))

            if settings.isBackupEnabled {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Backup folder: \(settings.backupFolderName ?? "the folder you picked")")
                    if settings.lastBackupMillis > 0 {
                        Text("Last backup: \(Date(timeIntervalSince1970: TimeInterval(settings.lastBackupMillis) / 1000).formatted(.dateTime.month(.abbreviated).day(.twoDigits).hour().minute()))")
                    } else {
                        Text("No backup yet")
                    }
                    if settings.lastBackupFailed {
                        Text("The last backup could not be saved. Back up now to try again, or choose the backup folder again.")
                            .foregroundStyle(.red)
                    }
                }
                .font(.subheadline)
                Button("Back Up Now") { backUpNow() }
                Button("Choose Backup Folder") { pickingFolder = true }
            }
        } header: {
            Text("Backup")
        } footer: {
            Text("Once a week your session history is saved as a CSV file named \(SessionBackup.fileName). When you turn it on, pick a folder in iCloud Drive for the file.")
        }
        .fileImporter(isPresented: $pickingFolder, allowedContentTypes: [.folder]) { result in
            guard case let .success(folder) = result else { return }
            if SessionBackup.connect(folder: folder, settings: settings) {
                // Picking a folder also turns the weekly backup on and writes it at once.
                settings.isBackupEnabled = true
                SessionBackup.schedule()
                backUpNow()
            } else {
                model.toast = "This folder cannot be used for automatic backups. Please choose one in iCloud Drive."
            }
        }

        Section {
            Button("Restore from Backup") { pickingRestoreFile = true }
        } footer: {
            Text("Adds the sessions from a backup or exported CSV file. Sessions already on this phone are kept and never added twice.")
        }
        .fileImporter(
            isPresented: $pickingRestoreFile,
            allowedContentTypes: [.commaSeparatedText, .plainText, .text, .data]
        ) { result in
            guard case let .success(url) = result else { return }
            restore(from: url)
        }
    }

    private func backUpNow() {
        switch SessionBackup.backUp(sessions: model.sessions, settings: model.settings) {
        case .saved: model.toast = "Backup saved"
        case .nothingToSave: model.toast = "No sessions to back up yet"
        case .noFolder, .failed: model.toast = "Backup failed"
        }
    }

    private func restore(from url: URL) {
        let parsed: SessionCSV.Parsed
        do {
            parsed = try SessionCSV.parse(SessionBackup.read(url))
        } catch {
            model.toast = "This file is not a Juggling Tracker backup"
            return
        }
        let added = model.restore(parsed.sessions)
        model.toast = added == 0
            ? "Nothing new: every session in this file is already here"
            : "Restored \(added) session\(added == 1 ? "" : "s")"
    }
}

// MARK: - Raw recordings

private struct RecordingsSection: View {
    // The developer collects recordings to tune the detector offline.
    static let developerEmail = "jugglingtracker@gmail.com"

    enum Export { case save, email }

    @Environment(TrackerModel.self) private var model
    @State private var editingJuggler = false
    @State private var askingJugglerFor: Export?
    @State private var confirmClear = false
    @State private var zipFile: ExportFile?
    @State private var mailDraft: MailComposer.Draft?
    @State private var shareURL: URL?

    var body: some View {
        let settings = model.settings
        Section {
            Button {
                editingJuggler = true
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Juggler").font(.caption).foregroundStyle(.secondary)
                    Text(Self.summary(settings.currentJuggler)).foregroundStyle(.primary)
                }
            }
            Button("Start Raw Recording") { model.startRawRecordingFlow() }

            if model.recordingCount > 0 {
                RecordingsTable(recordings: model.recordings)
                Button("Export All Recordings") { exportAfterAsking(.save) }
                Button("Email Recordings to Developer") { exportAfterAsking(.email) }
                Button("Delete All Recordings", role: .destructive) { confirmClear = true }
            }
        } header: {
            Text("Raw Data Recording")
        } footer: {
            Text("The juggler is saved with each new recording, so exports keep who juggled each run. Change it before someone else records.")
        }
        .sheet(isPresented: $editingJuggler) {
            JugglerForm(message: nil, confirmLabel: "Save") { editingJuggler = false }
        }
        .sheet(item: $askingJugglerFor) { export in
            let untagged = model.recordingsWithoutJuggler
            JugglerForm(
                message: untagged == 1
                    ? "1 recording was saved without a juggler. These answers will be used for it."
                    : "\(untagged) recordings were saved without a juggler. These answers will be used for them.",
                confirmLabel: "Continue"
            ) {
                askingJugglerFor = nil
                run(export)
            }
        }
        .alert("Delete All Recordings?", isPresented: $confirmClear) {
            Button("Delete", role: .destructive) { model.clearRecordings() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This deletes \(model.recordingCount) recording\(model.recordingCount == 1 ? "" : "s") from this phone. They cannot be recovered unless you exported them.")
        }
        .fileExporter(
            isPresented: Binding(get: { zipFile != nil }, set: { if !$0 { zipFile = nil } }),
            document: zipFile,
            contentType: .zip,
            defaultFilename: "juggling_recordings_\(ExportNames.stamp()).zip"
        ) { result in
            if case .failure = result { model.toast = "Export failed" }
        }
        .sheet(item: $mailDraft) { draft in
            MailComposer(draft: draft).ignoresSafeArea()
        }
        .sheet(isPresented: Binding(get: { shareURL != nil }, set: { if !$0 { shareURL = nil } })) {
            if let shareURL { ShareSheet(items: [shareURL]) }
        }
    }

    static func summary(_ juggler: Juggler?) -> String {
        guard let juggler else { return "Not set. Tap to say who is juggling." }
        return "\(juggler.name): watch on the \(juggler.hand) wrist, first throw with the \(juggler.firstThrow) hand"
    }

    /// Asks who juggled only when some runs were saved without a juggler.
    private func exportAfterAsking(_ export: Export) {
        if model.recordingsWithoutJuggler > 0 { askingJugglerFor = export } else { run(export) }
    }

    private func run(_ export: Export) {
        Task {
            let zip = await model.recordingsZip()
            let name = "juggling_recordings_\(ExportNames.stamp()).zip"
            switch export {
            case .save:
                zipFile = ExportFile(data: zip)
            case .email:
                if MailComposer.canSend {
                    mailDraft = MailComposer.Draft(
                        to: [Self.developerEmail],
                        subject: "JugglingTracker recordings",
                        body: "Attached are my juggling recordings for detector tuning.",
                        attachment: zip, mimeType: "application/zip", fileName: name
                    )
                } else {
                    // No mail account: the share sheet can still send the zip.
                    let url = AppFiles.shareDirectory().appendingPathComponent(name)
                    do {
                        try zip.write(to: url, options: .atomic)
                        shareURL = url
                    } catch {
                        model.toast = "Export failed"
                    }
                }
            }
        }
    }
}

extension RecordingsSection.Export: Identifiable {
    fileprivate var id: Self { self }
}

/// The stored runs: when, from which device, balls, catches (with what the
/// detector found) and length.
private struct RecordingsTable: View {
    let recordings: [RecordingStore.Summary]

    private static let timeFormat: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "dd.MM HH:mm"
        return formatter
    }()

    var body: some View {
        Grid(alignment: .leading, horizontalSpacing: 8, verticalSpacing: 4) {
            GridRow {
                Text("When")
                Text("Source")
                Text("Balls").gridColumnAlignment(.trailing)
                Text("Catches").gridColumnAlignment(.trailing)
                Text("Length").gridColumnAlignment(.trailing)
            }
            .font(.caption.bold())
            Divider()
            ForEach(recordings) { rec in
                GridRow {
                    Text(rec.timestamp > 0
                        ? Self.timeFormat.string(from: Date(timeIntervalSince1970: TimeInterval(rec.timestamp)))
                        : "-")
                    Text(rec.fromWatch ? "Watch 25Hz" : "Phone \(rec.sampleRate)Hz")
                    Text("\(rec.balls)")
                    Text("\(rec.catches) (\(rec.detected))")
                    Text(String(format: "%.0fs", rec.durationSeconds))
                }
                .font(.caption)
            }
        }
    }
}

/// Who juggles, which wrist wears the watch and which hand makes the first
/// throw, prefilled with the previous answers.
private struct JugglerForm: View {
    let message: String?
    let confirmLabel: String
    let onDone: () -> Void

    @Environment(TrackerModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var hand: String?
    @State private var firstThrow: String?

    var body: some View {
        NavigationStack {
            Form {
                if let message { Text(message) }
                TextField("Name", text: $name)
                    .textContentType(.name)
                handPicker("Which wrist is the watch on?", selection: $hand)
                handPicker("Which hand makes the first throw?", selection: $firstThrow)
            }
            .navigationTitle("Who is juggling?")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(confirmLabel) {
                        guard let hand, let firstThrow else { return }
                        model.settings.updateJuggler(
                            name: name.trimmingCharacters(in: .whitespaces), hand: hand, firstThrow: firstThrow
                        )
                        onDone()
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || hand == nil || firstThrow == nil)
                }
            }
        }
        .onAppear {
            name = model.settings.jugglerName
            hand = model.settings.watchHand
            firstThrow = model.settings.firstThrowHand
        }
    }

    private func handPicker(_ question: String, selection: Binding<String?>) -> some View {
        Picker(question, selection: selection) {
            Text("Left").tag(Optional(Juggler.left))
            Text("Right").tag(Optional(Juggler.right))
        }
        .pickerStyle(.inline)
    }
}
