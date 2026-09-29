package com.juggling.tracker.wear

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.juggling.tracker.wear.logic.AccelSample
import com.juggling.tracker.wear.logic.PhoneLink
import com.juggling.tracker.wear.logic.WatchEffects
import java.io.FileInputStream

/** A phone that records what it is sent and answers when told to. */
class RecordingPhone : PhoneLink {
    val sent = mutableListOf<Map<String, Any>>()
    private var listener: ((Map<String, Any?>) -> Unit)? = null
    var deliver = true

    override fun send(payload: Map<String, Any>, onResult: (delivered: Boolean) -> Unit) {
        sent += payload
        onResult(deliver)
    }

    override fun setMessageListener(listener: ((Map<String, Any?>) -> Unit)?) {
        this.listener = listener
    }

    fun ack(timestamp: Long) {
        listener?.invoke(mapOf("type" to "ack", "timestamp" to timestamp))
    }
}

class CountingEffects : WatchEffects {
    @Volatile var vibrations = 0
    @Volatile var exits = 0
    override fun vibrate() {
        vibrations += 1
    }

    override fun exit() {
        exits += 1
    }
}

object Emulator {
    private const val SCREEN_DIR = "/data/local/tmp/wear-screens"

    /**
     * Saves what the emulator display shows right now. The shell user owns
     * the directory, so the files survive the test APK being uninstalled and
     * CI can pull them afterwards.
     */
    fun screenshot(name: String) {
        shell("mkdir -p $SCREEN_DIR")
        shell("screencap -p $SCREEN_DIR/$name.png")
    }

    private fun shell(command: String) {
        val pfd: ParcelFileDescriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        // Reading to the end waits for the command to finish.
        FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
        pfd.close()
    }

    /** A labelled recording from connectiq/data, packaged as a test asset. */
    fun recording(runId: String): List<AccelSample> {
        val context = InstrumentationRegistry.getInstrumentation().context
        val lines = context.assets.open("$runId.csv").bufferedReader().readLines()
        return lines
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("x,") }
            .mapIndexed { i, line ->
                val (x, y, z) = line.trim().split(",").take(3).map { it.trim().toInt() }
                AccelSample(x, y, z, i * 40L)
            }
    }
}
