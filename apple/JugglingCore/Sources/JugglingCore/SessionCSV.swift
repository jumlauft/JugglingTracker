import Foundation

/// The session history as CSV: what "Export History to CSV" and the weekly
/// backup write, and what "Restore from Backup" reads back. The format is the
/// Android app's (`data/SessionCsv.kt`) exactly, so a backup made on either
/// phone restores on the other.
///
/// Numbers and dates are written the same way whatever the phone's language: a
/// German phone would otherwise write 12,50 for the average, and that comma
/// splits the row into one column too many.
///
/// The last column, Timestamp Millis, is the session's exact timestamp (its
/// identity). Exports from before it existed carry only the Date, to the
/// second in the phone's time zone, so `parse` falls back to that and
/// `newSessions` compares sessions to the second.
public enum SessionCSV {
    static let colDate = "Date"
    static let colBalls = "Ball Count"
    static let colDuration = "Session Duration Seconds"
    static let colRunDurations = "Run Durations Millis"
    static let colRunHistory = "Watch Hand Run History"
    static let colRegularity = "Regularity Percent"
    static let colTimestamp = "Timestamp Millis"

    public static let header = "\(colDate),\(colBalls),Run Count,\(colDuration),Watch Hand Average," +
        "Watch Hand Best,Watch Hand Total,\(colRunDurations),\(colRunHistory),\(colRegularity),\(colTimestamp)"

    public enum ParseError: Error, Equatable {
        case empty
        case notAHistory
    }

    /// What `parse` read: the sessions, and how many data rows it could not read.
    public struct Parsed: Equatable {
        public let sessions: [SessionSummary]
        public let unreadableRows: Int
    }

    static func dateFormatter(_ timeZone: TimeZone) -> DateFormatter {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = timeZone
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        formatter.isLenient = false
        return formatter
    }

    /// `sessions` as CSV, one row each in the order given.
    public static func write(_ sessions: [SessionSummary], timeZone: TimeZone = .current) -> String {
        let formatter = dateFormatter(timeZone)
        var out = header + "\n"
        for session in sessions {
            let date = formatter.string(from: Date(timeIntervalSince1970: Double(session.timestamp) / 1000.0))
            let runHistory = session.runHistory.map(String.init).joined(separator: ";")
            let runDurations = session.runDurationsMillis.map(String.init).joined(separator: ";")
            // Empty when the session has no Regularity score.
            let regularity = session.shapeConsistency.map(String.init) ?? ""
            out += "\(date),\(session.ballCount),\(session.runCount),\(session.durationSeconds),"
            out += "\(formatAverage(session)),\(session.bestRun),\(session.totalThrows),"
            out += "\"\(runDurations)\",\"\(runHistory)\",\(regularity),\(session.timestamp)\n"
        }
        return out
    }

    /// The average to two decimals, rounding halves up as Java's `%.2f` does
    /// (C's `%.2f` would round 12.125 down to 12.12). Computed from the runs
    /// themselves, which are whole numbers, so no binary fraction gets in the way.
    static func formatAverage(_ session: SessionSummary) -> String {
        let runs = session.runHistory
        guard !runs.isEmpty else { return String(format: "%.2f", session.avgThrows) }
        let total = Int64(runs.reduce(0, +))
        let count = Int64(runs.count)
        let hundredths = (total * 200 + count) / (count * 2)
        let whole = hundredths / 100
        let fraction = hundredths % 100
        return "\(whole)." + (fraction < 10 ? "0\(fraction)" : "\(fraction)")
    }

