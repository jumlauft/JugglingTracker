package com.jugglingtracker.imu.logic

import com.jugglingtracker.imu.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JugglingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var viewModel: JugglingViewModel

    @Before
    fun setup() {
        viewModel = JugglingViewModel()
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
