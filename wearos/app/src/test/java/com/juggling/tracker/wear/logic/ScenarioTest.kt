package com.juggling.tracker.wear.logic

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Replays the shared watch scenarios in `connectiq/scenarios` through the
 * whole Wear OS logic layer: the navigator, both sessions, a fake phone, a
 * fake clock and fake effects. The Apple Watch's `ScenarioTests` replays the
 * same files, so a workflow that drifts on one watch fails here or there.
 * The format is described in `connectiq/scenarios/README.md`.
 */
class ScenarioTest {
    private fun scenarioDir(): File {
        var dir = File("").absoluteFile
        while (dir.parentFile != null && !File(dir, "connectiq/scenarios").isDirectory) {
            dir = dir.parentFile
        }
        return File(dir, "connectiq/scenarios")
    }

    @Test
    fun `every shared watch scenario passes`() {
        val files = scenarioDir().listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertTrue("no scenarios found", files.isNotEmpty())
        val failures = mutableListOf<String>()
        var ran = 0
        for (file in files) {
            val scenario = JSONObject(file.readText())
            val platforms = scenario.optJSONArray("platforms")
            if (platforms != null && (0 until platforms.length()).none { platforms.getString(it) == "wearos" }) {
                continue
            }
            ran += 1
            try {
                ScenarioRun(scenario).run()
            } catch (e: AssertionError) {
                failures += "${file.name}: ${e.message}"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
        assertTrue("no scenario ran", ran > 0)
    }
}

/** One scenario, run against fresh fakes. */
private class ScenarioRun(private val scenario: JSONObject) {
    private val scheduler = FakeScheduler()
    private val link = FakePhoneLink()
    private val effects = FakeEffects()
    private val recorder = FakeActivityRecorder()
    private val store = FakeSessionStore()
    private var epoch = scenario.optLong("epochSeconds", 1_700_000_000L)
    private var t = 0L
    private var navigator = launch()

    /** The app opening on this watch, with its storage and clock. */
    private fun launch() = AppNavigator(
        newTracker = { balls -> tracker(balls, null) },
        newRecording = { balls -> RecordingSession(balls, link, scheduler, effects, epochSeconds = { epoch }) },
        effects = effects,
        resumeTracker = { store.load()?.let { tracker(it.balls, it) } },
    )

    private fun tracker(balls: Int, saved: KeptSession?) = TrackerSession(
        balls, link, scheduler, scheduler.clock, effects, epochSeconds = { epoch },
        recorder = recorder, store = store, saved = saved,
    )

    fun run() {
        val steps = scenario.getJSONArray("steps")
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            try {
                perform(step)
            } catch (e: AssertionError) {
                throw AssertionError("step ${i + 1} $step: ${e.message}")
            }
        }
    }

    private fun feed(samples: List<AccelSample>) {
        // The sensor delivers about one batch a second.
        samples.chunked(25).forEach { batch ->
            when (val s = navigator.screen.value) {
                is Screen.Tracker -> s.session.onSamples(batch)
                is Screen.Recording -> s.session.onSamples(batch)
                else -> Unit
            }
        }
        t = Feeds.next(samples)
    }

    private fun perform(step: JSONObject) {
        val key = step.keys().next()
        when (key) {
            "press" -> when (val button = step.getString("press")) {
                "up" -> navigator.onUp()
                "down" -> navigator.onDown()
                "start" -> navigator.onStart()
                "back" -> navigator.onBack()
                else -> fail("unknown button $button")
            }
            "select" -> when (val s = navigator.screen.value) {
                is Screen.Tracker -> s.session.onMenuSelect(step.getString("select"))
                is Screen.Recording -> s.session.onMenuSelect(step.getString("select"))
                else -> fail("no menu on ${screenName()}")
            }
            "warmup" -> feed(Feeds.baseline(t, 55))
            "catches" -> feed(Feeds.catches(t, step.getInt("catches")))
            "burst" -> repeat(step.getInt("burst")) { feed(Feeds.burst(t)) }
            "idle" -> feed(Feeds.baseline(t, step.getInt("idle")))
            "wait" -> scheduler.advance(step.getLong("wait"))
            "epochAdvance" -> epoch += step.getLong("epochAdvance")
            "deliver" ->
                if (step.has("index")) {
                    link.complete(step.getBoolean("deliver"), step.getInt("index"))
                } else {
                    link.complete(step.getBoolean("deliver"))
                }
            "deliverAll" -> deliverAll()
            // The shared scenarios relaunch only after the app has closed
            // itself, so there is nothing to close here first.
            "relaunch" -> navigator = launch()
            "phone" -> link.listener?.invoke(toMap(step.getJSONObject("phone")))
            "expect" -> expect(step.getJSONObject("expect"))
            else -> fail("unknown step $key")
        }
    }

    /**
     * Lets every message through: this one and all later ones report
     * delivered, and time runs on in small steps until the run's rec_end
     * (or the session) has gone out.
     */
    private fun deliverAll() {
        link.autoResult = true
        link.complete(true)
        var steps = 0
        while (link.sent.lastOrNull()?.get("type") !in setOf("rec_end", "session") && steps < 400) {
            scheduler.advance(50)
            steps += 1
        }
    }

    private fun screenName(): String = when (navigator.screen.value) {
        is Screen.ModeSelect -> "modeSelect"
        is Screen.BallSelect -> "ballSelect"
        is Screen.Tracker -> "tracker"
        is Screen.Recording -> "recording"
    }

    private fun expect(want: JSONObject) {
        val screen = navigator.screen.value
        val tracker = (screen as? Screen.Tracker)?.session?.state?.value
        val recording = (screen as? Screen.Recording)?.session?.state?.value
        val menu = tracker?.menu ?: recording?.menu
        for (key in want.keys()) {
            val value = want.get(key)
            val actual: Any? = when (key) {
                "screen" -> screenName()
                "recordMode" -> (screen as? Screen.ModeSelect)?.isRecordMode
                "mode" -> (screen as? Screen.BallSelect)?.mode?.name?.lowercase()
                "balls" -> when (screen) {
                    is Screen.BallSelect -> screen.ballCount
                    is Screen.Tracker -> tracker!!.ballCount
                    is Screen.Recording -> recording!!.ballCount
                    else -> null
                }
                "menu" -> menu?.kind
                "menuTitle" -> menu?.title
                "menuItems" -> menu?.items?.map { it.id }
                "menuLabels" -> menu?.items?.map { it.label }
                "menuSubLabels" -> menu?.items?.map { it.subLabel }
                "runActive" -> tracker?.runActive
                "count" -> tracker?.currentCount ?: recording?.liveCount
                "previous" -> tracker?.previousCount
                "runs" -> tracker?.runs
                "max" -> tracker?.max
                "sending" -> tracker?.sending
                "phase" -> recording?.phase?.name?.lowercase()
                "runsCompleted" -> recording?.runsCompleted
                "detected" -> recording?.detectedCount
                "label" -> recording?.labelCount
                "recordedSamples" -> recording?.recordedSamples
                "error" -> tracker?.errorMessage ?: recording?.errorMessage
                "exits" -> effects.exits
                "vibrations" -> effects.vibrations
                "sentTypes" -> collapsed(link.types)
                "sentCount" -> link.sent.size
                "stored" -> store.value != null
                "activityState" -> recorder.state
                "activitiesSaved" -> recorder.savedAverages.size
                "activityAverages" -> recorder.savedAverages
                "elapsed" -> tracker?.elapsedSeconds
                "firstSent" -> { checkSubset("firstSent", value as JSONObject, link.sent.firstOrNull()); continue }
                "lastSent" -> { checkSubset("lastSent", value as JSONObject, link.sent.lastOrNull()); continue }
                else -> fail("unknown expectation $key").let { null }
            }
            assertEquals(key, normalize(value), normalize(actual))
        }
    }

    private fun checkSubset(name: String, want: JSONObject, sent: Map<String, Any>?) {
        if (sent == null) fail("$name: nothing was sent")
        for (key in want.keys()) {
            if (want.isNull(key)) {
                assertTrue("$name.$key should be absent", !sent!!.containsKey(key))
            } else {
                assertEquals("$name.$key", normalize(want.get(key)), normalize(sent!![key]))
            }
        }
    }

    /** Consecutive repeats folded into one, so chunk counts do not matter. */
    private fun collapsed(types: List<Any?>): List<Any?> =
        types.fold(mutableListOf()) { out, type -> if (out.lastOrNull() != type) out += type; out }

    /** JSON and Kotlin values in one comparable form: numbers as Long or Double, lists as lists. */
    private fun normalize(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONArray -> (0 until value.length()).map { normalize(value.get(it)) }
        is List<*> -> value.map { normalize(it) }
        // org.json reads a decimal such as 3.5 as a BigDecimal.
        is java.math.BigDecimal -> normalize(value.toDouble())
        is Double -> if (value == Math.floor(value)) value.toLong() else value
        is Float -> normalize(value.toDouble())
        is Number -> value.toLong()
        else -> value
    }

    private fun toMap(json: JSONObject): Map<String, Any?> =
        json.keys().asSequence().associateWith { k -> json.get(k).let { if (it == JSONObject.NULL) null else it } }
}
