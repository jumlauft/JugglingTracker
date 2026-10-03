package com.juggling.tracker.logic

import com.juggling.tracker.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * A recorded run arrives in several messages, and the watch card must stay on
 * "receiving" from the first to the last, not flick back to "ready" between
 * chunks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchCardReceivingTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var inbox: WatchInbox
    private lateinit var viewModel: JugglingViewModel

    @Before
    fun setup() {
        inbox = WatchInbox()
        viewModel = JugglingViewModel(inbox = inbox)
        viewModel.onGarminLinkStatus(GarminConnectionStatus.READY, "Connected")
        viewModel.onWearLinkStatus(WearConnectionStatus.READY)
    }

    private val id = 1_780_000_000L
    private fun recStart(chunks: Int) = mapOf(
        "type" to "rec_start", "id" to id, "balls" to 3, "catches" to 10,
        "sampleRate" to 25, "samples" to chunks * 2, "chunks" to chunks,
    )
    private fun recChunk(i: Int) = mapOf(
        "type" to "rec_chunk", "id" to id, "i" to i,
        "x" to listOf(1, 2), "y" to listOf(1, 2), "z" to listOf(1, 2),
    )
    private fun recEnd() = mapOf("type" to "rec_end", "id" to id)

    @Test
    fun `the Garmin card stays on receiving while chunks keep arriving`() = runTest {
        inbox.receive(recStart(chunks = 4), WatchInbox.Source.GARMIN)
        for (i in 0 until 4) {
            // Well past the old 1.5 s flash between each chunk.
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(GarminConnectionStatus.RECEIVING, viewModel.garminStatus)
            inbox.receive(recChunk(i), WatchInbox.Source.GARMIN)
        }
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(GarminConnectionStatus.RECEIVING, viewModel.garminStatus)

        inbox.receive(recEnd(), WatchInbox.Source.GARMIN)
        assertEquals(GarminConnectionStatus.RECEIVING, viewModel.garminStatus)
        advanceTimeBy(JugglingViewModel.RECEIVING_DISPLAY_MS + 1)
        runCurrent()
        assertEquals(GarminConnectionStatus.READY, viewModel.garminStatus)
    }

    @Test
    fun `the Wear OS card stays on receiving while chunks keep arriving`() = runTest {
        inbox.receive(recStart(chunks = 2), WatchInbox.Source.WEAR_OS)
        advanceTimeBy(5_000)
        runCurrent()
        inbox.receive(recChunk(0), WatchInbox.Source.WEAR_OS)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(WearConnectionStatus.RECEIVING, viewModel.wearStatus)
        inbox.receive(recChunk(1), WatchInbox.Source.WEAR_OS)
        inbox.receive(recEnd(), WatchInbox.Source.WEAR_OS)
        advanceTimeBy(JugglingViewModel.RECEIVING_DISPLAY_MS + 1)
        runCurrent()
        assertEquals(WearConnectionStatus.READY, viewModel.wearStatus)
    }

    @Test
    fun `a transfer that stops halfway lets the card go back after a while`() = runTest {
        inbox.receive(recStart(chunks = 4), WatchInbox.Source.GARMIN)
        inbox.receive(recChunk(0), WatchInbox.Source.GARMIN)

        advanceTimeBy(JugglingViewModel.RECEIVING_STALL_MS + 1)
        runCurrent()

        assertEquals(GarminConnectionStatus.READY, viewModel.garminStatus)
    }

    @Test
    fun `a link status reported mid-transfer shows once the transfer ends`() = runTest {
        inbox.receive(recStart(chunks = 1), WatchInbox.Source.GARMIN)
        viewModel.onGarminLinkStatus(GarminConnectionStatus.DISCONNECTED, "Watch disconnected from phone")
        assertEquals(GarminConnectionStatus.RECEIVING, viewModel.garminStatus)

        advanceTimeBy(JugglingViewModel.RECEIVING_STALL_MS + 1)
        runCurrent()

        assertEquals(GarminConnectionStatus.DISCONNECTED, viewModel.garminStatus)
    }

    @Test
    fun `a single session shows receiving briefly`() = runTest {
        inbox.receive(
            mapOf("type" to "session", "balls" to 3, "timestamp" to 1L, "runs" to listOf(5)),
            WatchInbox.Source.GARMIN,
        )
        assertEquals(GarminConnectionStatus.RECEIVING, viewModel.garminStatus)
        advanceTimeBy(JugglingViewModel.RECEIVING_DISPLAY_MS + 1)
        runCurrent()
        assertEquals(GarminConnectionStatus.READY, viewModel.garminStatus)
    }
}
