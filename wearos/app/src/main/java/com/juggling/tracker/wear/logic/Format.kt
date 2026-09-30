package com.juggling.tracker.wear.logic

import java.util.Locale

/** Display strings, identical to what `MainView.mc` and `RecordingView.mc` draw. */
object Format {
    /** "m:ss", or "h:mm:ss" from one hour on. */
    fun elapsed(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds / 60) % 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "$hours:${twoDigits(minutes)}:${twoDigits(seconds)}"
        } else {
            "$minutes:${twoDigits(seconds)}"
        }
    }

    /** Stats show "-" until there is a value. */
    fun countOrDash(value: Int): String = if (value == 0) "-" else value.toString()

    fun averageOrDash(value: Double): String =
        if (value == 0.0) "-" else String.format(Locale.US, "%.1f", value)

    fun syncing(dots: Int): String = "Sync to phone" + ".".repeat(dots.coerceAtLeast(0))

    /** While chunks are going over, the record screen shows progress instead. */
    fun recordingSyncText(state: RecordingUiState): String =
        if (state.headerSent && !state.transferDone && state.totalChunks > 0) {
            "${state.chunkIndex}/${state.totalChunks}"
        } else {
            syncing(state.syncDots)
        }

    private fun twoDigits(value: Long) = if (value < 10) "0$value" else value.toString()
}
