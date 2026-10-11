package com.juggling.tracker.ui

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A ball-count chip: the selected one is filled with the primary color and
 * bold contrasting text, the others are outlined and muted, so the choice
 * reads at a glance in light and dark mode. Same look as the iPhone app's
 * BallChipStyle.
 */
@Composable
fun BallChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
        },
        modifier = Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 36.dp),
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = colors.onSurfaceVariant,
            selectedContainerColor = colors.primary,
            selectedLabelColor = colors.onPrimary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = colors.onSurfaceVariant.copy(alpha = 0.5f),
            selectedBorderColor = colors.primary,
            borderWidth = 1.dp,
            selectedBorderWidth = 2.dp,
        ),
    )
}
