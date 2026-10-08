import Foundation
import XCTest
@testable import JugglingCore

/// The Android `SessionCsvTest` cases, plus a check that the exact text the
/// Android app writes reads back here, so backups move between the two phones.
final class SessionCSVTests: XCTestCase {
    private let berlin = TimeZone(identifier: "Europe/Berlin")!

    private let sessions = [
        SessionSummary.summarize(
            timestamp: 1_716_931_234_567, ballCount: 5, runs: [12, 30], durationSeconds: 95,
            runDurationsMillis: [6000, 14000], shapeConsistency: 83
        ),
        SessionSummary.summarize(timestamp: 1_716_900_000_000, ballCount: 3, runs: [40], durationSeconds: 60, runDurationsMillis: [20000]),
    ]

    func testWhatWriteProducesParsesBackToTheSameSessions() throws {
        let parsed = try SessionCSV.parse(SessionCSV.write(sessions, timeZone: berlin), timeZone: berlin)
        XCTAssertEqual(parsed.sessions, sessions)
        XCTAssertEqual(parsed.unreadableRows, 0)
    }

    /// What `SessionCsv.write` on Android produces for the same two sessions.
    func testWriteMatchesTheAndroidFormatByteForByte() {
        let expected = SessionCSV.header + "\n" +
            "2024-05-28 23:20:34,5,2,95,21.00,30,42,\"6000;14000\",\"12;30\",83,1716931234567\n" +
            "2024-05-28 14:40:00,3,1,60,40.00,40,40,\"20000\",\"40\",,1716900000000\n"
        XCTAssertEqual(SessionCSV.write(sessions, timeZone: berlin), expected)
    }

    func testAverageHalvesRoundUpAsOnAndroid() {
        // 97 / 8 = 12.125: Java's %.2f gives 12.13, C's would give 12.12.
        let s = SessionSummary.summarize(timestamp: 0, ballCount: 3, runs: [12, 12, 12, 12, 12, 12, 12, 13])
        XCTAssertEqual(SessionCSV.formatAverage(s), "12.13")
        XCTAssertEqual(SessionCSV.formatAverage(SessionSummary.summarize(timestamp: 0, ballCount: 3, runs: [1, 1, 2])), "1.33")
    }

    func testAnExportFromBeforeTheTimestampColumnRestoresFromItsDate() throws {
        let old = """
            Date,Ball Count,Run Count,Session Duration Seconds,Watch Hand Average,Watch Hand Best,Watch Hand Total,Run Durations Millis,Watch Hand Run History,Regularity Percent
            2024-05-28 23:20:00,3,2,123,15.00,20,30,"9000;10000","10;20",
            """
        let parsed = try SessionCSV.parse(old, timeZone: berlin)
        XCTAssertEqual(parsed.sessions.count, 1)
        let session = parsed.sessions[0]
        XCTAssertEqual(session.timestamp, 1_716_931_200_000)
        XCTAssertEqual(session.ballCount, 3)
        XCTAssertEqual(session.runHistory, [10, 20])
        XCTAssertEqual(session.runDurationsMillis, [9000, 10000])
        XCTAssertEqual(session.durationSeconds, 123)
        XCTAssertNil(session.shapeConsistency)
    }

    func testAFileSavedByASpreadsheetWithSemicolonsStillReads() throws {
        let csv = SessionCSV.write(sessions, timeZone: berlin)
            .components(separatedBy: "\n")
            .map { line -> String in
                // Quoted cells keep their ';'; every other comma becomes one.
                var quoted = false
                return String(line.map { c -> Character in
                    if c == "\"" { quoted.toggle(); return c }
                    return c == "," && !quoted ? ";" : c
                })
            }
            .joined(separator: "\r\n")
        XCTAssertEqual(try SessionCSV.parse("\u{FEFF}" + csv, timeZone: berlin).sessions, sessions)
    }

    func testRowsItCannotReadAreSkippedAndCounted() throws {
        let csv = SessionCSV.write(sessions, timeZone: berlin) + "garbage,row\n" +
            "2024-05-28 23:20:00,3,0,0,0,0,0,\"\",\"\",,\n"
        let parsed = try SessionCSV.parse(csv, timeZone: berlin)
        XCTAssertEqual(parsed.sessions, sessions)
        XCTAssertEqual(parsed.unreadableRows, 2)
    }

    func testAFileThatIsNotASessionHistoryIsRefused() {
        XCTAssertThrowsError(try SessionCSV.parse("name,email\nJonas,j@example.com\n", timeZone: berlin)) {
            XCTAssertEqual($0 as? SessionCSV.ParseError, .notAHistory)
        }
    }

    func testNewSessionsSkipsThoseAlreadyStoredMatchingToTheSecond() {
        let stored = [SessionSummary.summarize(timestamp: 1_716_931_234_000, ballCount: 5, runs: [12])]
        // 1716931234567 is the stored session's second, as an old export would round it.
        XCTAssertEqual(SessionCSV.newSessions(sessions + sessions, existing: stored), [sessions[1]])
    }
}
