import Foundation

/// Who juggled a run: their name, the wrist wearing the watch (or phone) and
/// the hand that made the first throw, each "left" or "right".
struct Juggler: Equatable {
    static let left = "left"
    static let right = "right"

    var name: String
    var hand: String
    var firstThrow: String

    var headerFields: [String] {
        ["juggler=\(RecordingStore.headerSafe(name))", "hand=\(hand)", "firstThrow=\(firstThrow)"]
    }
}

/// Raw accelerometer recordings, one CSV file per run under `directory`.
/// Counterpart of the Android app's `data/RecordingRepository.kt`, writing
/// the same format, so a run recorded on either phone drops straight into
/// connectiq/data:
///
///     # run=20260922_144802,timestamp=1790081282,balls=3,catches=89,
///     sampleRate=25,units=milli_g,source=watch,countMode=watch_hand,detectedAtCapture=88
///     x,y,z
///     -123,456,987
///
/// (the header is one line). Catches are watch-hand catches. Samples are
/// milli-g integers in Android's axis convention (see `PhoneAccelerometer`).
/// When the juggler is known at save time the header also ends with
/// juggler=<name>,hand=left|right,firstThrow=left|right.
final class RecordingStore {
    static let sourceWatch = "watch"
    static let sourcePhone = "phone"
    private static let exportKeys: Set<String> = ["juggler", "hand", "firstThrow"]

    struct Summary: Equatable, Identifiable {
        var fileName: String
        var balls: Int
        var catches: Int
        var detected: Int
        var sampleRate: Int
        var timestamp: Int64
        var samples: Int
        var source: String
        var juggler: Juggler?

        var id: String { fileName }
        var durationSeconds: Double { sampleRate > 0 ? Double(samples) / Double(sampleRate) : 0 }
        var fromWatch: Bool { source == RecordingStore.sourceWatch }
    }

    let directory: URL
    private var summaryCache: [String: Summary] = [:]
    private let lock = NSLock()

