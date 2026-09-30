package com.juggling.tracker.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.Text
import com.juggling.tracker.wear.logic.Format
import com.juggling.tracker.wear.logic.MenuSpec
import com.juggling.tracker.wear.logic.RecordingPhase
import com.juggling.tracker.wear.logic.RecordingSession
import com.juggling.tracker.wear.logic.RecordingUiState
import com.juggling.tracker.wear.logic.TrackerUiState

/** The Garmin palette the watch app draws with. */
object WatchColors {
    val Green = Color(0xFF00FF00)
    val Yellow = Color(0xFFFFFF00)
    val Red = Color(0xFFFF0000)
    val LightGray = Color(0xFFAAAAAA)
    val White = Color.White
}

/** Test tags, shared by the Robolectric and the emulator tests. */
object Tags {
    const val START = "start_button"
    const val UP = "up_button"
    const val DOWN = "down_button"
    const val MODE = "mode_text"
    const val BALLS = "ball_count"
    const val RUN_STATE = "run_state"
    const val COUNT = "current_count"
    const val PREV = "prev"
    const val RUNS = "runs"
    const val AVG = "avg"
    const val MAX = "max"
    const val TIME = "time"
    const val SHAPE = "shape"
    const val ERROR = "error_banner"
    const val SYNC = "sync_status"
    const val MENU_TITLE = "menu_title"
    const val REC_STATUS = "rec_status"
    const val LABEL = "label_count"

    fun menuItem(id: String) = "menu_item_$id"
}

@Composable
private fun WatchText(
    text: String,
    color: Color,
    size: TextUnit,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
) {
    Text(
        text = text,
        color = color,
        fontSize = size,
        fontWeight = weight,
        textAlign = TextAlign.Center,
        // Every line on these screens is written to fit on one line of the
        // smallest round watch; letting it wrap broke words mid-screen.
        maxLines = 1,
        softWrap = false,
        modifier = modifier,
    )
}

/**
 * A screen's text, centred, with an optional button under it. The button is
 * measured first, so on a small watch the text gives way and the button is
 * never squeezed off the screen.
 */
@Composable
private fun CenteredColumn(button: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
        if (button != null) {
            Spacer(Modifier.height(2.dp))
            button()
        }
    }
}

/** UP and DOWN either side of the value they change, like a stepper. */
@Composable
private fun Stepper(onDown: () -> Unit, onUp: () -> Unit, value: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ArrowButton("▼", Tags.DOWN, onDown)
        Box(Modifier.padding(horizontal = 4.dp)) { value() }
        ArrowButton("▲", Tags.UP, onUp)
    }
}

@Composable
private fun ArrowButton(symbol: String, tag: String, onClick: () -> Unit) {
    CompactButton(
        onClick = onClick,
        colors = ButtonDefaults.secondaryButtonColors(),
        // A 40 dp tap target rather than 48, so a stepper row fits across
        // the middle of a 192 dp watch.
        backgroundPadding = 4.dp,
        modifier = Modifier.testTag(tag),
    ) { Text(symbol, fontSize = 12.sp) }
}

/**
 * START/STOP. A pill-shaped chip rather than a round button: a round
 * CompactButton is 32 dp wide, which broke "Start" and "Confirm" into
 * fragments across lines.
 */
@Composable
private fun StartButton(label: String, onClick: () -> Unit) {
    CompactChip(
        onClick = onClick,
        label = {
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        },
        colors = ChipDefaults.primaryChipColors(backgroundColor = WatchColors.Green, contentColor = Color.Black),
        modifier = Modifier.testTag(Tags.START),
    )
}

// ── Start-up screens ──────────────────────────────────────────────────

/** `ModeSelectView.mc`: Juggle or Record. */
@Composable
fun ModeSelectScreen(isRecordMode: Boolean, onToggle: () -> Unit, onStart: () -> Unit) {
    CenteredColumn(button = { StartButton("Start", onStart) }) {
        WatchText("Mode", WatchColors.Green, 14.sp)
        Stepper(onDown = onToggle, onUp = onToggle) {
            WatchText(
                if (isRecordMode) "Record" else "Juggle",
                WatchColors.White,
                18.sp,
                Modifier.testTag(Tags.MODE),
                FontWeight.Bold,
            )
        }
        WatchText(if (isRecordMode) "Save raw sensor data" else "Track catches live", WatchColors.LightGray, 11.sp)
    }
}

/** `BallSelectView.mc`: 3 to 9 balls. */
@Composable
fun BallSelectScreen(ballCount: Int, onUp: () -> Unit, onDown: () -> Unit, onStart: () -> Unit) {
    CenteredColumn(button = { StartButton("Start", onStart) }) {
        WatchText("Balls", WatchColors.Green, 14.sp)
        Stepper(onDown = onDown, onUp = onUp) {
            WatchText(ballCount.toString(), WatchColors.Green, 40.sp, Modifier.testTag(Tags.BALLS), FontWeight.Bold)
        }
    }
}

// ── Juggle ────────────────────────────────────────────────────────────

