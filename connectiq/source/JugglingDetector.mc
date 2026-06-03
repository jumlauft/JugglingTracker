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

    // Ball-count-dependent refractory: REFRACTORY_BASE_MS / (ballCount - 1).
    // 3 balls → 50ms, 5 balls → 25ms, 7 balls → 17ms.
    private const REFRACTORY_BASE_MS = 100;

    private const AUTO_FINISH_DELAY_MS = 2000;

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

    // True peak detection state.
    // _prevMag / _prevPrevMag: two-sample history for 3-point moving average
    // and local-maximum detection.
    private var _prevMag as Float;
    private var _prevPrevMag as Float;
    // Hysteresis: after detecting a peak we require the signal to drop below
    // threshold * hysteresisFactor() before another peak can fire.
    // 3 balls → 0.6, interpolated up to 0.65 for 5 balls, etc.
    private var _armed as Boolean;  // true = ready to detect next peak

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
        _prevMag = 0.0f;
        _prevPrevMag = 0.0f;
        _armed = true;
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

    // Detection threshold (m/s²). Scales with ball count: higher ball counts
    // produce higher-energy throws. Formula: 9.0 + ballCount.
    //   3 balls → 12.0, 5 balls → 14.0, 7 balls → 16.0, 9 balls → 18.0.
    // Validated against recorded IMU data across 3-ball and 5-ball sessions
    // with accurate gravity-alpha simulation.
    private function threshold() as Float {
        return 9.0f + ballCount;
    }

    // Hysteresis factor: signal must drop below threshold * hysteresisFactor()
    // before the next peak can fire. Higher ball counts need a higher factor
    // because the signal stays elevated between rapid throws.
    //   3 balls → 0.55, 5 balls → 0.70, 7 balls → 0.80 (capped).
    private function hysteresisFactor() as Float {
        var f = 0.325f + 0.075f * ballCount;
        if (f > 0.8f) {
            return 0.8f;
        }
        return f;
    }

    // Refractory period (ms) between consecutive throws. Faster patterns
    // (more balls) get a shorter refractory so rapid throws aren't missed.
    private function refractoryMs() as Number {
        return REFRACTORY_BASE_MS / (ballCount - 1);
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

        // 3-point moving average to smooth single-sample noise spikes.
        var smoothed = ((_prevPrevMag + _prevMag + mag) / 3.0f).toFloat();

        _samplesSeen += 1;

        // Ignore the first few samples while the gravity estimate settles so a
        // stationary watch never registers a startup run.
        if (_samplesSeen <= WARMUP_SAMPLES) {
            _prevPrevMag = _prevMag;
            _prevMag = mag;
            return;
        }

        // True peak detection: a peak is a local maximum in the smoothed signal
        // that exceeds the threshold, with hysteresis to avoid multiple
        // detections from noisy oscillation near the threshold.
        var thresholdValue = threshold();
        var refractory = refractoryMs();

        // Re-arm once signal drops below hysteresis band.
        var hystFactor = hysteresisFactor();
        if (!_armed && smoothed < thresholdValue * hystFactor) {
            _armed = true;
        }

        // Detect local maximum: previous smoothed value was higher than both
        // its neighbors (current and the one before it), exceeded threshold,
        // detector is armed, and refractory period has elapsed.
        // We evaluate the *previous* smoothed value so we have a one-sample
        // look-ahead to confirm the signal is falling.
        // Peak is a local maximum in the raw magnitude: previous sample is
        // higher than both its neighbors, the smoothed value exceeds the
        // threshold, detector is armed, and the refractory period has elapsed.
        var isPeak = (_prevMag >= mag) && (_prevMag >= _prevPrevMag) &&
                     (smoothed > thresholdValue) &&
                     _armed &&
                     (nowMs - _lastThrowTime > refractory);

        // Track activity: any sample above half the threshold keeps the
        // run alive. This prevents auto-finish during brief dips between
        // peaks that don't quite reach the full detection threshold.
        if (currentCount > 0 && smoothed > thresholdValue * 0.5f) {
            _lastActiveTime = nowMs;
        }

        if (isPeak) {
            currentCount += 2;  // Both hands: one detected peak = 2 throws
            _lastThrowTime = nowMs;
            _lastActiveTime = nowMs;
            _armed = false;  // Require signal to drop before next detection
        }

        if (DEBUG_LOG) {
            System.println("JDET: mag=" + mag.format("%.1f") +
                " sm=" + smoothed.format("%.1f") +
                " thr=" + thresholdValue.format("%.1f") +
                " arm=" + _armed +
                " pk=" + isPeak +
                " cnt=" + currentCount);
        }

        _prevPrevMag = _prevMag;
        _prevMag = mag;
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
