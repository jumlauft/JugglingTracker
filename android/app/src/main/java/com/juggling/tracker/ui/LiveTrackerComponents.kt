package com.juggling.tracker.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.juggling.tracker.R

@Composable
fun StatusIndicator(isJuggling: Boolean) {
    val statusColor by animateColorAsState(
        if (isJuggling) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        label = "statusColor",
    )

    Surface(
        color = statusColor.copy(alpha = 0.1f),
        shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(width = 1.dp, color = statusColor),
    ) {
        Text(
            text = if (isJuggling) stringResource(R.string.label_juggling) else stringResource(R.string.label_ready),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            color = statusColor
        )
    }
}
