package com.juggling.tracker.wear.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Juggle mode, `MainView.mc` and its delegates. Each test names the
 * requirement in `connectiq/REQUIREMENTS.md` it secures.
 */
class TrackerSessionTest {
    private val scheduler = FakeScheduler()
    private val link = FakePhoneLink()
    private val effects = FakeEffects()
    private val session = TrackerSession(3, link, scheduler, scheduler.clock, effects, epochSeconds = { 1_700_000_000L })
    private var t = 0L

    private val state get() = session.state.value

    private fun feed(samples: List<AccelSample>) {
        // The sensor delivers roughly one batch a second.
        samples.chunked(25).forEach { session.onSamples(it) }
        t = Feeds.next(samples)
    }

    private fun warmUp() = feed(Feeds.warmup())
    private fun catches(n: Int) = feed(Feeds.catches(t, n))
    private fun idle(samples: Int = 60) = feed(Feeds.baseline(t, samples))

    private fun completedRun(n: Int) {
        catches(n)
        idle()
    }

    private fun select(id: String) = session.onMenuSelect(id)

    // ── JUG-1 ─────────────────────────────────────────────────────────

    @Test
    fun `JUG-1 shows run state live count and session stats`() {
        warmUp()
        assertFalse(state.runActive)
        catches(3)
        assertTrue(state.runActive)
        assertEquals(3, state.currentCount)
        idle()
        assertFalse(state.runActive)
        assertEquals(0, state.currentCount)
        assertEquals(3, state.previousCount)
        assertEquals(1, state.runs)
        assertEquals(3.0, state.average, 0.0)
        assertEquals(3, state.max)
    }

    @Test
    fun `JUG-1 elapsed time counts from the screen opening`() {
        scheduler.advance(65_000)
        session.tick()
        assertEquals(65, state.elapsedSeconds)
        assertEquals("1:05", Format.elapsed(state.elapsedSeconds))
        assertEquals("1:00:01", Format.elapsed(3601))
        assertEquals("-", Format.countOrDash(0))
        assertEquals("-", Format.averageOrDash(0.0))
        assertEquals("4.5", Format.averageOrDash(4.5))
    }

    // ── JUG-2 ─────────────────────────────────────────────────────────

    @Test
    fun `JUG-2 start stop opens the session end menu`() {
        session.onStartStop()
        val menu = state.menu!!
        assertEquals("End session?", menu.title)
        assertEquals(listOf("Sync and quit", "Quit without sync", "Continue"), menu.items.map { it.label })
    }

    @Test
    fun `JUG-2 the session end menu freezes the session clock and continue resumes it`() {
        scheduler.advance(10_000)
        session.onStartStop()
        scheduler.advance(20_000)
        session.tick()
        assertEquals(10, state.elapsedSeconds)
        select(TrackerSession.ITEM_CONTINUE)
        assertNull(state.menu)
        assertEquals(30, state.elapsedSeconds)
    }

    @Test
    fun `JUG-2 quit without sync exits without sending`() {
        warmUp()
        completedRun(3)
        session.onStartStop()
        select(TrackerSession.ITEM_NOSYNC_QUIT)
        assertEquals(1, effects.exits)
        assertTrue(link.sent.isEmpty())
    }

    // ── JUG-3 / JUG-4 / JUG-5 ─────────────────────────────────────────

    @Test
    fun `JUG-3 back during a run offers to discard this run`() {
        warmUp()
        catches(4)
        session.onBack()
        val menu = state.menu!!
        assertEquals("Discard this run?", menu.title)
        assertEquals("Discard", menu.items[0].label)
        assertEquals("4 catches", menu.items[0].subLabel)
        assertEquals("Keep", menu.items[1].label)

        select(TrackerSession.ITEM_DISCARD_YES)
        assertEquals(0, state.currentCount)
        assertEquals(0, state.runs)
        assertEquals(0, effects.exits)
    }