/** `MainView.mc`: run state, live count and session stats (JUG-1). */
@Composable
fun TrackerScreen(state: TrackerUiState, onStartStop: () -> Unit) {
    if (state.sending) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            WatchText(Format.syncing(state.syncDots), WatchColors.Yellow, 18.sp, Modifier.testTag(Tags.SYNC))
        }
        return
    }
    CenteredColumn(button = { StartButton("End", onStartStop) }) {
        WatchText(
            if (state.runActive) "RUN ACTIVE" else "WAITING",
            if (state.runActive) WatchColors.Green else WatchColors.Yellow,
            11.sp,
            Modifier.testTag(Tags.RUN_STATE),
        )
        WatchText("Catches in watch hand", WatchColors.Green, 12.sp)
        WatchText(
            state.currentCount.toString(),
            WatchColors.Green,
            46.sp,
            Modifier.testTag(Tags.COUNT),
            FontWeight.Bold,
        )
        StatRow(
            "Prev: ${Format.countOrDash(state.previousCount)}" to Tags.PREV,
            "Runs: ${state.runs}" to Tags.RUNS,
        )
        StatRow(
            "Avg: ${Format.averageOrDash(state.average)}" to Tags.AVG,
            "Max: ${Format.countOrDash(state.max)}" to Tags.MAX,
        )
        WatchText("Time: ${Format.elapsed(state.elapsedSeconds)}", WatchColors.LightGray, 11.sp, Modifier.testTag(Tags.TIME))
        WatchText("Shape: ${Format.percentOrDash(state.shapeConsistency)}", WatchColors.LightGray, 11.sp, Modifier.testTag(Tags.SHAPE))
        state.errorMessage?.let {
            WatchText(it, WatchColors.Red, 11.sp, Modifier.testTag(Tags.ERROR))
        }
    }
}

@Composable
private fun StatRow(left: Pair<String, String>, right: Pair<String, String>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        WatchText(left.first, WatchColors.LightGray, 11.sp, Modifier.testTag(left.second))
        WatchText(right.first, WatchColors.LightGray, 11.sp, Modifier.testTag(right.second))
    }
}

// ── Record ────────────────────────────────────────────────────────────

/** `RecordingView.mc`: idle, recording, labelling and syncing. */
@Composable
fun RecordingScreen(state: RecordingUiState, onStart: () -> Unit, onUp: () -> Unit, onDown: () -> Unit) {
    val button = when (state.phase) {
        RecordingPhase.IDLE -> "Start"
        RecordingPhase.RECORDING -> "Stop"
        RecordingPhase.LABELING -> "Confirm"
        RecordingPhase.SYNCING -> null
    }
    CenteredColumn(button = button?.let { label -> @Composable { StartButton(label, onStart) } }) {
        when (state.phase) {
            RecordingPhase.IDLE -> {
                WatchText("Ready to record", WatchColors.Yellow, 13.sp, Modifier.testTag(Tags.REC_STATUS))
                WatchText("Press Start", WatchColors.Green, 20.sp)
                if (state.runsCompleted > 0) {
                    WatchText("Runs: ${state.runsCompleted}", WatchColors.LightGray, 11.sp)
                }
            }
            RecordingPhase.RECORDING -> {
                WatchText("● REC", WatchColors.Red, 13.sp, Modifier.testTag(Tags.REC_STATUS))
                WatchText("${state.recordedSamples / RecordingSession.SAMPLE_RATE}s", WatchColors.White, 24.sp)
                WatchText("Start stops | Hand: ${state.liveCount}", WatchColors.LightGray, 11.sp)
            }
            RecordingPhase.LABELING -> {
                WatchText(
                    "Auto-detected ${state.detectedCount}",
                    WatchColors.Yellow,
                    11.sp,
                    Modifier.testTag(Tags.REC_STATUS),
                )
                WatchText("catches, watch hand", WatchColors.Yellow, 11.sp)
                WatchText("Actual:", WatchColors.LightGray, 11.sp)
                Stepper(onDown = onDown, onUp = onUp) {
                    WatchText(
                        state.labelCount.toString(),
                        WatchColors.Green,
                        30.sp,
                        Modifier.testTag(Tags.LABEL),
                        FontWeight.Bold,
                    )
                }
                WatchText("Swipe back = discard", WatchColors.LightGray, 10.sp)
            }
            RecordingPhase.SYNCING -> {
                WatchText(Format.recordingSyncText(state), WatchColors.Yellow, 18.sp, Modifier.testTag(Tags.SYNC))
                state.errorMessage?.let { WatchText(it, WatchColors.Red, 11.sp, Modifier.testTag(Tags.ERROR)) }
                if (state.errorMessage != null) {
                    state.failReason?.let { WatchText(it, WatchColors.Red, 11.sp) }
                    WatchText(if (state.dataPending) "data: pending" else "data: sent", WatchColors.Red, 11.sp)
                }
            }
        }
    }
}

// ── Menus ─────────────────────────────────────────────────────────────

/** A Garmin `Menu2`: a title and a short list of choices. */
@Composable
fun MenuScreen(menu: MenuSpec, onSelect: (String) -> Unit) {
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            ListHeader(modifier = Modifier.testTag(Tags.MENU_TITLE)) {
                Text(menu.title, textAlign = TextAlign.Center, color = WatchColors.White, maxLines = 1, softWrap = false)
            }
        }
        items(menu.items) { item ->
            val sub = item.subLabel
            Chip(
                onClick = { onSelect(item.id) },
                label = { Text(item.label, maxLines = 1, softWrap = false) },
                secondaryLabel = if (sub != null) {
                    { Text(sub, maxLines = 1, softWrap = false) }
                } else {
                    null
                },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().testTag(Tags.menuItem(item.id)),
            )
        }
    }
}
