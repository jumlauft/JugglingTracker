package com.juggling.tracker.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class RecordingHeaderTest {
    private val ada = Juggler("Ada", RecordingHeader.HAND_LEFT, RecordingHeader.HAND_RIGHT)

    @Test
    fun `the header line carries every field, then the juggler`() {
        assertEquals(
            "# run=20260922_144802,timestamp=1790081282,balls=3,catches=89,sampleRate=25,units=milli_g," +
                "source=watch,countMode=watch_hand,detectedAtCapture=88,juggler=Ada,hand=left,firstThrow=right",
            RecordingHeader.line("20260922_144802", 1790081282L, 3, 89, 25, "watch", 88, ada),
        )
    }

    @Test
    fun `the run id is the capture time in the given time zone`() {
        assertEquals("20260922_144802", RecordingHeader.runId(1790081282L, TimeZone.getTimeZone("Europe/Berlin")))
    }

    @Test
    fun `a header reads back its juggler`() {
        val line = RecordingHeader.line("r", 1L, 3, 1, 25, "phone", 1, ada)
        val fields = RecordingHeader.fields(line)

        assertEquals("3", fields["balls"])
        assertEquals(ada, RecordingHeader.jugglerOf(fields))
    }

    @Test
    fun `a header without all three juggler fields names no juggler`() {
        assertNull(RecordingHeader.jugglerOf(RecordingHeader.fields("# run=r,juggler=Ada,hand=left")))
        assertNull(RecordingHeader.jugglerOf(RecordingHeader.fields("# run=r,juggler=Ada,hand=up,firstThrow=left")))
    }

    @Test
    fun `withJuggler replaces the juggler a header already names`() {
        val line = RecordingHeader.line("r", 1L, 3, 1, 25, "watch", 1, ada)
        val bob = Juggler("Bob", RecordingHeader.HAND_RIGHT, RecordingHeader.HAND_LEFT)

        val retagged = RecordingHeader.withJuggler(line, bob)

        assertEquals(bob, RecordingHeader.jugglerOf(RecordingHeader.fields(retagged)))
        assertEquals(1, Regex("juggler=").findAll(retagged).count())
    }

    @Test
    fun `a name cannot break the header format`() {
        assertEquals("Doe John", RecordingHeader.headerSafe("Doe, John"))
        assertEquals("a b c", RecordingHeader.headerSafe(" a=b#\nc "))
    }
}
