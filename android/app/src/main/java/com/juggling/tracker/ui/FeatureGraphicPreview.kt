package com.juggling.tracker.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juggling.tracker.R

@Preview(widthDp = 1024, heightDp = 500)
@Composable
fun FeatureGraphicPreview() {
    val brandColor = Color(0xFFF59E0B)
    Box(
        modifier = Modifier
            .size(width = 1024.dp, height = 500.dp)
            .background(Color.Black)
            .padding(horizontal = 80.dp)
    ) {
        // Text on the left, ensuring no overlap with the graphic on the right
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(0.6f)
        ) {
            Text(
                text = "Juggling Tracker",
                color = brandColor,
                fontSize = 80.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1).sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Track your catches\nAccelerate your progress",
                color = brandColor.copy(alpha = 0.9f),
                fontSize = 36.sp,
                lineHeight = 44.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // Icon/Graphic on the right
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier
                .size(450.dp)
                .align(Alignment.CenterEnd)
                .offset(x = 40.dp), // Slightly offset to the right to clear space
            contentScale = ContentScale.Fit
        )
    }
}
