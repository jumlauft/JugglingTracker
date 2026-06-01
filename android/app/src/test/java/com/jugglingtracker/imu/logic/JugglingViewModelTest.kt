package com.jugglingtracker.imu.logic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JugglingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: JugglingViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = JugglingViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `test import session from watch`() = runTest {
        val payload = mapOf(
            "type" to "session",
            "balls" to 3,
            "timestamp" to 1716931200L, // Example epoch
            "runs" to listOf(10, 20, 15)
        )

        viewModel.importSessionFromWatch(payload)
        advanceUntilIdle()

        assertEquals(1, viewModel.completedSessions.size)
        val summary = viewModel.completedSessions[0]
        assertEquals(3, summary.ballCount)
        assertEquals(3, summary.runCount)
        assertEquals(45, summary.totalThrows)
    }
}
