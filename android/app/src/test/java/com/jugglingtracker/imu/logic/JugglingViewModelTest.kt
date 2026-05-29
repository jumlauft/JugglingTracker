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
    fun `test session management`() = runTest {
        // Start session
        viewModel.startSession(5)
        assertTrue(viewModel.isSessionActive)
        assertEquals(5, viewModel.ballCount)

        // Add some runs
        viewModel.onRunFinished(10)
        viewModel.onRunFinished(20)
        viewModel.onRunFinished(15)

        // Advance until idle to process announcements in viewModelScope if any
        advanceUntilIdle()

        assertEquals(3, viewModel.runHistory.size)
        assertEquals(15.0, viewModel.runHistory.average(), 0.1)

        // Finish session
        viewModel.finishSession()
        assertFalse(viewModel.isSessionActive)
        assertEquals(1, viewModel.completedSessions.size)
        
        val summary = viewModel.completedSessions[0]
        assertEquals(5, summary.ballCount)
        assertEquals(3, summary.runCount)
        assertEquals(15.0, summary.avgThrows, 0.1)
        assertEquals(20, summary.bestRun)
        assertEquals(45, summary.totalThrows)
    }

    @Test
    fun `test clear history`() = runTest {
        viewModel.startSession(3)
        viewModel.onRunFinished(10)
        advanceUntilIdle()
        viewModel.finishSession()
        
        assertEquals(1, viewModel.completedSessions.size)
        viewModel.clearAllHistory()
        assertEquals(0, viewModel.completedSessions.size)
    }
}
