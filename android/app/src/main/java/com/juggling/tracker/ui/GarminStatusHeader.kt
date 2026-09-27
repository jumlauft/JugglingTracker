package com.juggling.tracker.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.juggling.tracker.R
import com.juggling.tracker.logic.GarminConnectionStatus
import com.juggling.tracker.ui.theme.LocalStatusColors

/** The two cards at the top of the home screen: watch status, and start-with-phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GarminStatusHeader(
    status: GarminConnectionStatus,
    message: String,
    onPhoneRecordClick: () -> Unit,
    onGarminLinkClick: () -> Unit,
    onGarminSessionClick: () -> Unit,
) {
    // Every state's colours come from the theme: the failure states from
    // Material's own error container, "ready" and "receiving" from
    // LocalStatusColors, which has no Material token. These used to be literal
    // light-mode tints, so in dark mode the card stayed pale while the rest of
    // the app went dark -- and only NOT_INITIALIZED, already on theme colours,
    // followed along.
    val statusColors = LocalStatusColors.current
    val (backgroundColor, textColor, statusText) = when (status) {
        GarminConnectionStatus.READY -> Triple(
            statusColors.successContainer,
            statusColors.onSuccessContainer,
            stringResource(R.string.garmin_ready)
        )
        GarminConnectionStatus.RECEIVING -> Triple(
            statusColors.warningContainer,
            statusColors.onWarningContainer,
            stringResource(R.string.garmin_receiving)
        )
        GarminConnectionStatus.CONNECT_IQ_MISSING -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            stringResource(R.string.garmin_missing)
        )
        GarminConnectionStatus.WATCH_APP_MISSING -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            stringResource(R.string.garmin_watch_app_missing)
        )
        GarminConnectionStatus.DISCONNECTED -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            stringResource(R.string.garmin_disconnected)
        )
        GarminConnectionStatus.BLUETOOTH_DISABLED,
        GarminConnectionStatus.NO_PAIRED_DEVICES,
        GarminConnectionStatus.SDK_ERROR -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
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
