package com.jugglingtracker.imu.ui

import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
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
    Tracker, PhoneSession, Settings
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JugglingTrackerApp(
    viewModel: JugglingViewModel,
    isWatchAppRunning: Boolean,
    onStartPhoneSession: (Int) -> Boolean,
    onStopPhoneSession: () -> Boolean,
    onCancelPhoneSession: () -> Unit,
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
                    // No toast when sync starts as per user request
                }
                is JugglingEvent.SyncCompleted -> {
                    Toast.makeText(context, "Received ${event.count} runs for ${event.ballCount} balls", Toast.LENGTH_LONG).show()
                    tts.speak("Synced ${event.count} runs", TextToSpeech.QUEUE_FLUSH, null, null)
                }
                is JugglingEvent.PhoneSessionSaved -> {
                    Toast.makeText(context, "Saved ${event.count} phone runs for ${event.ballCount} balls", Toast.LENGTH_LONG).show()
                    tts.speak("Saved ${event.count} runs", TextToSpeech.QUEUE_FLUSH, null, null)
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
                    Text(
                        when (currentScreen) {
                            Screen.Settings -> "Settings"
                            Screen.PhoneSession -> "Phone Tracker"
                            Screen.Tracker -> "Juggling Tracker"
                        }
                    )
                },
                navigationIcon = {
                    if (currentScreen != Screen.Tracker) {
                        IconButton(
                            onClick = {
                                if (currentScreen == Screen.PhoneSession) onCancelPhoneSession()
                                currentScreen = Screen.Tracker
                            },
                        ) {
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
                    onPhoneRecordClick = { currentScreen = Screen.PhoneSession },
                ) { selectedSessionForDetails.value = it }
            } else if (currentScreen == Screen.PhoneSession) {
                PhoneSessionScreen(
                    viewModel = viewModel,
                    onBack = { currentScreen = Screen.Tracker },
                    onStartPhoneSession = onStartPhoneSession,
                    onStopPhoneSession = onStopPhoneSession,
                    onCancelPhoneSession = onCancelPhoneSession,
                )
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
    onPhoneRecordClick: () -> Unit,
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
            message = viewModel.statusMessage,
            onPhoneRecordClick = onPhoneRecordClick,
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
                        text = "No sessions yet",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Start juggling on your watch or phone and\nresults will appear here automatically.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun GarminStatusHeader(
    status: GarminConnectionStatus,
    message: String,
    onPhoneRecordClick: () -> Unit,
) {
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start,
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

            FilledTonalButton(onClick = onPhoneRecordClick) {
                Icon(Icons.Default.PhoneAndroid, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Phone")
            }
        }
    }
}

@Composable
fun PhoneSessionScreen(
    viewModel: JugglingViewModel,
    onBack: () -> Unit,
    onStartPhoneSession: (Int) -> Boolean,
    onStopPhoneSession: () -> Boolean,
    onCancelPhoneSession: () -> Unit,
) {
    val state = viewModel.phoneSessionState

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.isRecording) {
            StatusIndicator(isJuggling = state.currentCount > 0)

            Text(
                text = state.statusMessage,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            CurrentPhoneRunCard(
                currentCount = state.currentCount,
                previousCount = state.previousCount,
            )

            PhoneSessionStats(
                runCount = state.sessionRunCount,
                average = state.sessionAverage,
                max = state.sessionMax,
                elapsedSeconds = state.elapsedSeconds,
            )

            if (state.completedRuns.isNotEmpty()) {
                Text(
                    text = "Runs: ${state.completedRuns.joinToString("  ")}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Start),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        onCancelPhoneSession()
                        onBack()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        if (onStopPhoneSession()) onBack()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save")
                }
            }
        } else {
            Text(
                text = "Ball Count",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Start),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (3..9).forEach { count ->
                    FilterChip(
                        selected = state.selectedBallCount == count,
                        onClick = { viewModel.selectPhoneBallCount(count) },
                        label = { Text("$count") },
                    )
                }
            }

            if (state.sensorError != null) {
                Text(
                    text = state.sensorError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Start),
                )
            }

            Button(
                onClick = { onStartPhoneSession(state.selectedBallCount) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Start")
            }
        }
    }
}

@Composable
private fun CurrentPhoneRunCard(currentCount: Int, previousCount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (currentCount > 0) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = "COUNTING HAND", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = currentCount.toString(),
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = "PREVIOUS RUN", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = if (previousCount == 0) "-" else previousCount.toString(),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PhoneSessionStats(
    runCount: Int,
    average: Double,
    max: Int,
    elapsedSeconds: Long,
) {
    val avgText = if (runCount == 0) "-" else "%.1f".format(average)
    val maxText = if (max == 0) "-" else max.toString()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        PhoneStat("Runs", runCount.toString(), Modifier.weight(1f))
        PhoneStat("Avg", avgText, Modifier.weight(1f))
        PhoneStat("Max", maxText, Modifier.weight(1f))
        PhoneStat("Time", formatPhoneElapsedSeconds(elapsedSeconds), Modifier.weight(1f))
    }
}

@Composable
private fun PhoneStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall)
        Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

private fun formatPhoneElapsedSeconds(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds / 60) % 60
    val seconds = totalSeconds % 60
    fun twoDigits(value: Long) = if (value < 10) "0$value" else value.toString()
    return if (hours > 0) {
        "$hours:${twoDigits(minutes)}:${twoDigits(seconds)}"
    } else {
        "$minutes:${twoDigits(seconds)}"
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(all = 16.dp)
            .verticalScroll(state = rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(space = 16.dp),
    ) {
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
