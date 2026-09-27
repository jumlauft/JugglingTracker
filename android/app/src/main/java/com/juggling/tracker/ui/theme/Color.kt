package com.juggling.tracker.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// Status colours for the Garmin header. Material 3 ships errorContainer and
// onErrorContainer, which the header's failure states use, but has no token for
// "good" or "in progress" -- so these follow the same container/on-container
// convention: a muted container with a foreground legible on it, one pair per
// theme. StatusBarIconContrastTest's sibling StatusColorsTest holds every pair
// to WCAG AA, which is why the light warning foreground is this dark: the pale
// orange it replaced sat at 2.8:1.
val SuccessContainerLight = Color(0xFFE8F5E9)
val OnSuccessContainerLight = Color(0xFF2E7D32)
val SuccessContainerDark = Color(0xFF1B3B1F)
val OnSuccessContainerDark = Color(0xFFA5D6A7)

val WarningContainerLight = Color(0xFFFFF3E0)
val OnWarningContainerLight = Color(0xFF7A3E00)
val WarningContainerDark = Color(0xFF402B12)
val OnWarningContainerDark = Color(0xFFFFCC80)