    @Test
    fun `JUG-3 back between runs offers to discard the last run`() {
        warmUp()
        completedRun(5)
        completedRun(3)
        session.onBack()
        assertEquals("Discard last run?", state.menu!!.title)
        assertEquals("3 catches", state.menu!!.items[0].subLabel)
        select(TrackerSession.ITEM_DISCARD_YES)
        assertEquals(1, state.runs)
        assertEquals(5, state.previousCount)
    }

    @Test
    fun `JUG-3 keep or backing out of the prompt changes nothing`() {
        warmUp()
        completedRun(3)
        session.onBack()
        select(TrackerSession.ITEM_DISCARD_NO)
        assertEquals(1, state.runs)

        session.onBack()
        session.onMenuBack()
        assertNull(state.menu)
        assertEquals(1, state.runs)
        assertEquals(0, effects.exits)
    }

    @Test
    fun `JUG-4 back with nothing recorded is swallowed and never exits`() {
        warmUp()
        session.onBack()
        assertNull(state.menu)
        assertEquals(0, effects.exits)
    }

    @Test
    fun `JUG-5 the discard prompt uses the app's own English labels`() {
        warmUp()
        catches(3)
        session.onBack()
        val labels = state.menu!!.items.map { it.label }
        assertEquals(listOf("Discard", "Keep"), labels)
    }

    // ── JUG-6 ─────────────────────────────────────────────────────────

    @Test
    fun `JUG-6 backing out of any menu leaves start stop working`() {
        warmUp()
        catches(3)
        session.onBack()
        session.onMenuBack()
        session.onStartStop()
        assertEquals("End session?", state.menu!!.title)
        session.onMenuBack()
        assertNull(state.menu)
        session.onStartStop()
        assertEquals("End session?", state.menu!!.title)
    }

    // ── JUG-7 ─────────────────────────────────────────────────────────

    @Test
    fun `JUG-7 vibrates every ten catches and resets when the run finishes`() {
        warmUp()
        catches(9)
        assertEquals(9, state.currentCount)
        assertEquals(0, effects.vibrations)
        // Two more bursts: the other hand, then the 10th watch-hand catch.
        feed(Feeds.burst(t))
        feed(Feeds.burst(t))
        assertEquals(10, state.currentCount)
        assertEquals(1, effects.vibrations)
        idle()
        assertEquals(1, effects.vibrations)
        catches(10)
        assertEquals("the counter restarts with the next run", 2, effects.vibrations)
    }

    // ── JUG-8 ─────────────────────────────────────────────────────────

    @Test
    fun `JUG-8 continue after a failed sync clears the banner`() {
        warmUp()
        completedRun(3)
        session.onStartStop()
        select(TrackerSession.ITEM_SYNC_QUIT)
        link.complete(false)
        assertEquals("Sync failed", state.errorMessage)
        assertEquals("Sync failed", state.menu!!.title)
        select(TrackerSession.ITEM_CONTINUE)
        assertNull(state.errorMessage)
    }

    // ── Detection pauses under a menu, as the Garmin sensor does ─────

    @Test
    fun `samples are ignored while a menu is open`() {
        warmUp()
        session.onStartStop()
        catches(3)
        session.onMenuBack()
        assertEquals(0, state.currentCount)
    }

    // ── SYNC-1 ────────────────────────────────────────────────────────

    @Test
    fun `SYNC-1 a session goes over as one message`() {
        warmUp()
        completedRun(3)
        catches(4) // still in progress: folded in on sync
        scheduler.advance(42_000)
        session.onStartStop()
        select(TrackerSession.ITEM_SYNC_QUIT)

        val payload = link.sent.single()
        assertEquals("session", payload["type"])
        assertEquals("watch_hand", payload["countMode"])
        assertEquals(3, payload["balls"])
        assertEquals(1_700_000_000L, payload["timestamp"])
        assertEquals(42L, payload["durationSeconds"])
        assertEquals(listOf(3, 4), payload["runs"])
        assertEquals(2, (payload["runDurationsMillis"] as List<*>).size)
        // SHAPE-4: the session's shape consistency goes along with it.
        assertTrue(state.shapeConsistency in 0..100)
        assertEquals(state.shapeConsistency, payload["shapeConsistency"])
        assertTrue(state.sending)
        assertEquals("Sync to phone.", Format.syncing(state.syncDots))
    }

