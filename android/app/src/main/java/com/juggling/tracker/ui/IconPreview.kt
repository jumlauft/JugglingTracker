package com.juggling.tracker.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.juggling.tracker.R

@Preview
@Composable
fun AppIconPreview() {
    IconBox(size = 120.dp, iconSize = 80.dp)
}

@Preview(widthDp = 512, heightDp = 512)
@Composable
fun PlayStoreIconPreview() {
    IconBox(size = 512.dp, iconSize = 340.dp)
}

@Composable
private fun IconBox(size: androidx.compose.ui.unit.Dp, iconSize: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(Color.Black), // Play Store icons are square, Adaptive icons handle the mask
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = "App Icon",
            modifier = Modifier.size(iconSize)
        )
    }
}
