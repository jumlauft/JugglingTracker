import Foundation

/// Who juggled a run: their name, the wrist wearing the watch (or phone) and
/// the hand that made the first throw, each "left" or "right".
public struct Juggler: Equatable, Sendable {
    public static let left = "left"
    public static let right = "right"

    public var name: String
    public var hand: String
    public var firstThrow: String

    public init(name: String, hand: String, firstThrow: String) {
        self.name = name
        self.hand = hand
        self.firstThrow = firstThrow
    }

    public var headerFields: [String] {
        ["juggler=\(RecordingHeader.headerSafe(name))", "hand=\(hand)", "firstThrow=\(firstThrow)"]
    }
}

/// The first line of a recording CSV, which both phones write and the corpus
/// in connectiq/data uses:
///
///     # run=20260922_144802,timestamp=1790081282,balls=3,catches=89,
///     sampleRate=25,units=milli_g,source=watch,countMode=watch_hand,detectedAtCapture=88
///
/// (one line), followed by juggler=<name>,hand=left|right,firstThrow=left|right
/// when the juggler is known. Same rules as `RecordingHeader.kt` in the Kotlin
/// `shared/` module.
public enum RecordingHeader {
    private static let jugglerKeys: Set<String> = ["juggler", "hand", "firstThrow"]

    /// The run id and file name stem: the capture time (`timestamp`, epoch
    /// seconds) as yyyyMMdd_HHmmss in `timeZone`.
    public static func runId(timestamp: Int64, timeZone: TimeZone = .current) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = timeZone
        formatter.dateFormat = "yyyyMMdd_HHmmss"
        return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(timestamp)))
    }

    /// The header line of one run. `detected` is stored as detectedAtCapture:
    /// what the detector counted at the time, which goes stale whenever the
    /// detector changes.
    public static func line(
        runId: String, timestamp: Int64, balls: Int, catches: Int, sampleRate: Int,
        source: String, detected: Int, juggler: Juggler?
    ) -> String {
        var header = "# run=\(runId),timestamp=\(timestamp),balls=\(balls),catches=\(catches)" +
            ",sampleRate=\(sampleRate),units=milli_g,source=\(source)" +
            ",countMode=watch_hand,detectedAtCapture=\(detected)"
        if let juggler { header += "," + juggler.headerFields.joined(separator: ",") }
        return header
    }

    /// The juggler's name as a header value: the characters that separate
    /// header fields and lines become spaces.
    public static func headerSafe(_ name: String) -> String {
        let replaced = name.map { ",=#\r\n".contains($0) ? " " : String($0) }.joined()
        return replaced.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    }

    /// `header` with juggler, hand and firstThrow set, replacing any it carries.
    public static func withJuggler(_ header: String, _ juggler: Juggler) -> String {
        var body = header
        if body.hasPrefix("#") { body.removeFirst() }
        let kept = body.trimmingCharacters(in: .whitespaces)
            .split(separator: ",", omittingEmptySubsequences: false)
            .map(String.init)
            .filter { part in
                let key = part.split(separator: "=", maxSplits: 1).first.map {
                    String($0).trimmingCharacters(in: .whitespaces)
                } ?? ""
                return !part.trimmingCharacters(in: .whitespaces).isEmpty && !jugglerKeys.contains(key)
            }
        return "# " + (kept + juggler.headerFields).joined(separator: ",")
    }

    /// The header's key=value fields.
    public static func fields(_ header: String) -> [String: String] {
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

    /// The juggler a header names, or nil unless it carries all three fields.
    public static func juggler(of fields: [String: String]) -> Juggler? {
        guard let name = fields["juggler"], !name.isEmpty,
              let hand = fields["hand"], hand == Juggler.left || hand == Juggler.right,
              let first = fields["firstThrow"], first == Juggler.left || first == Juggler.right
        else { return nil }
        return Juggler(name: name, hand: hand, firstThrow: first)
    }
}
