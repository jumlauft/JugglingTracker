package com.juggling.tracker.ui

import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juggling.tracker.R
import com.juggling.tracker.logic.GarminConnectionStatus
import com.juggling.tracker.logic.JugglingEvent
import com.juggling.tracker.logic.JugglingViewModel
import com.juggling.tracker.model.SessionSummary
import java.util.*

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
    onStartRawRecording: (Int) -> Boolean,
    onStopRawRecording: () -> Unit,
    onCancelRawRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var currentScreen by remember { mutableStateOf(Screen.Tracker) }
    var showQuitConfirmation by remember { mutableStateOf(false) }
    var showGarminLinkInstructions by remember { mutableStateOf(false) }
    var showGarminSessionInstructions by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (showGarminLinkInstructions) {
        GarminLinkDialog(onDismiss = { showGarminLinkInstructions = false })
    }

    if (showGarminSessionInstructions) {
        GarminSessionDialog(onDismiss = { showGarminSessionInstructions = false })
    }

    RawRecordingFlow(
        viewModel = viewModel,
        onStart = onStartRawRecording,
        onStop = onStopRawRecording,
        onCancel = onCancelRawRecording,
    )

    BackHandler(enabled = currentScreen != Screen.Tracker) {
        if (currentScreen == Screen.PhoneSession && viewModel.phoneSessionState.isRecording) {
            showQuitConfirmation = true
        } else {
            currentScreen = Screen.Tracker
        }
    }

    if (showQuitConfirmation) {
        AlertDialog(
            onDismissRequest = { showQuitConfirmation = false },
            title = { Text(stringResource(R.string.dialog_quit_session_title)) },
            text = { Text(stringResource(R.string.dialog_quit_session_text)) },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            onStopPhoneSession()
                            showQuitConfirmation = false
                            currentScreen = Screen.Tracker
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.action_save_and_quit))
                    }
                    Button(
                        onClick = {
                            onCancelPhoneSession()
                            showQuitConfirmation = false
                            currentScreen = Screen.Tracker
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.action_quit_without_saving))
                    }
                    TextButton(
                        onClick = { showQuitConfirmation = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.action_continue_session))
                    }
                }
            }
        )
    }

    // TTS Setup
    var isTtsReady by remember { mutableStateOf(false) }
    val tts = remember {
        TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isTtsReady = true
            }
        }
    }

    LaunchedEffect(tts, isTtsReady) {
        if (isTtsReady) {
            tts.language = Locale.US
        }
    }

    LaunchedEffect(viewModel, isTtsReady) {
        viewModel.events.collect { event ->
            when (event) {
                is JugglingEvent.Announcement -> {
                    if (isTtsReady) tts.speak(event.text, TextToSpeech.QUEUE_FLUSH, null, null)
                }
                is JugglingEvent.SyncCompleted -> {
                    Toast.makeText(context, context.getString(R.string.toast_sync_completed, event.count, event.ballCount), Toast.LENGTH_LONG).show()
                    if (isTtsReady) tts.speak(context.getString(R.string.toast_sync_completed, event.count, event.ballCount), TextToSpeech.QUEUE_FLUSH, null, null)
                }
                is JugglingEvent.PhoneSessionSaved -> {
                    Toast.makeText(context, context.getString(R.string.toast_phone_session_saved, event.count, event.ballCount), Toast.LENGTH_LONG).show()
                    if (isTtsReady) tts.speak(context.getString(R.string.toast_phone_session_saved, event.count, event.ballCount), TextToSpeech.QUEUE_FLUSH, null, null)
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
                            Screen.Settings -> stringResource(R.string.screen_settings)
                            Screen.PhoneSession -> stringResource(R.string.screen_phone_tracker)
                            Screen.Tracker -> stringResource(R.string.screen_tracker)
                        }
                    )
                },
                navigationIcon = {
                    if (currentScreen != Screen.Tracker) {
                        IconButton(
                            onClick = {
                                if (currentScreen == Screen.PhoneSession && viewModel.phoneSessionState.isRecording) {
                                    showQuitConfirmation = true
                                } else {
                                    if (currentScreen == Screen.PhoneSession) onCancelPhoneSession()
                                    currentScreen = Screen.Tracker
                                }
                            },
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
                actions = {
                    if (currentScreen == Screen.Tracker) {
                        IconButton(onClick = { currentScreen = Screen.Settings }) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.screen_settings))
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
                    onGarminLinkClick = { showGarminLinkInstructions = true },
                    onGarminSessionClick = { showGarminSessionInstructions = true },
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
    onGarminLinkClick: () -> Unit,
    onGarminSessionClick: () -> Unit,
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
                        sessionToDelete?.let { viewModel.deleteSession(it) }
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
            status = viewModel.garminStatus,
            message = viewModel.statusMessage,
            onPhoneRecordClick = onPhoneRecordClick,
            onGarminLinkClick = onGarminLinkClick,
            onGarminSessionClick = onGarminSessionClick,
        )

        if (viewModel.completedSessions.isNotEmpty()) {
            Text(
                text = stringResource(R.string.section_session_history),
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
                        text = { Text(stringResource(R.string.format_balls_tab, count)) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GarminStatusHeader(
    status: GarminConnectionStatus,
    message: String,
    onPhoneRecordClick: () -> Unit,
    onGarminLinkClick: () -> Unit,
    onGarminSessionClick: () -> Unit,
) {
    val (backgroundColor, textColor, statusText) = when (status) {
        GarminConnectionStatus.READY -> Triple(
            Color(0xFFE8F5E9), // Light Green
            Color(0xFF2E7D32), // Dark Green
            stringResource(R.string.garmin_ready)
        )
        GarminConnectionStatus.RECEIVING -> Triple(
            Color(0xFFFFF3E0), // Light Orange
            Color(0xFFEF6C00), // Dark Orange
            stringResource(R.string.garmin_receiving)
        )
        GarminConnectionStatus.CONNECT_IQ_MISSING -> Triple(
            Color(0xFFFFEBEE), // Light Red
            Color(0xFFC62828), // Dark Red
            stringResource(R.string.garmin_missing)
        )
        GarminConnectionStatus.WATCH_APP_MISSING -> Triple(
            Color(0xFFFFEBEE), // Light Red
            Color(0xFFC62828), // Dark Red
            stringResource(R.string.garmin_watch_app_missing)
        )
        GarminConnectionStatus.DISCONNECTED -> Triple(
            Color(0xFFFFEBEE), // Light Red
            Color(0xFFC62828), // Dark Red
            stringResource(R.string.garmin_disconnected)
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
            stringResource(R.string.garmin_connecting)
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Max)
            .animateContentSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Garmin Column
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            onClick = {
                when (status) {
                    GarminConnectionStatus.READY -> onGarminSessionClick()
                    GarminConnectionStatus.RECEIVING,
                    GarminConnectionStatus.NOT_INITIALIZED -> {}
                    else -> onGarminLinkClick() // Show checklist for any error/disconnected state
                }
            },
            colors = CardDefaults.cardColors(containerColor = backgroundColor)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Watch,
                    contentDescription = null,
                    tint = textColor
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (status == GarminConnectionStatus.READY ||
                        status == GarminConnectionStatus.RECEIVING ||
                        status == GarminConnectionStatus.WATCH_APP_MISSING
                    ) {
                        statusText
                    } else if (status == GarminConnectionStatus.NOT_INITIALIZED) {
                        stringResource(R.string.label_record_with_garmin)
                    } else {
                        stringResource(R.string.garmin_missing)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = textColor,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                if (status == GarminConnectionStatus.RECEIVING) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor.copy(alpha = 0.8f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }

        // Phone Column
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            onClick = onPhoneRecordClick,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.PhoneAndroid, contentDescription = null)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.action_start_phone_session),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
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
                    text = "${stringResource(R.string.stat_runs)}: ${state.completedRuns.joinToString("  ")}",
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
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = {
                        if (onStopPhoneSession()) onBack()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_save))
                }
            }
        } else {
            Text(
                text = stringResource(R.string.phone_session_instructions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(R.string.section_ball_count),
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
                Text(stringResource(R.string.action_start))
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
                Text(text = stringResource(R.string.stat_counting_hand), style = MaterialTheme.typography.labelMedium)
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
                Text(text = stringResource(R.string.stat_previous_run), style = MaterialTheme.typography.labelMedium)
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
        PhoneStat(stringResource(R.string.stat_runs), runCount.toString(), Modifier.weight(1f))
        PhoneStat(stringResource(R.string.stat_avg), avgText, Modifier.weight(1f))
        PhoneStat(stringResource(R.string.stat_max), maxText, Modifier.weight(1f))
        PhoneStat(stringResource(R.string.stat_time), formatPhoneElapsedSeconds(elapsedSeconds), Modifier.weight(1f))
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
    val sessionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openOutputStream(it)?.use { outputStream ->
                    outputStream.write(viewModel.getSessionsCsv().toByteArray())
                }
            } catch (e: Exception) {
                Log.e("JugglingTrackerApp", "CSV export failed", e)
                Toast.makeText(context, context.getString(R.string.toast_export_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val recordingLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openOutputStream(it)?.use { outputStream ->
                    viewModel.writeRecordingsZip(outputStream)
                }
            } catch (e: Exception) {
                Log.e("JugglingTrackerApp", "Recording export failed", e)
                Toast.makeText(context, context.getString(R.string.toast_export_failed), Toast.LENGTH_SHORT).show()
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
        // Voice Settings
        Text(text = stringResource(R.string.section_voice), style = MaterialTheme.typography.titleLarge)
        
        SettingToggle(
            label = stringResource(R.string.label_voice_enabled),
            checked = viewModel.isVoiceEnabled,
            onCheckedChange = { viewModel.toggleVoice(it) }
        )

        if (viewModel.isVoiceEnabled) {
            Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(
                    text = stringResource(R.string.label_voice_interval),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(1, 2, 5, 10, 20, 50, 100).forEach { interval ->
                        FilterChip(
                            selected = viewModel.voiceInterval == interval,
                            onClick = { viewModel.setVoiceInterval(interval) },
                            label = { Text(stringResource(R.string.format_voice_interval, interval)) }
                        )
                    }
                }
            }
        }

        HorizontalDivider()

        // Privacy Settings
        Text(text = stringResource(R.string.section_privacy), style = MaterialTheme.typography.titleLarge)
        
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingToggle(
                label = stringResource(R.string.label_share_data),
                checked = viewModel.isAnalyticsEnabled,
                onCheckedChange = { viewModel.toggleAnalytics(it) }
            )
            Text(
                text = stringResource(R.string.desc_share_data),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }

        HorizontalDivider()

        // Data Management
        Text(text = stringResource(R.string.section_data_management), style = MaterialTheme.typography.titleLarge)
        
        Button(
            onClick = {
                val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                sessionLauncher.launch("juggling_history_$ts.csv")
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.action_export_csv))
        }

        HorizontalDivider()

        // Raw Data Recording
        Text(text = stringResource(R.string.section_raw_recording), style = MaterialTheme.typography.titleLarge)
        
        Button(
            onClick = { viewModel.startRawRecordingFlow() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.action_start_raw_recording))
        }

        if (viewModel.recordingCount > 0) {
            RecordingsTable(viewModel.recordings)

            Button(
                onClick = {
                    val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                    recordingLauncher.launch("juggling_recordings_$ts.zip")
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text(stringResource(R.string.action_export_recordings))
            }

            Button(
                onClick = { emailRecordings(context, viewModel) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text(stringResource(R.string.action_email_recordings))
            }

            OutlinedButton(
                onClick = { viewModel.clearRecordings() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text(stringResource(R.string.action_clear_recordings))
            }
        }
    }
}

@Composable
fun RawRecordingFlow(
    viewModel: JugglingViewModel,
    onStart: (Int) -> Boolean,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    val state = viewModel.rawRecordingState
    
    when (state.step) {
        com.juggling.tracker.logic.RawRecordingStep.SELECT_BALLS -> {
            var balls by remember { mutableIntStateOf(state.selectedBallCount) }
            AlertDialog(
                onDismissRequest = onCancel,
                title = { Text(stringResource(R.string.section_ball_count)) },
                text = {
                    Column {
                        Text(stringResource(R.string.phone_session_instructions))
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            (3..9).forEach { b ->
                                FilterChip(
                                    selected = balls == b,
                                    onClick = { balls = b },
                                    label = { Text(b.toString()) }
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { onStart(balls) }) {
                        Text(stringResource(R.string.action_start))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
        com.juggling.tracker.logic.RawRecordingStep.RECORDING -> {
            AlertDialog(
                onDismissRequest = { /* No dismiss */ },
                title = { Text(stringResource(R.string.dialog_raw_recording_title)) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.label_samples_recorded, state.sampleCount),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Detected: ${viewModel.phoneSessionState.currentCount}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("STOP")
                    }
                }
            )
        }
        com.juggling.tracker.logic.RawRecordingStep.ENTER_CATCHES -> {
            var catchesText by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = onCancel,
                title = { Text(stringResource(R.string.dialog_enter_catches_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.dialog_enter_catches_text))
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = catchesText,
                            onValueChange = { if (it.all { char -> char.isDigit() }) catchesText = it },
                            label = { Text(stringResource(R.string.label_catches)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                            )
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { viewModel.saveRawRecording(catchesText.toIntOrNull() ?: 0) },
                        enabled = catchesText.isNotEmpty()
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
        else -> {}
    }
}

@Composable
fun GarminSessionDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_garmin_session_title)) },
        text = { Text(stringResource(R.string.dialog_garmin_session_text)) },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
fun GarminLinkDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_garmin_link_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.dialog_garmin_link_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(stringResource(R.string.dialog_garmin_condition_bluetooth))
                Text(stringResource(R.string.dialog_garmin_condition_permissions))
                Text(stringResource(R.string.dialog_garmin_condition_connect_app))
                Text(stringResource(R.string.dialog_garmin_condition_ciq_app))
                Text(stringResource(R.string.dialog_garmin_condition_open))
                Text(stringResource(R.string.dialog_garmin_condition_range))
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            data = android.net.Uri.parse("market://details?id=com.garmin.android.apps.connectmobile")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            data = android.net.Uri.parse("https://play.google.com/store/apps/details?id=com.garmin.android.apps.connectmobile")
                        }
                        context.startActivity(intent)
                    }
                }
            ) {
                Text(stringResource(R.string.action_open_play_store))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
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

private val recordingTimeFormat =
    java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())

// The developer collects recordings to tune the detector offline.
private const val DEVELOPER_EMAIL = "jugglingtracker@gmail.com"

// Write the merged recordings CSV to a shareable cache file and open an email
// draft to the developer with it attached.
private fun emailRecordings(context: android.content.Context, viewModel: JugglingViewModel) {
    if (viewModel.recordingCount <= 0) {
        Toast.makeText(context, context.getString(R.string.toast_no_recordings), Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = java.io.File(dir, "juggling_recordings_$ts.zip")
        file.outputStream().use { viewModel.writeRecordingsZip(it) }

        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf(DEVELOPER_EMAIL))
            putExtra(android.content.Intent.EXTRA_SUBJECT, context.getString(R.string.email_recordings_subject))
            putExtra(android.content.Intent.EXTRA_TEXT, context.getString(R.string.email_recordings_body))
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = android.content.Intent.createChooser(
            intent, context.getString(R.string.email_recordings_chooser)
        )
        try {
            context.startActivity(chooser)
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.toast_no_email_app), Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Log.e("JugglingTrackerApp", "Email recordings failed", e)
        Toast.makeText(context, context.getString(R.string.toast_export_failed), Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun RecordingsTable(
    recordings: List<com.juggling.tracker.data.RecordingRepository.RecordingSummary>,
) {
    if (recordings.isEmpty()) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.recordings_table_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.recordings_col_when),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(2.0f),
                )
                Text(
                    text = stringResource(R.string.recordings_col_source),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1.6f),
                )
                Text(
                    text = stringResource(R.string.recordings_col_balls),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(0.8f),
                )
                Text(
                    text = stringResource(R.string.recordings_col_catches),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1.4f),
                )
                Text(
                    text = stringResource(R.string.recordings_col_duration),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1.0f),
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            recordings.forEach { rec ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        text = if (rec.timestamp > 0L) {
                            recordingTimeFormat.format(java.util.Date(rec.timestamp * 1000L))
                        } else {
                            "-"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(2.0f),
                    )
                    Text(
                        text = if (rec.fromWatch) {
                            stringResource(R.string.recordings_source_watch)
                        } else {
                            stringResource(R.string.recordings_source_phone, rec.sampleRate)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1.6f),
                    )
                    Text(
                        text = rec.balls.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(0.8f),
                    )
                    Text(
                        // actual, with what the detector found alongside it
                        text = "${rec.catches} (${rec.detected})",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1.4f),
                    )
                    Text(
                        text = String.format(java.util.Locale.getDefault(), "%.0fs", rec.durationSeconds),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1.0f),
                    )
                }
            }
        }
    }
}
