package com.juggling.tracker.logic

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.juggling.tracker.data.SessionRepository
import com.juggling.tracker.shared.SessionCsv
import com.juggling.tracker.shared.SessionSummary
import com.juggling.tracker.shared.summarizeSession

/**
 * The session history the home screen lists: every stored session and the
 * ball count whose sessions are shown. Counterpart of the iPhone app's
 * HistoryModel.
 */
class HistoryModel(private val repository: SessionRepository?) {
    /** Newest first, as the repository keeps them. */
    val sessions = mutableStateListOf<SessionSummary>()

    /**
     * The ball count whose history the home screen shows: the chip the user
     * tapped last, or the ball count of the session a watch sent last,
     * whichever came later. Null until either happens. Kept here rather than
     * in the screen so a sync that lands while another screen is open is
     * still shown on the way back.
     */
    var historyBallCount by mutableStateOf<Int?>(null)
        private set

    init {
        repository?.getSessions()?.let { sessions.addAll(it) }
    }

    /** The ball counts the history has sessions for, smallest first. */
    val historyBallCounts: List<Int>
        get() = sessions.map { it.ballCount }.distinct().sorted()

    /**
     * The chip that is selected: [historyBallCount] while the history has
     * sessions for it, otherwise the smallest ball count. Null with no history.
     */
    val shownHistoryBallCount: Int?
        get() {
            val counts = historyBallCounts
            return historyBallCount?.takeIf { it in counts } ?: counts.firstOrNull()
        }

    fun selectHistoryBallCount(ballCount: Int) {
        historyBallCount = ballCount
    }

    /** Stores a session recorded on this phone and lists it. */
    fun store(
        balls: Int,
        timestamp: Long,
        runs: List<Int>,
        durationSeconds: Long,
        runDurationsMillis: List<Long>,
    ) {
        val session = repository?.importSession(balls, timestamp, runs, durationSeconds, runDurationsMillis, null)
            ?: summarizeSession(timestamp, balls, runs, durationSeconds, runDurationsMillis, null)
        show(session)
    }

    /**
     * Lists a session the inbox has already stored, and flips the history to
     * its ball count. Several sessions in one sync each pass through here, so
     * the last one wins.
     */
    fun showReceived(session: SessionSummary) {
        show(session)
        historyBallCount = session.ballCount
    }

    private fun show(session: SessionSummary) {
        if (repository != null) {
            // Reload from the repository so the list matches what is stored.
            sessions.clear()
            sessions.addAll(repository.getSessions())
            return
        }
        // No repository (unit tests): keep the list the way the repository
        // would, one session per timestamp, a resend replacing the old copy.
        val existing = sessions.indexOfFirst { it.timestamp == session.timestamp }
        if (existing >= 0) {
            sessions[existing] = session
        } else {
            sessions.add(0, session)
        }
    }

    fun delete(session: SessionSummary) {
        sessions.remove(session)
        repository?.deleteSession(session)
    }

    fun csv(): String = SessionCsv.write(sessions)

    /**
     * Add a backup's sessions to the history, skipping those already here.
     * Returns how many were added.
     */
    fun restore(backup: List<SessionSummary>): Int {
        if (repository != null) {
            val added = repository.restoreSessions(backup)
            sessions.clear()
            sessions.addAll(repository.getSessions())
            return added
        }
        val added = SessionCsv.newSessions(backup, sessions)
        sessions.addAll(added)
        sessions.sortByDescending { it.timestamp }
        return added.size
    }
}
