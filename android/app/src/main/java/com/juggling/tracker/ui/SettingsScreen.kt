package com.juggling.tracker.ui

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.juggling.tracker.R
import com.juggling.tracker.data.WatchType
import com.juggling.tracker.data.RecordingRepository
import com.juggling.tracker.logic.JugglingViewModel
import java.util.*

/** Settings, plus the recordings table and the export it offers. */
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

    var editingJuggler by remember { mutableStateOf(false) }
    if (editingJuggler) {
        JugglerDialog(
            initialName = viewModel.jugglerName,
            initialHand = viewModel.watchHand,
            initialFirstThrow = viewModel.firstThrowHand,
            message = null,
            confirmLabel = stringResource(R.string.action_save),
            onConfirm = { name, hand, firstThrow ->
                viewModel.setExportDetails(name, hand, firstThrow)
                editingJuggler = false
            },
            onDismiss = { editingJuggler = false },
        )
    }

    // An export waiting on who juggled the runs saved without a juggler, asked for first.
    var pendingExport by remember { mutableStateOf<(() -> Unit)?>(null) }
    pendingExport?.let { export ->
        val untagged = viewModel.recordingsWithoutJuggler
        JugglerDialog(
            initialName = viewModel.jugglerName,
            initialHand = viewModel.watchHand,
            initialFirstThrow = viewModel.firstThrowHand,
            message = pluralStringResource(R.plurals.dialog_export_untagged, untagged, untagged),
            confirmLabel = stringResource(R.string.action_continue_export),
            onConfirm = { name, hand, firstThrow ->
                viewModel.setExportDetails(name, hand, firstThrow)
                pendingExport = null
                export()
            },
            onDismiss = { pendingExport = null },
        )
    }

    // Ask who juggled only when some runs were saved without a juggler.
    fun exportAfterAsking(export: () -> Unit) {
        if (viewModel.recordingsWithoutJuggler > 0) pendingExport = export else export()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(all = 16.dp)
            .verticalScroll(state = rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(space = 16.dp),
    ) {
        // Watch
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            WatchTypeDropdown(
                selected = viewModel.watchType,
                onSelect = { viewModel.setWatchType(it) },
            )
            Text(
                text = stringResource(R.string.desc_watch),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }

        HorizontalDivider()

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

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            JugglerCard(viewModel.currentJuggler) { editingJuggler = true }
            Text(
                text = stringResource(R.string.desc_juggler),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }

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
                    exportAfterAsking {
                        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                        recordingLauncher.launch("juggling_recordings_$ts.zip")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text(stringResource(R.string.action_export_recordings))
            }

            Button(
                onClick = { exportAfterAsking { emailRecordings(context, viewModel) } },
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

/** Garmin or Wear OS, as a compact drop-down. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchTypeDropdown(selected: WatchType, onSelect: (WatchType) -> Unit) {
    val options = listOf(
        WatchType.GARMIN to stringResource(R.string.watch_type_garmin),
        WatchType.WEAR_OS to stringResource(R.string.watch_type_wear_os),
    )
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = options.first { it.first == selected }.second,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.section_watch)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (type, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(type)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/** Who is juggling, shown as one line; tapping it opens the [JugglerDialog]. */
@Composable
private fun JugglerCard(juggler: RecordingRepository.Juggler?, onClick: () -> Unit) {
    val left = stringResource(R.string.hand_left_lower)
    val right = stringResource(R.string.hand_right_lower)
    fun handName(hand: String) = if (hand == RecordingRepository.HAND_LEFT) left else right
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(text = stringResource(R.string.label_juggler), style = MaterialTheme.typography.labelMedium)
            Text(
                text = if (juggler != null) {
                    stringResource(
                        R.string.juggler_summary,
                        juggler.name,
                        handName(juggler.hand),
                        handName(juggler.firstThrow),
                    )
                } else {
                    stringResource(R.string.juggler_not_set)
                },
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/**
 * Asks who juggles, which wrist wears the watch and which hand makes the first
 * throw, prefilled with the previous answers. New recordings are saved with
 * them, and an export uses them for older runs saved without a juggler.
 */
@Composable
private fun JugglerDialog(
    initialName: String,
    initialHand: String?,
    initialFirstThrow: String?,
    message: String?,
    confirmLabel: String,
    onConfirm: (name: String, hand: String, firstThrow: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var hand by remember { mutableStateOf(initialHand) }
    var firstThrow by remember { mutableStateOf(initialFirstThrow) }
    val selectedHand = hand
    val selectedFirstThrow = firstThrow
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_export_details_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                message?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.label_juggler_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                HandChoice(stringResource(R.string.label_watch_hand), hand) { hand = it }
                HandChoice(stringResource(R.string.label_first_throw_hand), firstThrow) { firstThrow = it }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (selectedHand != null && selectedFirstThrow != null) {
                        onConfirm(name.trim(), selectedHand, selectedFirstThrow)
                    }
                },
                enabled = name.isNotBlank() && selectedHand != null && selectedFirstThrow != null,
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** A question answered with Left or Right. */
@Composable
private fun HandChoice(question: String, selected: String?, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = question, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                RecordingRepository.HAND_LEFT to stringResource(R.string.watch_hand_left),
                RecordingRepository.HAND_RIGHT to stringResource(R.string.watch_hand_right),
            ).forEach { (value, label) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(label) },
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
