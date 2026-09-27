package com.juggling.tracker.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.juggling.tracker.R
import com.juggling.tracker.logic.JugglingViewModel

/** The raw-capture flow: pick a ball count, record, then enter the real catch count. */
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
