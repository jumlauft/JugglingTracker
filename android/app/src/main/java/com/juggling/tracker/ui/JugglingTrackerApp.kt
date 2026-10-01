package com.juggling.tracker.ui

import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.juggling.tracker.R
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
    // Saved, so turning the phone or the system recreating the app comes back
    // to the same screen instead of throwing the user back to the start.
    var currentScreen by rememberSaveable { mutableStateOf(Screen.Tracker) }
    var showQuitConfirmation by remember { mutableStateOf(false) }
    var showGarminLinkInstructions by remember { mutableStateOf(false) }
    var showGarminSessionInstructions by remember { mutableStateOf(false) }
    var showWearLinkInstructions by remember { mutableStateOf(false) }
    var showWearSessionInstructions by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (showGarminLinkInstructions) {
        GarminLinkDialog(onDismiss = { showGarminLinkInstructions = false })
    }

    if (showGarminSessionInstructions) {
        GarminSessionDialog(onDismiss = { showGarminSessionInstructions = false })
    }

    if (showWearLinkInstructions) {
        WearLinkDialog(onDismiss = { showWearLinkInstructions = false })
    }

    if (showWearSessionInstructions) {
        WearSessionDialog(onDismiss = { showWearSessionInstructions = false })
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

    // Hold the screen awake while samples are being collected. Without this the
    // display times out mid-session -- juggling never touches the screen -- and
    // onPause tears the sensor listener down, so counting stops with nothing on
    // screen to say so.
    val view = LocalView.current
    val keepScreenOn = viewModel.shouldKeepScreenOn
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
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
                    onWearLinkClick = { showWearLinkInstructions = true },
                    onWearSessionClick = { showWearSessionInstructions = true },
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
