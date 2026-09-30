package com.juggling.tracker.wear.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalView
import androidx.wear.compose.material.MaterialTheme
import com.juggling.tracker.wear.logic.AppNavigator
import com.juggling.tracker.wear.logic.Screen
import kotlinx.coroutines.delay

/**
 * The whole watch UI. The four Garmin buttons arrive here and go to
 * [AppNavigator]:
 * - UP / DOWN: the rotary crown or bezel, or the on-screen arrows;
 * - START: the on-screen button, or a hardware stem button;
 * - BACK: a swipe from the left edge, or the back key.
 */
@Composable
fun WearApp(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val screen by navigator.screen.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val rotaryAccumulated = remember { floatArrayOf(0f) }

    // The system back gesture or key. Always ours: on the tracking screens
    // BACK must reach the session, never close the app by default.
    BackHandler { navigator.onBack() }

    MaterialTheme {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
                .edgeSwipeToBack { navigator.onBack() }
                .onRotaryScrollEvent { event ->
                    val acc = rotaryAccumulated[0] + event.verticalScrollPixels
                    rotaryAccumulated[0] = when {
                        acc >= ROTARY_STEP_PX -> {
                            navigator.onUp()
                            0f
                        }
                        acc <= -ROTARY_STEP_PX -> {
                            navigator.onDown()
                            0f
                        }
                        else -> acc
                    }
                    true
                }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                    when (event.key) {
                        Key.StemPrimary, Key.Stem1, Key.Enter, Key.DirectionCenter -> navigator.onStart()
                        Key.DirectionUp -> navigator.onUp()
                        Key.DirectionDown -> navigator.onDown()
                        else -> return@onKeyEvent false
                    }
                    true
                }
                .focusRequester(focusRequester)
                .focusable(),
        ) {
            when (val s = screen) {
                is Screen.ModeSelect -> ModeSelectScreen(
                    isRecordMode = s.isRecordMode,
                    onToggle = navigator::onUp,
                    onStart = navigator::onStart,
                )
                is Screen.BallSelect -> BallSelectScreen(
                    ballCount = s.ballCount,
                    onUp = navigator::onUp,
                    onDown = navigator::onDown,
                    onStart = navigator::onStart,
                )
                is Screen.Tracker -> {
                    val state by s.session.state.collectAsState()
                    KeepScreenOn()
                    LaunchedEffect(s.session) {
                        while (true) {
                            delay(1_000)
                            s.session.tick()
                        }
                    }
                    val menu = state.menu
                    if (menu != null) {
                        MenuScreen(menu, onSelect = s.session::onMenuSelect)
                    } else {
                        TrackerScreen(state, onStartStop = navigator::onStart)
                    }
                }
                is Screen.Recording -> {
                    val state by s.session.state.collectAsState()
                    KeepScreenOn()
                    val menu = state.menu
                    if (menu != null) {
                        MenuScreen(menu, onSelect = s.session::onMenuSelect)
                    } else {
                        RecordingScreen(
                            state,
                            onStart = navigator::onStart,
                            onUp = navigator::onUp,
                            onDown = navigator::onDown,
                        )
                    }
                }
            }
        }
    }

    // Rotary and key events go to the focused node, so keep the root focused.
    LaunchedEffect(screen) { focusRequester.requestFocus() }
}

private const val ROTARY_STEP_PX = 48f

/** Keeps the display on while a session is open, so the sensor keeps running. */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * Wear OS's back gesture, a swipe to the right starting near the left edge.
 * The theme turns the system's own swipe-to-dismiss off (it would close the
 * activity and lose the session), so the app recognises the gesture itself.
 */
private fun Modifier.edgeSwipeToBack(onBack: () -> Unit): Modifier = pointerInput(Unit) {
    var startX = 0f
    var dragX = 0f
    detectHorizontalDragGestures(
        onDragStart = { offset ->
            startX = offset.x
            dragX = 0f
        },
        onDragEnd = {
            if (startX < size.width * EDGE_FRACTION && dragX > size.width * SWIPE_FRACTION) {
                onBack()
            }
        },
        onHorizontalDrag = { change, amount ->
            dragX += amount
            change.consume()
        },
    )
}

private const val EDGE_FRACTION = 0.35f
private const val SWIPE_FRACTION = 0.25f
