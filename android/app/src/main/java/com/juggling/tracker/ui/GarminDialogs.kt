package com.juggling.tracker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.juggling.tracker.R

/** The instruction dialogs the watch card opens, for a Garmin and for Wear OS. */
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
fun WearSessionDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_wear_session_title)) },
        text = { Text(stringResource(R.string.dialog_wear_session_text)) },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
fun WearLinkDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_wear_link_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.dialog_wear_link_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(stringResource(R.string.dialog_wear_condition_bluetooth))
                Text(stringResource(R.string.dialog_wear_condition_paired))
                Text(stringResource(R.string.dialog_wear_condition_app))
                Text(stringResource(R.string.dialog_wear_condition_range))
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}