    @Test
    fun `SYNC-1 shape consistency is left out until a run has been scored`() {
        warmUp()
        catches(3) // far too short for one window
        session.onStartStop()
        select(TrackerSession.ITEM_SYNC_QUIT)

        assertEquals(-1, state.shapeConsistency)
        assertFalse(link.sent.single().containsKey("shapeConsistency"))
    }

    // ── SYNC-2 ────────────────────────────────────────────────────────

    @Test
    fun `SYNC-2 delivery alone does not close the app, the ack does`() {
        warmUp()
        completedRun(3)
        session.onStartStop()
        select(TrackerSession.ITEM_SYNC_QUIT)
        link.complete(true)
        assertEquals(0, effects.exits)
        assertTrue(state.sending)
        link.ack(1_700_000_000L)
        assertEquals(1, effects.exits)
        assertTrue(state.exited)
    }

    @Test
    fun `SYNC-2 a timeout opens the retry menu and retry sends again`() {
        warmUp()
        completedRun(3)
        session.onStartStop()
        select(TrackerSession.ITEM_SYNC_QUIT)
        link.complete(true)
        scheduler.advance(TrackerSession.SYNC_TIMEOUT_MS)
        assertFalse(state.sending)
        assertEquals("Sync failed", state.menu!!.title)
        assertEquals(
            listOf("Retry sync", "Quit without sync", "Continue"),
            state.menu!!.items.map { it.label },
        )
        select(TrackerSession.ITEM_SYNC_RETRY)
        assertEquals(2, link.sent.size)
        assertEquals(link.sent[0], link.sent[1])
        link.ack()
        assertEquals(1, effects.exits)
    }

    @Test
    fun `SYNC-2 an ack arriving while the retry menu is open still closes the app`() {
        warmUp()
        completedRun(3)
        session.onStartStop()
        select(TrackerSession.ITEM_SYNC_QUIT)
        scheduler.advance(TrackerSession.SYNC_TIMEOUT_MS)
        assertEquals("Sync failed", state.menu!!.title)
        link.ack()
        assertNull(state.menu)
        assertEquals(1, effects.exits)
    }

    @Test
    fun `SYNC-2 an ack with nothing pending is ignored`() {
        link.ack()
        assertEquals(0, effects.exits)
    }

    // ── SYNC-3 ────────────────────────────────────────────────────────

    @Test
    fun `SYNC-3 a session with no completed runs is not sent`() {
        warmUp()
        catches(2) // a false start
        session.onStartStop()
        select(TrackerSession.ITEM_SYNC_QUIT)
        assertTrue(link.sent.isEmpty())
        assertEquals(1, effects.exits)
    }

    // ── SYNC-4 ────────────────────────────────────────────────────────

    @Test
    fun `SYNC-4 a late failure from an abandoned attempt does not abort the retry`() {
        warmUp()
        completedRun(3)
        session.onStartStop()
        select(TrackerSession.ITEM_SYNC_QUIT)
        scheduler.advance(TrackerSession.SYNC_TIMEOUT_MS)
        select(TrackerSession.ITEM_SYNC_RETRY)
        assertTrue(state.sending)

        link.complete(false, index = 0) // the first attempt reports back late
        assertTrue("the retry is still in flight", state.sending)
        assertNull(state.menu)
    }

    @Test
    fun `status dots cycle once a second while sending`() {
        warmUp()
        completedRun(3)
        session.onStartStop()
        select(TrackerSession.ITEM_SYNC_QUIT)
        assertEquals(1, state.syncDots)
        scheduler.advance(1_000)
        assertEquals(2, state.syncDots)
        scheduler.advance(2_000)
        assertEquals(1, state.syncDots)
    }
}
