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
import com.juggling.tracker.data.WatchType
import com.juggling.tracker.logic.GarminConnectionStatus
import com.juggling.tracker.logic.WearConnectionStatus
import com.juggling.tracker.ui.theme.LocalStatusColors

/** What the watch card shows and does in one state. */
private data class WatchCardState(
    val background: androidx.compose.ui.graphics.Color,
    val content: androidx.compose.ui.graphics.Color,
    val title: String,
    val detail: String?,
    val onClick: () -> Unit,
)

/**
 * The two cards at the top of the home screen: the status of the watch picked
 * in Settings, and start-with-phone. Both watches follow the same rules: green
 * when ready (a tap explains how to start a session), red when not (a tap
 * shows how to connect), and inert while connecting or receiving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GarminStatusHeader(
    status: GarminConnectionStatus,
    message: String,
    onPhoneRecordClick: () -> Unit,
    onGarminLinkClick: () -> Unit,
    onGarminSessionClick: () -> Unit,
    watchType: WatchType = WatchType.GARMIN,
    wearStatus: WearConnectionStatus = WearConnectionStatus.CHECKING,
    onWearLinkClick: () -> Unit = {},
    onWearSessionClick: () -> Unit = {},
) {
    val card = when (watchType) {
        WatchType.GARMIN -> garminCardState(status, message, onGarminLinkClick, onGarminSessionClick)
        WatchType.WEAR_OS -> wearCardState(wearStatus, onWearLinkClick, onWearSessionClick)
    }
    WatchHeaderRow(card, onPhoneRecordClick)
}

@Composable
private fun wearCardState(
    status: WearConnectionStatus,
    onLinkClick: () -> Unit,
    onSessionClick: () -> Unit,
): WatchCardState {
    val statusColors = LocalStatusColors.current
    val errorBackground = MaterialTheme.colorScheme.errorContainer
    val errorContent = MaterialTheme.colorScheme.onErrorContainer
    val notConnected = stringResource(R.string.wear_missing)
    return when (status) {
        WearConnectionStatus.READY -> WatchCardState(
            statusColors.successContainer, statusColors.onSuccessContainer,
            stringResource(R.string.wear_ready), null, onSessionClick,
        )
        WearConnectionStatus.RECEIVING -> WatchCardState(
            statusColors.warningContainer, statusColors.onWarningContainer,
            stringResource(R.string.garmin_receiving), null, {},
        )
        WearConnectionStatus.CHECKING -> WatchCardState(
            MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant,
            stringResource(R.string.label_record_with_wear), null, {},
        )
        WearConnectionStatus.NO_WATCH -> WatchCardState(
            errorBackground, errorContent, notConnected, stringResource(R.string.wear_no_watch), onLinkClick,
        )
        WearConnectionStatus.WATCH_APP_MISSING -> WatchCardState(
            errorBackground, errorContent, stringResource(R.string.garmin_watch_app_missing), null, onLinkClick,
        )
        WearConnectionStatus.UNAVAILABLE -> WatchCardState(
            errorBackground, errorContent, notConnected, stringResource(R.string.wear_unavailable), onLinkClick,
        )
    }
}

@Composable
private fun garminCardState(
    status: GarminConnectionStatus,
    message: String,
    onGarminLinkClick: () -> Unit,
    onGarminSessionClick: () -> Unit,
): WatchCardState {
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

    val onClick: () -> Unit = {
        when (status) {
            GarminConnectionStatus.READY -> onGarminSessionClick()
            GarminConnectionStatus.RECEIVING,
            GarminConnectionStatus.NOT_INITIALIZED -> {}
            else -> onGarminLinkClick() // Show checklist for any error/disconnected state
        }
    }
    val title = if (status == GarminConnectionStatus.READY ||
        status == GarminConnectionStatus.RECEIVING ||
        status == GarminConnectionStatus.WATCH_APP_MISSING
    ) {
        statusText
    } else if (status == GarminConnectionStatus.NOT_INITIALIZED) {
        stringResource(R.string.label_record_with_garmin)
    } else {
        stringResource(R.string.garmin_missing)
    }
    val detail = if (status == GarminConnectionStatus.RECEIVING) statusText else null
    return WatchCardState(backgroundColor, textColor, title, detail, onClick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchHeaderRow(card: WatchCardState, onPhoneRecordClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Max)
            .animateContentSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Watch Column
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            onClick = card.onClick,
            colors = CardDefaults.cardColors(containerColor = card.background)
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
                    tint = card.content
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = card.content,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                if (card.detail != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = card.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = card.content.copy(alpha = 0.8f),
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
