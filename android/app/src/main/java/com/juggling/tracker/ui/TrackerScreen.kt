package com.juggling.tracker.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.juggling.tracker.R
import com.juggling.tracker.data.WatchType
import com.juggling.tracker.logic.HistoryModel
import com.juggling.tracker.logic.WatchStatusModel
import com.juggling.tracker.shared.SessionSummary

/** The home screen: watch/phone header, ball-count tabs and session history. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerScreen(
    watchStatus: WatchStatusModel,
    history: HistoryModel,
    watchType: WatchType,
    onPhoneRecordClick: () -> Unit,
    onGarminLinkClick: () -> Unit,
    onGarminSessionClick: () -> Unit,
    onWearLinkClick: () -> Unit,
    onWearSessionClick: () -> Unit,
    onSessionClick: (SessionSummary) -> Unit,
) {
    var sessionToDelete by remember { mutableStateOf<SessionSummary?>(null) }

    if (sessionToDelete != null) {
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text(stringResource(R.string.dialog_delete_session_title)) },
            text = { Text(stringResource(R.string.dialog_delete_session_text)) },
            confirmButton = {
                Button(
                    onClick = {
                        sessionToDelete?.let { history.delete(it) }
                        sessionToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(space = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Garmin Status Header
        GarminStatusHeader(
            status = watchStatus.garminStatus,
            message = watchStatus.garminMessage,
            onPhoneRecordClick = onPhoneRecordClick,
            onGarminLinkClick = onGarminLinkClick,
            onGarminSessionClick = onGarminSessionClick,
            watchType = watchType,
            wearStatus = watchStatus.wearStatus,
            onWearLinkClick = onWearLinkClick,
            onWearSessionClick = onWearSessionClick,
        )

        if (history.sessions.isNotEmpty()) {
            Text(
                text = stringResource(R.string.section_session_history),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Start)
            )

            val selectedTabBallCount = history.shownHistoryBallCount

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                history.historyBallCounts.forEach { count ->
                    BallChip(
                        label = stringResource(R.string.format_balls_tab, count),
                        selected = selectedTabBallCount == count,
                        onClick = { history.selectHistoryBallCount(count) },
                    )
                }
            }

            val filteredSessions = history.sessions.filter { it.ballCount == selectedTabBallCount }

            SessionHistoryGraph(
                sessions = filteredSessions,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            )
            
            filteredSessions.forEach { session ->
                key(session.timestamp) {
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = {
                            if (it == SwipeToDismissBoxValue.EndToStart) {
                                sessionToDelete = session
                                false
                            } else false
                        },
                    )

                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {
                            val color = when (dismissState.dismissDirection) {
                                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.error
                                else -> Color.Transparent
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(vertical = 4.dp)
                                    .background(color, MaterialTheme.shapes.medium),
                            )
                        },
                        modifier = Modifier.animateContentSize(),
                    ) {
                        SessionHistoryItem(
                            session = session,
                        ) { onSessionClick(session) }
                    }
                }
            }
        } else {
            // Empty State
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 64.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.empty_history_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.empty_history_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}
