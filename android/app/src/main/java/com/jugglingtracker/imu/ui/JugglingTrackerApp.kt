package com.jugglingtracker.imu.ui

import android.speech.tts.TextToSpeech
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jugglingtracker.imu.logic.JugglingEvent
import com.jugglingtracker.imu.logic.JugglingViewModel
import com.jugglingtracker.imu.model.SessionSummary
import java.util.*
import kotlin.math.roundToInt

enum class Screen {
    Tracker, Settings
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JugglingTrackerApp(
    viewModel: JugglingViewModel,
    garminStatus: String,
    isWatchAppRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    var currentScreen by remember { mutableStateOf(Screen.Tracker) }
    val context = LocalContext.current

    // TTS Setup
    val tts = remember {
        var ttsInstance: TextToSpeech? = null
        ttsInstance = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsInstance?.language = Locale.US
            }
        }
        ttsInstance
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is JugglingEvent.Announcement -> {
                    tts?.speak(event.text, TextToSpeech.QUEUE_FLUSH, null, null)
                }
                is JugglingEvent.SyncCompleted -> {
                    // Toast or snackbar notification could be shown here
                    // For now, the session list will update automatically
                    tts?.speak("Synced ${event.count} runs", TextToSpeech.QUEUE_FLUSH, null, null)
                }
            }
        }
    }

    val selectedSessionForDetails = remember { mutableStateOf<SessionSummary?>(value = null) }

    if (selectedSessionForDetails.value != null) {
        SessionDetailsDialog(
            session = selectedSessionForDetails.value!!,
        ) { selectedSessionForDetails.value = null }
    }

    DisposableEffect(Unit) {
        onDispose { 
            tts?.stop()
            tts?.shutdown()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { 
                    Text(if (currentScreen == Screen.Settings) "Settings" else "Juggling Tracker") 
                },
                navigationIcon = {
                    if (currentScreen == Screen.Settings) {
                        IconButton(onClick = { currentScreen = Screen.Tracker }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (currentScreen == Screen.Tracker) {
                        IconButton(onClick = { currentScreen = Screen.Settings }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            if (currentScreen == Screen.Tracker) {
                TrackerScreen(
                    viewModel = viewModel,
                    garminStatus = garminStatus,
                    isWatchAppRunning = isWatchAppRunning,
                ) { selectedSessionForDetails.value = it }
            } else {
                SettingsScreen(viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerScreen(
    viewModel: JugglingViewModel,
    garminStatus: String,
    isWatchAppRunning: Boolean,
    onSessionClick: (SessionSummary) -> Unit,
) {
    var sessionToDelete by remember { mutableStateOf<SessionSummary?>(null) }

    if (sessionToDelete != null) {
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text("Delete Session") },
            text = { Text("Are you sure you want to delete this session from your history?") },
            confirmButton = {
                Button(
                    onClick = {
                        sessionToDelete?.let { viewModel.deleteSession(it) }
                        sessionToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) {
                    Text("Cancel")
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
        // Garmin Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isWatchAppRunning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = "GARMIN STATUS", style = MaterialTheme.typography.labelSmall)
                Text(text = garminStatus, style = MaterialTheme.typography.bodyMedium)
                if (isWatchAppRunning) {
                    Text(
                        text = "Watch App: Running",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF4CAF50),
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(
                        text = "Watch App: Stopped",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // The watch is the sole session controller; the phone only listens for
        // finished sessions and records them. No session is started here.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                text = if (isWatchAppRunning) {
                    "Listening for sessions from your watch…"
                } else {
                    "Start a session on your watch. Results appear here automatically."
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp)
            )
        }

        if (viewModel.completedSessions.isNotEmpty()) {
            Text(
                text = "Session History",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Start)
            )

            val availableBallCounts = viewModel.completedSessions
                .asSequence()
                .map { it.ballCount }
                .distinct()
                .sorted()
                .toList()
            
            var selectedTabBallCount by remember { mutableIntStateOf(availableBallCounts.first()) }
            
            if (selectedTabBallCount !in availableBallCounts) {
                selectedTabBallCount = availableBallCounts.first()
            }

            ScrollableTabRow(
                selectedTabIndex = availableBallCounts.indexOf(selectedTabBallCount),
                edgePadding = 0.dp,
                containerColor = Color.Transparent,
                divider = {},
            ) {
                availableBallCounts.forEach { count ->
                    Tab(
                        selected = selectedTabBallCount == count,
                        onClick = { selectedTabBallCount = count },
                        text = { Text("$count Balls") }
                    )
                }
            }

            val filteredSessions = viewModel.completedSessions.filter { it.ballCount == selectedTabBallCount }

            SessionHistoryGraph(
                sessions = filteredSessions,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            )
            
            filteredSessions.forEach { session ->
                key(session.id) {
                    val dismissState = rememberDismissState(
                        confirmValueChange = {
                            if (it == DismissValue.DismissedToStart) {
                                sessionToDelete = session
                                false
                            } else false
                        },
                    )

                    SwipeToDismiss(
                        state = dismissState,
                        background = {
                            val color = when (dismissState.dismissDirection) {
                                DismissDirection.EndToStart -> MaterialTheme.colorScheme.error
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
                        dismissContent = {
                            SessionHistoryItem(
                                session = session,
                            ) { onSessionClick(session) }
                        }
                    )
                }
            }

            OutlinedButton(
                onClick = { viewModel.clearAllHistory() },
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text("Clear All History")
            }
        } else {
            Text(
                text = "Tap 'Start New Session' to begin tracking.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(vertical = 32.dp)
            )
        }
    }
}

@Composable
fun SettingsScreen(viewModel: JugglingViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(all = 16.dp)
            .verticalScroll(state = rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(space = 16.dp),
    ) {
        Text(text = "Feedback Settings", style = MaterialTheme.typography.titleLarge)

        SettingToggle(
            label = "Voice Announcements",
            checked = viewModel.isVoiceEnabled,
        ) { viewModel.isVoiceEnabled = it }
        
        if (viewModel.isVoiceEnabled) {
            Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(
                    text = "Announce every ${viewModel.voiceInterval} throws",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = viewModel.voiceInterval.toFloat(),
                    onValueChange = { viewModel.voiceInterval = it.roundToInt() },
                    valueRange = 5f..100f,
                    steps = 18 // Increments of 5
                )
            }
        }
    }
}

@Composable
fun SettingToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(intrinsicSize = IntrinsicSize.Min)
                .toggleable(
                    value = checked,
                    onValueChange = onCheckedChange,
                    role = Role.Switch,
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(weight = 1f),
            )
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}
