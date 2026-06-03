import Toybox.Lang;
import Toybox.Math;
import Toybox.System;

// The accelerometer reports samples in milli-g (gravity still included). The
// detector converts each sample to m/s², keeps a low-pass estimate of the
// gravity vector, removes gravity, and computes the magnitude of the remaining
// linear acceleration. True peak detection (local maxima above threshold with
// hysteresis) counts throws. Each detected peak adds 2 to the count (one wrist
// sensor sees one arm; doubled for the other hand). After a pause with no
// throws the run is finished and the count moves to "previous run".
class JugglingDetector {
    private const MILLI_G_TO_MS2 = 9.80665f / 1000.0f;

    // Gravity low-pass filter coefficient.
    // During active juggling the filter slows down (_ACTIVE) to prevent gravity
    // drift from absorbing the sustained arm motion and attenuating the signal.
    private const GRAVITY_ALPHA_IDLE = 0.95f;
    private const GRAVITY_ALPHA_ACTIVE = 0.99f;

    // Refractory period between consecutive peaks. With the highpass
    // filter removing drift, a fixed 200ms works across all ball counts.
    private const REFRACTORY_MS = 200;

    private const AUTO_FINISH_DELAY_MS = 2000;

    // ── 2nd-order Butterworth IIR highpass filter (0.7 Hz, fs=25 Hz) ──
    // Removes slow gravity-estimation drift from the magnitude signal,
    // giving cleaner peaks that don't depend on ball-count-specific thresholds.
    // Coefficients from: butter(2, 0.7, btype='highpass', fs=25, output='sos')
    private const HP_B0 =  0.883002f;
    private const HP_B1 = -1.766004f;
    private const HP_B2 =  0.883002f;
    private const HP_A1 = -1.752268f;  // negated in difference equation
    private const HP_A2 =  0.779739f;  // negated in difference equation

    // Detection thresholds for the highpass-filtered signal.
    // Universal — no per-ball-count scaling needed because the filter
    // normalises the signal by removing the DC component.
    private const HP_THRESHOLD = 1.0f;
    private const HP_HYSTERESIS = 0.3f;  // signal must drop below threshold * 0.3

    // Number of balls being juggled (3-9), selected at startup.
    public var ballCount as Number;

    private var _gravityX as Float;
    private var _gravityY as Float;
    private var _gravityZ as Float;
    private var _lastThrowTime as Number;
    // Last time the smoothed signal was above the activity floor (half the
    // detection threshold). Used for auto-finish: the run ends when the
    // signal stays below the activity floor for AUTO_FINISH_DELAY_MS,
    // rather than when no peaks are detected — prevents premature splits.
    private var _lastActiveTime as Number;

    // Number of samples to wait before detecting throws, giving the gravity
    // estimate time to settle. Prevents a spurious run at startup.
    private const WARMUP_SAMPLES = 25;
    private var _samplesSeen as Number;
    private var _gravityInitialized as Boolean;

    public var currentCount as Number;
    public var previousCount as Number;

    // Session statistics over completed runs.
    private var _sessionRuns as Number;
    private var _sessionTotal as Number;
    public var sessionMax as Number;

    // Throw counts of each completed run this session, in order.
    private var _runThrows as Array<Number>;

    // IIR highpass filter state.
    private var _hpX1 as Float;  // x[n-1]
    private var _hpX2 as Float;  // x[n-2]
    private var _hpY1 as Float;  // y[n-1]
    private var _hpY2 as Float;  // y[n-2]

    // Threshold-crossing peak detection state.
    // When the filtered signal rises above HP_THRESHOLD we enter the "above"
    // state and track the peak value and time.  The peak is only registered
    // when the signal drops back below HP_THRESHOLD * HP_HYSTERESIS, giving
    // exactly one detection per above→below cycle (matches Python simulation).
    private var _above as Boolean;   // true while filtered signal > threshold
    private var _peakTime as Number; // timestamp of highest sample in current crossing

    // Set to true to print per-sample debug info via System.println().
    private const DEBUG_LOG = false;

    public function initialize(balls as Number) {
        ballCount = balls;
        _gravityX = 0.0f;
        _gravityY = 0.0f;
        _gravityZ = 9.80665f;
        _lastThrowTime = 0;
        _lastActiveTime = 0;
        _samplesSeen = 0;
        _gravityInitialized = false;
        currentCount = 0;
        previousCount = 0;
        _sessionRuns = 0;
        _sessionTotal = 0;
        sessionMax = 0;
        _runThrows = [];
        _hpX1 = 0.0f;
        _hpX2 = 0.0f;
        _hpY1 = 0.0f;
        _hpY2 = 0.0f;
        _above = false;
        _peakTime = 0;
    }

    // Throw counts of all completed runs this session, in order.
    public function runThrows() as Array<Number> {
        return _runThrows;
    }

    // Fold a finished run's throw count into the session statistics and the
    // per-run list. Shared by auto-finish and manual session end.
    private function recordRun(throws as Number) as Void {
        previousCount = throws;
        _sessionRuns += 1;
        _sessionTotal += throws;
        if (throws > sessionMax) {
            sessionMax = throws;
        }
        _runThrows.add(throws);
    }

