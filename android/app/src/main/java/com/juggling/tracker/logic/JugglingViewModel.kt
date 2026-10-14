package com.juggling.tracker.logic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.analytics.FirebaseAnalytics
import com.juggling.tracker.data.RecordingRepository
import com.juggling.tracker.data.SessionRepository
import com.juggling.tracker.data.SettingsManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

sealed class JugglingEvent {
    data class Announcement(val text: String) : JugglingEvent()
    data class SyncCompleted(val count: Int, val ballCount: Int) : JugglingEvent()
    data class PhoneSessionSaved(val count: Int, val ballCount: Int) : JugglingEvent()
}

/**
 * Holds the app's models and wires them together; each screen talks to the
 * models it shows:
 *
 * - [watchStatus]: the watch card on the home screen
 * - [history]: the session history
 * - [phoneSession]: a session counted with the phone's own sensor
 * - [rawRecording]: raw recordings for the detection corpus
 * - [settings]: everything on the Settings screen but the recordings
 *
 * What the watches send reaches them through the [WatchInbox]. The iPhone
 * app's TrackerModel holds the same models.
 */
class JugglingViewModel(
    repository: SessionRepository? = null,
    recordingRepository: RecordingRepository? = null,
    analytics: FirebaseAnalytics? = null,
    settingsManager: SettingsManager? = null,
    // The app's own inbox, shared with the Wear OS listener service and the
    // Garmin link; it must hold the same repositories as this view model.
    // Without one (tests) the view model gets an inbox of its own.
    inbox: WatchInbox? = null,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _events = MutableSharedFlow<JugglingEvent>()
    val events = _events.asSharedFlow()

    val watchStatus = WatchStatusModel(viewModelScope)
    val history = HistoryModel(repository)
    val settings = SettingsModel(settingsManager, history, repository, analytics, ioDispatcher)
    val phoneSession = PhoneSessionModel(history, analytics, onCatch = ::announce)
    val rawRecording = RawRecordingModel(
        viewModelScope, recordingRepository, { settings.currentJuggler }, ioDispatcher, onCatch = ::announce,
    )

    private val inbox = inbox ?: WatchInbox(repository, recordingRepository, analytics) { settings.currentJuggler }

    // What the watches deliver reaches the screens through the inbox, which
    // may have stored it while no screen was open at all.
    private val inboxListener: (WatchInbox.Event) -> Unit = { event ->
        when (event) {
            is WatchInbox.Event.Receiving -> watchStatus.showReceiving(event.source, event.more)
            is WatchInbox.Event.SessionStored -> {
                history.showReceived(event.session)
                emit(JugglingEvent.SyncCompleted(event.session.runCount, event.session.ballCount))
            }
            WatchInbox.Event.RecordingStored -> rawRecording.refresh()
        }
    }

    init {
        this.inbox.addListener(inboxListener)
    }

    override fun onCleared() {
        inbox.removeListener(inboxListener)
        super.onCleared()
    }

    private fun emit(event: JugglingEvent) {
        viewModelScope.launch { _events.emit(event) }
    }

    private fun announce(count: Int) {
        if (settings.shouldAnnounce(count)) emit(JugglingEvent.Announcement(count.toString()))
    }

    /**
     * True while the accelerometer has to keep streaming, so the UI can hold the
     * screen awake for exactly that long.
     *
     * MainActivity unregisters the sensor listener in onPause, and the display
     * timeout triggers onPause: nobody touches the screen while juggling, so any
     * session outlasting the timeout silently stopped counting, and the gap in
     * samples then auto-finished the run that was in progress. Deriving this
     * from the recording state rather than toggling a flag at the call sites
     * means it cannot drift out of step with the sensor.
     */
    val shouldKeepScreenOn: Boolean
        get() = isUsingPhoneSensor

    /**
     * Whether a phone session or raw recording still wants accelerometer
     * samples. False once a frozen sensor ended the session, so the screen
     * can let go of the sensor.
     */
    val isUsingPhoneSensor: Boolean
        get() = phoneSession.state.isRecording || rawRecording.state.step == RawRecordingStep.RECORDING

    /** Hands a batch of phone accelerometer events to whichever is capturing. */
    fun processPhoneSamples(samples: List<PhoneAccelSample>) {
        if (samples.isEmpty()) return
        rawRecording.process(samples)
        phoneSession.process(samples)
    }

    fun processPhoneSample(ax: Double, ay: Double, az: Double, timestampNanos: Long) {
        processPhoneSamples(listOf(PhoneAccelSample(ax, ay, az, timestampNanos)))
    }

    /** Ends the phone session and stores it; false when there was no run to save. */
    fun stopPhoneSessionAndSave(stoppedAtMillis: Long = System.currentTimeMillis()): Boolean {
        val balls = phoneSession.state.selectedBallCount
        val runs = phoneSession.stopAndSave(stoppedAtMillis) ?: return false
        emit(JugglingEvent.PhoneSessionSaved(runs, balls))
        return true
    }

    // ── Watch messages, fed in directly ─────────────────────────────────

    /**
     * Feeds a finished session straight into the inbox, as if [source] had
     * sent it. See [WatchInbox.importSession] for the payload.
     */
    fun importSessionFromWatch(payload: Map<String, Any>, source: WatchInbox.Source = WatchInbox.Source.GARMIN) {
        inbox.importSession(payload, source)
    }

    // A watch's recorded run arrives as rec_start, rec_chunk parts and
    // rec_end, and the inbox reassembles it. These feed it directly, as if a
    // watch had sent them; see WatchInbox for the rules.

    fun startRecordingTransfer(payload: Map<String, Any>) = inbox.startRecordingTransfer(payload)

    fun appendRecordingChunk(payload: Map<String, Any>) = inbox.appendRecordingChunk(payload)

    /** Returns true when a complete run was written. */
    fun finishRecordingTransfer(payload: Map<String, Any>, source: WatchInbox.Source = WatchInbox.Source.GARMIN): Boolean =
        inbox.finishRecordingTransfer(payload, source)
}