    /// The sessions in a CSV written by `write` or an older export. Rows it
    /// cannot read (no date, no runs, cut short) are counted and skipped.
    /// Throws when the text is not a session history at all.
    public static func parse(_ text: String, timeZone: TimeZone = .current) throws -> Parsed {
        var body = text
        if body.hasPrefix("\u{FEFF}") { body.removeFirst() }
        let lines = body.components(separatedBy: .newlines)
            .filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        guard let headerLine = lines.first else { throw ParseError.empty }
        // A spreadsheet app saving with a German locale separates with ';'.
        let delimiter: Character = (!headerLine.contains(",") && headerLine.contains(";")) ? ";" : ","
        let headerCells = splitRow(headerLine, delimiter).map(trim)
        var index: [String: Int] = [:]
        for (i, name) in headerCells.enumerated() { index[name] = i }
        let balls = index[colBalls]
        let runHistory = index[colRunHistory]
        let date = index[colDate]
        let exact = index[colTimestamp]
        guard balls != nil, runHistory != nil, date != nil || exact != nil else {
            throw ParseError.notAHistory
        }
        let formatter = dateFormatter(timeZone)

        var unreadable = 0
        var sessions: [SessionSummary] = []
        for line in lines.dropFirst() {
            let cells = splitRow(line, delimiter).map(trim)
            func cell(_ column: Int?) -> String? {
                guard let column, column < cells.count, !cells[column].isEmpty else { return nil }
                return cells[column]
            }
            if let session = readRow(
                cell: cell, balls: balls, runHistory: runHistory, date: date, exact: exact,
                index: index, formatter: formatter
            ) {
                sessions.append(session)
            } else {
                unreadable += 1
            }
        }
        return Parsed(sessions: sessions, unreadableRows: unreadable)
    }

    private static func readRow(
        cell: (Int?) -> String?, balls: Int?, runHistory: Int?, date: Int?, exact: Int?,
        index: [String: Int], formatter: DateFormatter
    ) -> SessionSummary? {
        let timestamp: Int64? = cell(exact).flatMap { Int64($0) }
            ?? cell(date).flatMap { formatter.date(from: $0) }.map { Int64(($0.timeIntervalSince1970 * 1000).rounded()) }
        guard let timestamp, let ballCount = cell(balls).flatMap({ Int($0) }) else { return nil }
        // One unreadable run makes the row unreadable, as on Android.
        let runParts = cell(runHistory)?.split(separator: ";", omittingEmptySubsequences: false) ?? []
        var runs: [Int] = []
        for part in runParts {
            guard let run = Int(trim(String(part))) else { return nil }
            runs.append(run)
        }
        guard !runs.isEmpty else { return nil }
        let durations = (cell(index[colRunDurations])?.split(separator: ";") ?? [])
            .compactMap { Int64(trim(String($0))) }
        return SessionSummary.summarize(
            timestamp: timestamp,
            ballCount: ballCount,
            runs: runs,
            durationSeconds: cell(index[colDuration]).flatMap { Int64($0) } ?? 0,
            runDurationsMillis: durations,
            shapeConsistency: cell(index[colRegularity]).flatMap { Int($0) }
        )
    }

    /// The sessions of `backup` not already in `existing`, each once. Sessions
    /// are matched to the second, because older exports only carry the date to
    /// the second; two real sessions never start within one second.
    public static func newSessions(_ backup: [SessionSummary], existing: [SessionSummary]) -> [SessionSummary] {
        var seen = Set(existing.map { secondOf($0.timestamp) })
        return backup.filter { seen.insert(secondOf($0.timestamp)).inserted }
    }

    private static func secondOf(_ timestamp: Int64) -> Int64 {
        // Floor division, so times before 1970 land in the right second too.
        timestamp >= 0 ? timestamp / 1000 : -((-timestamp + 999) / 1000)
    }

    private static func trim(_ s: String) -> String {
        s.trimmingCharacters(in: .whitespaces)
    }

    /// One CSV row's cells; a quoted cell may hold the delimiter and "" for a quote.
    static func splitRow(_ line: String, _ delimiter: Character) -> [String] {
        var cells: [String] = []
        var current = ""
        var quoted = false
        let chars = Array(line)
        var i = 0
        while i < chars.count {
            let c = chars[i]
            if quoted && c == "\"" && i + 1 < chars.count && chars[i + 1] == "\"" {
                current.append("\"")
                i += 1
            } else if c == "\"" {
                quoted.toggle()
            } else if !quoted && c == delimiter {
                cells.append(current)
                current = ""
            } else {
                current.append(c)
            }
            i += 1
        }
        cells.append(current)
        return cells
    }
}
