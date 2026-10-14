import Foundation
import JugglingCore
import Observation

/// The session history the home screen lists: every stored session and the
/// ball count whose sessions are shown. Counterpart of the Android app's
/// `HistoryModel`.
@MainActor
@Observable
final class HistoryModel {
    @ObservationIgnored let sessionStore: SessionStore?

    /// Newest first, as the session store keeps them.
    private(set) var sessions: [SessionSummary] = []
    /// The ball count whose history the home screen shows: the chip the user
    /// tapped last, or the ball count of the session a watch sent last,
    /// whichever came later. Nil until either happens. Kept here rather than
    /// in the screen so a sync that lands while another screen is open is
    /// still shown on the way back.
    private(set) var historyBallCount: Int?

    init(sessionStore: SessionStore?) {
        self.sessionStore = sessionStore
        sessions = sessionStore?.sessions ?? []
    }

    /// The ball counts the history has sessions for, smallest first.
    var historyBallCounts: [Int] {
        Array(Set(sessions.map(\.ballCount))).sorted()
    }

    /// The chip that is selected: `historyBallCount` while the history has
    /// sessions for it, otherwise the smallest ball count. Nil with no history.
    var shownHistoryBallCount: Int? {
        let counts = historyBallCounts
        if let historyBallCount, counts.contains(historyBallCount) { return historyBallCount }
        return counts.first
    }

    func selectHistoryBallCount(_ ballCount: Int) {
        historyBallCount = ballCount
    }

    /// Stores a session recorded on this phone and lists it.
    func store(_ session: SessionSummary) {
        sessionStore?.store(session)
        show(session)
    }

    /// Lists a session the inbox has already stored, and flips the history to
    /// its ball count. Several sessions in one sync each pass through here, so
    /// the last one wins.
    func showReceived(_ session: SessionSummary) {
        show(session)
        historyBallCount = session.ballCount
    }

    private func show(_ session: SessionSummary) {
        if let sessionStore {
            sessions = sessionStore.sessions
            return
        }
        // No store (tests): keep the list the way the store would.
        if let existing = sessions.firstIndex(where: { $0.timestamp == session.timestamp }) {
            sessions[existing] = session
        } else {
            sessions.insert(session, at: 0)
        }
    }

    func delete(_ session: SessionSummary) {
        sessions.removeAll { $0 == session }
        sessionStore?.delete(session)
    }

    /// Adds a backup's sessions, skipping those already here. Returns how many were added.
    func restore(_ backup: [SessionSummary]) -> Int {
        if let sessionStore {
            let added = sessionStore.restore(backup)
            sessions = sessionStore.sessions
            return added
        }
        let added = SessionCSV.newSessions(backup, existing: sessions)
        sessions = (sessions + added).sorted { $0.timestamp > $1.timestamp }
        return added.count
    }
}