    init(directory: URL) {
        self.directory = directory
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    static func standard() -> RecordingStore {
        RecordingStore(directory: AppFiles.supportDirectory.appendingPathComponent("recordings", isDirectory: true))
    }

    // MARK: - Header helpers

    /// The juggler's name as a header value: the characters that separate
    /// header fields and lines become spaces.
    static func headerSafe(_ name: String) -> String {
        let replaced = name.map { ",=#\r\n".contains($0) ? " " : String($0) }.joined()
        return replaced.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    }

    /// `header` with juggler, hand and firstThrow set, replacing any it carries.
    static func withJuggler(_ header: String, _ juggler: Juggler) -> String {
        var body = header
        if body.hasPrefix("#") { body.removeFirst() }
        let kept = body.trimmingCharacters(in: .whitespaces)
            .split(separator: ",", omittingEmptySubsequences: false)
            .map(String.init)
            .filter { part in
                let key = part.split(separator: "=", maxSplits: 1).first.map {
                    String($0).trimmingCharacters(in: .whitespaces)
                } ?? ""
                return !part.trimmingCharacters(in: .whitespaces).isEmpty && !exportKeys.contains(key)
            }
        return "# " + (kept + juggler.headerFields).joined(separator: ",")
    }

    static func headerFields(_ header: String) -> [String: String] {
        var body = header
        if body.hasPrefix("#") { body.removeFirst() }
        var fields: [String: String] = [:]
        for part in body.trimmingCharacters(in: .whitespaces).split(separator: ",") {
            let kv = part.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            guard kv.count == 2 else { continue }
            fields[kv[0].trimmingCharacters(in: .whitespaces)] = kv[1].trimmingCharacters(in: .whitespaces)
        }
        return fields
    }

    static func juggler(of fields: [String: String]) -> Juggler? {
        guard let name = fields["juggler"], !name.isEmpty,
              let hand = fields["hand"], hand == Juggler.left || hand == Juggler.right,
              let first = fields["firstThrow"], first == Juggler.left || first == Juggler.right
        else { return nil }
        return Juggler(name: name, hand: hand, firstThrow: first)
    }

    /// The run id and file name stem: the capture time as yyyyMMdd_HHmmss in
    /// the phone's time zone.
    static func runId(timestamp: Int64, timeZone: TimeZone = .current) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = timeZone
        formatter.dateFormat = "yyyyMMdd_HHmmss"
        return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(timestamp)))
    }

    // MARK: - Saving and reading

    /// Saves one run to its own CSV file. `timestamp` is epoch seconds.
    /// Returns the file, or nil when there was nothing to write or the write failed.
    @discardableResult
    func saveRecording(
        balls: Int, catches: Int, detected: Int, sampleRate: Int, timestamp: Int64,
        x: [Int], y: [Int], z: [Int], source: String, juggler: Juggler? = nil
    ) -> URL? {
        guard !x.isEmpty else { return nil }
        let n = min(x.count, y.count, z.count)
        let runId = Self.runId(timestamp: timestamp)
        let url = directory.appendingPathComponent("\(runId).csv")

        var header = "# run=\(runId),timestamp=\(timestamp),balls=\(balls),catches=\(catches)" +
            ",sampleRate=\(sampleRate),units=milli_g,source=\(source)" +
            ",countMode=watch_hand,detectedAtCapture=\(detected)"
        if let juggler { header += "," + juggler.headerFields.joined(separator: ",") }
        var text = header + "\nx,y,z\n"
        text.reserveCapacity(text.count + n * 16)
        for i in 0..<n {
            text += "\(x[i]),\(y[i]),\(z[i])\n"
        }
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try Data(text.utf8).write(to: url, options: .atomic)
        } catch {
            lock.withLock { _ = summaryCache.removeValue(forKey: url.lastPathComponent) }
            return nil
        }
        let summary = Summary(
            fileName: url.lastPathComponent, balls: balls, catches: catches, detected: detected,
            sampleRate: sampleRate, timestamp: timestamp, samples: n, source: source,
            // As a re-read of the header would give it back.
            juggler: juggler.flatMap { Self.juggler(of: Self.headerFields($0.headerFields.joined(separator: ","))) }
        )
        lock.withLock { summaryCache[url.lastPathComponent] = summary }
        return url
    }

    private func csvFiles() -> [URL] {
        let urls = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        return urls.filter { $0.pathExtension == "csv" }.sorted { $0.lastPathComponent < $1.lastPathComponent }
    }

    var recordingCount: Int { csvFiles().count }

    /// Summaries of every stored run, newest first. Each file is read once.
    func listRecordings() -> [Summary] {
        let files = csvFiles()
        let names = Set(files.map(\.lastPathComponent))
        let unread: [URL] = lock.withLock {
            summaryCache = summaryCache.filter { names.contains($0.key) }
            return files.filter { summaryCache[$0.lastPathComponent] == nil }
        }
        let read = unread.compactMap(readSummary)
        return lock.withLock {
            for summary in read where summaryCache[summary.fileName] == nil {
                summaryCache[summary.fileName] = summary
            }
            return summaryCache.values.sorted { $0.timestamp > $1.timestamp }
        }
    }

    private func readSummary(_ url: URL) -> Summary? {
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return nil }
        var header: String?
        var samples = 0
        for line in text.split(separator: "\n", omittingEmptySubsequences: true) {
            if line.hasPrefix("#") {
                if header == nil { header = String(line) }
            } else if !line.hasPrefix("x,") && !line.trimmingCharacters(in: .whitespaces).isEmpty {
                samples += 1
            }
        }
        guard let header else { return nil }
        let fields = Self.headerFields(header)
        guard let balls = fields["balls"].flatMap({ Int($0) }) else { return nil }
        let sampleRate = fields["sampleRate"].flatMap { Int($0) } ?? 0
        return Summary(
            fileName: url.lastPathComponent,
            balls: balls,
            catches: fields["catches"].flatMap { Int($0) } ?? 0,
            detected: (fields["detectedAtCapture"] ?? fields["detected"]).flatMap { Int($0) } ?? 0,
            sampleRate: sampleRate,
            timestamp: fields["timestamp"].flatMap { Int64($0) } ?? 0,
            samples: samples,
            // Runs from before the source was stored: the watch runs at 25 Hz.
            source: fields["source"] ?? (sampleRate <= 25 ? Self.sourceWatch : Self.sourcePhone),
            juggler: Self.juggler(of: fields)
        )
    }

    /// One run's CSV with only the x,y,z columns, its header naming its own
    /// juggler or else `fallback`.
    func normalizedCSV(_ url: URL, fallback: Juggler?) -> String {
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return "" }
        var out = ""
        for line in text.split(separator: "\n", omittingEmptySubsequences: true) {
            let raw = line.hasSuffix("\r") ? String(line.dropLast()) : String(line)
            if raw.trimmingCharacters(in: .whitespaces).isEmpty { continue }
            if raw.hasPrefix("#") {
                if let own = Self.juggler(of: Self.headerFields(raw)) {
                    out += Self.withJuggler(raw, own)
                } else if let fallback {
                    out += Self.withJuggler(raw, fallback)
                } else {
                    out += raw
                }
            } else {
                out += raw.split(separator: ",", omittingEmptySubsequences: false).prefix(3).joined(separator: ",")
            }
            out += "\n"
        }
        return out
    }

    /// Every stored run as a zip of one CSV each, named and formatted to drop
    /// into connectiq/data. Runs saved without a juggler are tagged with `fallback`.
    func exportZip(fallback: Juggler?) -> Data {
        var zip = ZipWriter()
        for url in csvFiles() {
            zip.add(name: url.lastPathComponent, data: Data(normalizedCSV(url, fallback: fallback).utf8))
        }
        return zip.finish()
    }

    func clearAll() {
        for url in csvFiles() { try? FileManager.default.removeItem(at: url) }
        lock.withLock { summaryCache.removeAll() }
    }
}
