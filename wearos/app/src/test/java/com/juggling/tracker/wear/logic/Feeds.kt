package com.juggling.tracker.wear.logic

import com.juggling.tracker.shared.JugglingDetector

/**
 * Synthetic accelerometer input, the same shapes `connectiq/test/DetectorTest.mc`
 * uses: a still watch with gravity on Z, and catch-like impulses against it.
 * Everything is in milli-g at 25 Hz, as the sensor delivers it.
 */
object Feeds {
    const val MILLI_G_PER_MS2 = 1000.0 / 9.80665
    const val BASELINE_Z = 1000
    const val PERIOD_MS = JugglingDetector.SAMPLE_PERIOD_MS

    fun baseline(startMs: Long, samples: Int): List<AccelSample> =
        (0 until samples).map { AccelSample(0, 0, BASELINE_Z, startMs + it * PERIOD_MS) }

    /** One impulse: three spiked samples, then twelve still ones to commit it. */
    fun burst(startMs: Long, amplitudeMs2: Double = 50.0): List<AccelSample> {
        val spikeZ = BASELINE_Z - (amplitudeMs2 * MILLI_G_PER_MS2).toInt()
        return (0 until 3).map { AccelSample(0, 0, spikeZ, startMs + it * PERIOD_MS) } +
            baseline(startMs + 3 * PERIOD_MS, 12)
    }

    /** `catches` watch-hand catches need 2 * catches - 1 bursts (DET-8). */
    fun catches(startMs: Long, catches: Int): List<AccelSample> {
        val out = mutableListOf<AccelSample>()
        var t = startMs
        repeat(catches * 2 - 1) {
            val b = burst(t)
            out += b
            t = b.last().timeMs + PERIOD_MS
        }
        return out
    }

    fun warmup(): List<AccelSample> = baseline(0, 55)

    fun next(samples: List<AccelSample>): Long = samples.last().timeMs + PERIOD_MS
}

/** Drives a detector the way the Garmin test helpers do, one sample at a time. */
fun JugglingDetector.feed(samples: List<AccelSample>): Long {
    for (s in samples) {
        processSample(s.x, s.y, s.z, s.timeMs)
        checkAutoFinish(s.timeMs)
    }
    return Feeds.next(samples)
}

class FakeScheduler : Scheduler {
    private data class Task(val dueMs: Long, val seq: Int, val action: () -> Unit, var cancelled: Boolean = false)

    var nowMs = 0L
        private set
    private var seq = 0
    private val tasks = mutableListOf<Task>()

    override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
        val task = Task(nowMs + delayMs, seq++, action)
        tasks += task
        return Cancellable { task.cancelled = true }
    }

    /** Runs every task that falls due within [ms], in order. */
    fun advance(ms: Long) {
        val target = nowMs + ms
        while (true) {
            val next = tasks.filter { !it.cancelled && it.dueMs <= target }
                .minWithOrNull(compareBy({ it.dueMs }, { it.seq })) ?: break
            tasks.remove(next)
            nowMs = next.dueMs
            next.action()
        }
        nowMs = target
    }

    val clock = MonotonicClock { nowMs }
}

class FakePhoneLink : PhoneLink {
    val sent = mutableListOf<Map<String, Any>>()
    private val callbacks = mutableListOf<(Boolean) -> Unit>()
    var listener: ((Map<String, Any?>) -> Unit)? = null
        private set

    /** When set, every send reports this result at once. */
    var autoResult: Boolean? = null

    override fun send(payload: Map<String, Any>, onResult: (delivered: Boolean) -> Unit) {
        sent += payload
        val auto = autoResult
        if (auto != null) onResult(auto) else callbacks += onResult
    }

    override fun setMessageListener(listener: ((Map<String, Any?>) -> Unit)?) {
        this.listener = listener
    }

    /** Reports the result of the send at [index] (default: the latest). */
    fun complete(delivered: Boolean, index: Int = callbacks.size - 1) {
        callbacks[index](delivered)
    }

    fun ack(timestamp: Long? = null) {
        val msg = mutableMapOf<String, Any?>("type" to "ack")
        if (timestamp != null) msg["timestamp"] = timestamp
        listener?.invoke(msg)
    }

    val types: List<Any?> get() = sent.map { it["type"] }
}

class FakeEffects : WatchEffects {
    var vibrations = 0
    var exits = 0
    override fun vibrate() {
        vibrations += 1
    }

    override fun exit() {
        exits += 1
    }
}
