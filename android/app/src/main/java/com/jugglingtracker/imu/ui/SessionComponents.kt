package com.jugglingtracker.imu.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jugglingtracker.imu.model.SessionSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SessionHistoryGraph(sessions: List<SessionSummary>, modifier: Modifier = Modifier) {
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    val displaySessions = remember(sessions) { sessions.reversed() }
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }

    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if ((selectedIndex != null) && (selectedIndex!! < displaySessions.size)) {
                    val s = displaySessions[selectedIndex!!]
                    val dateStr = dateFormat.format(Date(s.timestamp))
                    Text(
                        text = "$dateStr | Average: %.1f | Best: %d".format(s.avgThrows, s.bestRun),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    Text(text = "Watch-Hand Trends", style = MaterialTheme.typography.labelSmall)
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF4CAF50)))
                    Text(" Best ", fontSize = 10.sp, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF2196F3)))
                    Text(" Average", fontSize = 10.sp, style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val leftPadding = 45.dp
                val bottomPadding = 4.dp
                
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = leftPadding, bottom = bottomPadding)
                        .pointerInput(displaySessions) {
                            detectTapGestures(
                                onPress = { 
                                    selectedIndex = null 
                                },
                            ) { offset ->
                                if (displaySessions.isNotEmpty()) {
                                    val count = displaySessions.size
                                    val stepX = if (count > 1) size.width / (count - 1) else size.width
                                    selectedIndex = (offset.x / stepX).toInt().coerceIn(0, count - 1)
                                }
                            }
                        }
                        .pointerInput(displaySessions) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    if (displaySessions.isNotEmpty()) {
                                        val count = displaySessions.size
                                        val stepX = if (count > 1) size.width / (count - 1) else size.width
                                        selectedIndex = (offset.x / stepX).toInt().coerceIn(0, count - 1)
                                    }
                                },
                                onDragEnd = { selectedIndex = null },
                                onDragCancel = { selectedIndex = null },
                            ) { change, _ ->
                                if (displaySessions.isNotEmpty()) {
                                    val count = displaySessions.size
                                    val stepX = if (count > 1) size.width / (count - 1) else size.width
                                    selectedIndex = (change.position.x / stepX).toInt().coerceIn(0, count - 1)
                                }
                            }
                        }
                ) {
                    if (displaySessions.isEmpty()) return@Canvas

                    val count = displaySessions.size
                    val width = size.width
                    val height = size.height
                    
                    val globalMax = (sessions.maxOfOrNull { it.bestRun } ?: 10).toFloat().coerceAtLeast(10f)
                    val scaleY = height / (globalMax * 1.2f)
                    val stepX = if (count > 1) width / (count - 1) else width / 2f
                    val startX = if (count > 1) 0f else width / 2f

                    // Draw Y-axis labels and title
                    val paint = android.graphics.Paint().apply {
                        color = Color.Gray.toArgb()
                        textSize = 10.sp.toPx()
                        textAlign = android.graphics.Paint.Align.RIGHT
                    }
                    
                    val numLabels = 5
                    for (i in 0 until numLabels) {
                        val value = (globalMax / (numLabels - 1) * i).toInt()
                        val labelY = height - (value * scaleY)
                        drawContext.canvas.nativeCanvas.drawText(
                            value.toString(),
                            -8.dp.toPx(),
                            labelY + 4.dp.toPx(),
                            paint
                        )
                    }

                    // Draw Y-axis title "# of catches"
                    drawContext.canvas.nativeCanvas.save()
                    drawContext.canvas.nativeCanvas.rotate(-90f, -32.dp.toPx(), height / 2)
                    paint.textAlign = android.graphics.Paint.Align.CENTER
                    drawContext.canvas.nativeCanvas.drawText(
                        "# of catches",
                        -32.dp.toPx(),
                        height / 2,
                        paint
                    )
                    drawContext.canvas.nativeCanvas.restore()

                    // Draw shaded area for standard deviation
                    if (count > 1) {
                        val stdDevPath = Path().apply {
                            for (i in 0 until count) {
                                val x = startX + (i * stepX)
                                val y = height - ((displaySessions[i].avgThrows + displaySessions[i].stdDevThrows).toFloat() * scaleY)
                                if (i == 0) moveTo(x, y) else lineTo(x, y)
                            }
                            for (i in count - 1 downTo 0) {
                                val x = startX + (i * stepX)
                                val valY = (displaySessions[i].avgThrows - displaySessions[i].stdDevThrows).coerceAtLeast(0.0).toFloat()
                                val y = height - (valY * scaleY)
                                lineTo(x, y)
                            }
                            close()
                        }
                        drawPath(
                            path = stdDevPath,
                            color = Color(0xFF2196F3).copy(alpha = 0.2f),
                        )
                    }

                    // Draw selection line
                    selectedIndex?.let { idx ->
                        val x = startX + idx * stepX
                        drawLine(
                            color = Color.Gray.copy(alpha = 0.5f),
                            start = Offset(x, 0f),
                            end = Offset(x, height),
                            strokeWidth = 1.dp.toPx()
                        )
                    }

                    for (i in 0 until count) {
                        val x1 = startX + i * stepX
                        val best1 = displaySessions[i].bestRun.toFloat()
                        val avg1 = displaySessions[i].avgThrows.toFloat()

                        drawCircle(
                            color = if (selectedIndex == i) Color(0xFF4CAF50) else Color(0xFF4CAF50).copy(alpha = 0.5f),
                            radius = (if (selectedIndex == i) 5.dp else 3.dp).toPx(),
                            center = Offset(x1, height - best1 * scaleY)
                        )
                        drawCircle(
                            color = if (selectedIndex == i) Color(0xFF2196F3) else Color(0xFF2196F3).copy(alpha = 0.5f),
                            radius = (if (selectedIndex == i) 5.dp else 3.dp).toPx(),
                            center = Offset(x1, height - avg1 * scaleY)
                        )

                        if (i < count - 1) {
                            val x2 = startX + (i + 1) * stepX
                            val best2 = displaySessions[i+1].bestRun.toFloat()
                            val avg2 = displaySessions[i+1].avgThrows.toFloat()

                            drawLine(
                                color = Color(0xFF4CAF50).copy(alpha = 0.3f),
                                start = Offset(x1, height - best1 * scaleY),
                                end = Offset(x2, height - best2 * scaleY),
                                strokeWidth = 2.dp.toPx()
                            )
                            drawLine(
                                color = Color(0xFF2196F3).copy(alpha = 0.3f),
                                start = Offset(x1, height - avg1 * scaleY),
                                end = Offset(x2, height - avg2 * scaleY),
                                strokeWidth = 2.dp.toPx()
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionHistoryItem(session: SessionSummary, onClick: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }
    val dateString = remember(session.timestamp) { dateFormat.format(Date(session.timestamp)) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = dateString, fontWeight = FontWeight.Bold)
                Text(
                    text = "${session.ballCount} balls • ${session.runCount} runs • ${session.totalThrows} catches",
                    fontSize = 12.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatItem("Average", "%.1f (±%.1f)".format(session.avgThrows, session.stdDevThrows))
                StatItem("Best", session.bestRun.toString())
            }
        }
    }
}

@Composable
fun SessionDetailsDialog(session: SessionSummary, onDismiss: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("MMMM dd, HH:mm", Locale.getDefault()) }
    val dateString = remember(session.timestamp) { dateFormat.format(Date(session.timestamp)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(text = "Session Details", style = MaterialTheme.typography.headlineSmall)
                Text(text = dateString, style = MaterialTheme.typography.bodyMedium)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "${session.ballCount} Balls • ${session.runCount} Runs",
                    style = MaterialTheme.typography.titleMedium,
                )
                
                SessionRunsBarPlot(
                    runs = session.runHistory,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    StatItem("Average", "%.1f".format(session.avgThrows))
                    StatItem("Best", session.bestRun.toString())
                    StatItem("Total", session.totalThrows.toString())
                }

                if (session.runDurationsMillis.isNotEmpty()) {
                    val totalDurationMillis = session.runDurationsMillis.sum()
                    val avgFreq = if (totalDurationMillis > 0) {
                        session.totalThrows.toDouble() / (totalDurationMillis / 1000.0)
                    } else 0.0
                    val avgGap = if (session.totalThrows > 0) {
                        (totalDurationMillis / 1000.0) / session.totalThrows
                    } else 0.0

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        StatItem("Average Frequency", "%.2f/s".format(avgFreq))
                        StatItem("Average Gap", "%.2fs".format(avgGap))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun SessionRunsBarPlot(runs: List<Int>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (runs.isEmpty()) return@Canvas

        val width = size.width
        val height = size.height
        val count = runs.size
        val maxVal = (runs.maxOfOrNull { it } ?: 1).toFloat().coerceAtLeast(10f)
        val spacing = 4.dp.toPx()
        val barWidth = (width - (count - 1) * spacing) / count

        runs.forEachIndexed { index, value ->
            val barHeight = (value.toFloat() / maxVal) * height
            val x = index * (barWidth + spacing)
            val y = height - barHeight
            
            drawRect(
                color = Color(0xFF2196F3),
                topLeft = Offset(x, y),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
            )
        }
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelSmall)
        Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}
