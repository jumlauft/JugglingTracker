package com.jugglingtracker.imu.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
            text = if (isJuggling) "JUGGLING..." else "READY",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            color = statusColor
        )
    }
}

@Composable
fun CurrentRunCard(throwCount: Int, previousRunCount: Int? = null) {
    val isJuggling = throwCount > 0
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isJuggling) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                Text(text = "WATCH-HAND CATCHES", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = throwCount.toString(),
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            if (previousRunCount != null) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "PREVIOUS HAND", style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = previousRunCount.toString(),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun ThrowGraph(history: List<FloatArray>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            if (history.isEmpty()) return@Canvas

            val width = size.width
            val height = size.height
            val maxVal = 40f // Max magnitude expected
            val scaleY = height / maxVal
            val maxHistorySize = 100
            val stepX = width / (maxHistorySize - 1)

            for (i in 0 until (history.size - 1)) {
                val current = history[i][3] // Magnitude
                val next = history[i + 1][3]
                val x1 = i * stepX
                val x2 = (i + 1) * stepX
                drawLine(
                    color = lineColor,
                    start = Offset(x1, height - (current * scaleY).coerceIn(0f, height)),
                    end = Offset(x2, height - (next * scaleY).coerceIn(0f, height)),
                    strokeWidth = 2.dp.toPx(),
                )
            }
        }
    }
}