    // Ends a run that is still in progress (e.g. when the user stops the
    // session manually). Records it if it has any throws. Returns the count.
    public function finishCurrentRun() as Number {
        if (currentCount > 0) {
            var finished = currentCount;
            recordRun(finished);
            currentCount = 0;
            _lastThrowTime = 0;
            return finished;
        }
        return 0;
    }

    // Apply the IIR highpass filter to one sample of the magnitude signal.
    // Returns the filtered value. Updates internal filter state.
    private function applyHighpass(x as Float) as Float {
        var y = HP_B0 * x + HP_B1 * _hpX1 + HP_B2 * _hpX2
                           - HP_A1 * _hpY1 - HP_A2 * _hpY2;
        _hpX2 = _hpX1;
        _hpX1 = x;
        _hpY2 = _hpY1;
        _hpY1 = y;
        return y;
    }

    // Average throws per completed run this session (0.0 if no runs yet).
    public function sessionAverage() as Float {
        if (_sessionRuns == 0) {
            return 0.0f;
        }
        return _sessionTotal.toFloat() / _sessionRuns;
    }

    // Number of completed runs in the current session.
    public function sessionRuns() as Number {
        return _sessionRuns;
    }

    // Feed one raw accelerometer sample (milli-g) at time nowMs.
    public function processSample(gxMilliG as Number, gyMilliG as Number, gzMilliG as Number, nowMs as Number) as Void {
        var ax = gxMilliG * MILLI_G_TO_MS2;
        var ay = gyMilliG * MILLI_G_TO_MS2;
        var az = gzMilliG * MILLI_G_TO_MS2;

        // Seed the gravity estimate from the first real sample so it matches the
        // watch's actual orientation instead of an assumed "down" direction.
        if (!_gravityInitialized) {
            _gravityX = ax;
            _gravityY = ay;
            _gravityZ = az;
            _gravityInitialized = true;
        } else {
            // Slow down gravity adaptation during active juggling to prevent
            // the estimate from drifting toward sustained arm motion.
            var alpha = (currentCount > 0) ? GRAVITY_ALPHA_ACTIVE : GRAVITY_ALPHA_IDLE;
            _gravityX = alpha * _gravityX + (1.0f - alpha) * ax;
            _gravityY = alpha * _gravityY + (1.0f - alpha) * ay;
            _gravityZ = alpha * _gravityZ + (1.0f - alpha) * az;
        }

        // Linear acceleration = total - gravity.
        var lx = ax - _gravityX;
        var ly = ay - _gravityY;
        var lz = az - _gravityZ;

        // Acceleration magnitude — captures throw energy in all directions
        // (cascade, shower, columns) rather than only the vertical component.
        var mag = Math.sqrt(lx * lx + ly * ly + lz * lz).toFloat();

        _samplesSeen += 1;

        // Feed every sample into the highpass filter (including warmup) so
        // the filter state tracks the signal from the start. This avoids a
        // transient burst when detection begins.
        var filtered = applyHighpass(mag);

        // Ignore the first few samples while the gravity estimate settles so a
        // stationary watch never registers a startup run.
        if (_samplesSeen <= WARMUP_SAMPLES) {
            return;
        }

        // Threshold-crossing peak detection on the highpass-filtered signal.
        // One peak per complete above→below cycle: signal must rise above
        // HP_THRESHOLD and then fall below HP_THRESHOLD * HP_HYSTERESIS.
        // The peak is registered on the down-crossing, not at the local max.
        var isPeak = false;

        if (!_above) {
            // Waiting for signal to rise above threshold.
            if (filtered > HP_THRESHOLD) {
                _above = true;
                _peakTime = nowMs;
            }
        } else {
            // Signal is above threshold — track when we entered.
            // Wait for it to drop below hysteresis level.
            if (filtered < HP_THRESHOLD * HP_HYSTERESIS) {
                _above = false;
                // Register the peak if refractory period has elapsed.
                if (_peakTime - _lastThrowTime > REFRACTORY_MS) {
                    isPeak = true;
                }
            }
        }

        // Track activity: any sample with filtered value above half the
        // threshold keeps the run alive. Prevents premature auto-finish
        // during brief dips between peaks.
        if (currentCount > 0 && filtered > HP_THRESHOLD * 0.5f) {
            _lastActiveTime = nowMs;
        }

        if (isPeak) {
            currentCount += 2;  // Both hands: one detected peak = 2 throws
            _lastThrowTime = _peakTime;
            _lastActiveTime = nowMs;
        }

        if (DEBUG_LOG) {
            System.println("JDET: mag=" + mag.format("%.1f") +
                " hp=" + filtered.format("%.2f") +
                " above=" + _above +
                " pk=" + isPeak +
                " cnt=" + currentCount);
        }
    }

    // Finishes the current run if it has been idle long enough. Returns the
    // number of throws in the just-finished run, or -1 if no run finished.
    public function checkAutoFinish(nowMs as Number) as Number {
        if (currentCount > 0 && _lastActiveTime > 0 && (nowMs - _lastActiveTime > AUTO_FINISH_DELAY_MS)) {
            var finished = currentCount;

            // Fold the finished run into the session statistics.
            recordRun(finished);

            currentCount = 0;
            _lastThrowTime = 0;

            return finished;
        }
        return -1;
    }
}
