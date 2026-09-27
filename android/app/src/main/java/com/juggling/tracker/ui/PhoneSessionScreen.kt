package com.juggling.tracker.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.juggling.tracker.R
import com.juggling.tracker.logic.JugglingViewModel

/** The live phone session screen and the cards it is built from. */
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
