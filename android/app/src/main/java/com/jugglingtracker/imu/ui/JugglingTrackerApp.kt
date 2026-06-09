package com.jugglingtracker.imu.ui

import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.jugglingtracker.imu.logic.GarminConnectionStatus
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
                    tts.speak(event.text, TextToSpeech.QUEUE_FLUSH, null, null)
                }
                is JugglingEvent.SyncStarted -> {
                    // Show brief feedback when sync starts
                    Toast.makeText(context, "Receiving data...", Toast.LENGTH_SHORT).show()
                }
                is JugglingEvent.SyncCompleted -> {
                    Toast.makeText(context, "Sync complete: ${event.count} runs received", Toast.LENGTH_LONG).show()
                    tts.speak("Synced ${event.count} runs", TextToSpeech.QUEUE_FLUSH, null, null)
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
            tts.stop()
            tts.shutdown()
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
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
        // Garmin Status Header
        GarminStatusHeader(
            status = viewModel.garminStatus,
            message = viewModel.statusMessage
        )

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
        }
    }
}

@Composable
fun GarminStatusHeader(status: GarminConnectionStatus, message: String) {
    val (backgroundColor, textColor, statusText) = when (status) {
        GarminConnectionStatus.READY -> Triple(
            Color(0xFFE8F5E9), // Light Green
            Color(0xFF2E7D32), // Dark Green
            "Ready to receive data from your watch"
        )
        GarminConnectionStatus.RECEIVING -> Triple(
            Color(0xFFFFF3E0), // Light Orange
            Color(0xFFEF6C00), // Dark Orange
            "Receiving data..."
        )
        GarminConnectionStatus.BLUETOOTH_DISABLED,
        GarminConnectionStatus.NO_PAIRED_DEVICES,
        GarminConnectionStatus.SDK_ERROR -> Triple(
            Color(0xFFFFEBEE), // Light Red
            Color(0xFFC62828), // Dark Red
            message
        )
        GarminConnectionStatus.NOT_INITIALIZED -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Connecting to Garmin..."
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.titleSmall,
                color = textColor,
                fontWeight = FontWeight.Bold
            )
            
            if (status == GarminConnectionStatus.BLUETOOTH_DISABLED || 
                status == GarminConnectionStatus.NO_PAIRED_DEVICES || 
                status == GarminConnectionStatus.SDK_ERROR) {
                Spacer(modifier = Modifier.height(4.dp))
                val advice = if (status == GarminConnectionStatus.BLUETOOTH_DISABLED) {
                    "Go to Android Settings to enable Bluetooth."
                } else {
                    "Ensure your watch is paired in the Garmin ConnectIQ app."
                }
                Text(
                    text = "Troubleshooting: $advice",
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(viewModel: JugglingViewModel) {
    val context = LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openOutputStream(it)?.use { outputStream ->
                    outputStream.write(viewModel.getSessionsCsv().toByteArray())
                }
            } catch (e: Exception) {
                Log.e("JugglingTrackerApp", "CSV export failed", e)
                Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val recordingLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openOutputStream(it)?.use { outputStream ->
                    outputStream.write(viewModel.getRecordingsCsv().toByteArray())
                }
                Toast.makeText(context, "Recordings exported", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("JugglingTrackerApp", "Recording export failed", e)
                Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

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
                    text = "Announce every ${viewModel.voiceInterval} watch-hand catches",
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

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Data Management", style = MaterialTheme.typography.titleLarge)
        
        Button(
            onClick = {
                val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                launcher.launch("juggling_history_$ts.csv")
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Export History to CSV")
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "IMU Recordings", style = MaterialTheme.typography.titleLarge)

        Text(
            text = "${viewModel.recordingCount} recording(s) stored",
            style = MaterialTheme.typography.bodyMedium,
        )

        Button(
            onClick = {
                val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                recordingLauncher.launch("juggling_recordings_$ts.csv")
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = viewModel.recordingCount > 0,
        ) {
            Text("Export Recordings to CSV")
        }

        var showClearConfirm by remember { mutableStateOf(false) }

        OutlinedButton(
            onClick = { showClearConfirm = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = viewModel.recordingCount > 0,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text("Clear All Recordings")
        }

        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                title = { Text("Clear Recordings") },
                text = { Text("Delete all ${viewModel.recordingCount} stored recordings? This cannot be undone.") },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.clearRecordings()
                            showClearConfirm = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text("Delete All")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirm = false }) {
                        Text("Cancel")
                    }
                },
            )
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
