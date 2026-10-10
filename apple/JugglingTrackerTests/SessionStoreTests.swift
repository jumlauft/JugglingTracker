import JugglingCore
import XCTest
@testable import JugglingTracker

final class SessionStoreTests: XCTestCase {
    private var file: URL!

    override func setUp() {
        super.setUp()
        file = makeTempDirectory(self).appendingPathComponent("sessions.jsonl")
    }

    private func reopened(_ store: SessionStore) -> SessionStore {
        store.flush()
        return SessionStore(fileURL: file)
    }

    func testSessionsSurviveARestartNewestFirst() {
        let store = SessionStore(fileURL: file)
        store.importSession(ballCount: 3, timestamp: 1_000, runs: [10, 20], durationSeconds: 30, runDurationsMillis: [4000, 8000])
        store.importSession(ballCount: 5, timestamp: 2_000, runs: [7], shapeConsistency: 81)

        let again = reopened(store)
        XCTAssertEqual(again.sessions.map(\.timestamp), [2_000, 1_000])
        XCTAssertEqual(again.sessions, store.sessions)
        XCTAssertEqual(again.sessions[0].shapeConsistency, 81)
    }

    func testASessionResentUnderItsTimestampReplacesTheFirstCopy() {
        let store = SessionStore(fileURL: file)
        store.importSession(ballCount: 3, timestamp: 1_000, runs: [10])
        store.importSession(ballCount: 3, timestamp: 1_000, runs: [10, 25])

        let again = reopened(store)
        XCTAssertEqual(again.sessions.count, 1)
        XCTAssertEqual(again.sessions[0].runHistory, [10, 25])
    }

    func testASessionWithoutRunsIsNotStored() {
        let store = SessionStore(fileURL: file)
        XCTAssertNil(store.importSession(ballCount: 3, timestamp: 1_000, runs: []))
        XCTAssertTrue(store.sessions.isEmpty)
    }

    func testDeleteRemovesTheSessionFromDisk() {
        let store = SessionStore(fileURL: file)
        let a = store.importSession(ballCount: 3, timestamp: 1_000, runs: [10])!
        store.importSession(ballCount: 3, timestamp: 2_000, runs: [12])
        store.delete(a)

        XCTAssertEqual(reopened(store).sessions.map(\.timestamp), [2_000])
    }

    func testRestoreAddsOnlyNewSessions() {
        let store = SessionStore(fileURL: file)
        store.importSession(ballCount: 3, timestamp: 1_000_000, runs: [10])
        let backup = [
            SessionSummary.summarize(timestamp: 1_000_400, ballCount: 3, runs: [10]), // same second
            SessionSummary.summarize(timestamp: 5_000_000, ballCount: 4, runs: [8]),
        ]
        XCTAssertEqual(store.restore(backup), 1)
        XCTAssertEqual(reopened(store).sessions.map(\.timestamp), [5_000_000, 1_000_000])
    }

    func testALineCutShortByACrashIsSkipped() throws {
        let store = SessionStore(fileURL: file)
        store.importSession(ballCount: 3, timestamp: 1_000, runs: [10])
        store.flush()
        let handle = try FileHandle(forWritingTo: file)
        try handle.seekToEnd()
        try handle.write(contentsOf: Data("{\"timestamp\":2000,\"ballC".utf8))
        try handle.close()

        XCTAssertEqual(SessionStore(fileURL: file).sessions.map(\.timestamp), [1_000])
    }

    /// The Android app's JSON line for a session reads here too.
    func testReadsTheAndroidJsonLine() throws {
        let line = #"{"timestamp":1716931234567,"ballCount":5,"runCount":2,"avgThrows":21.0,"stdDevThrows":9.0,"bestRun":30,"totalThrows":42,"runHistory":[12,30],"durationSeconds":95,"runDurationsMillis":[6000,14000],"shapeConsistency":83}"#
        try Data((line + "\n").utf8).write(to: file)
        let session = try XCTUnwrap(SessionStore(fileURL: file).sessions.first)
        XCTAssertEqual(session.runHistory, [12, 30])
        XCTAssertEqual(session.shapeConsistency, 83)
        XCTAssertEqual(session.runDurationsMillis, [6000, 14000])
    }
}
