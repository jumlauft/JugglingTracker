import Toybox.Lang;
import Toybox.Math;
import Toybox.System;

// The accelerometer reports samples in milli-g (gravity still included). The
// detector converts each sample to m/s², keeps a low-pass estimate of the
// gravity vector, removes gravity, and computes the magnitude of the remaining
// linear acceleration. Threshold crossings on a highpass-filtered signal create
// candidates; nearby candidates are delayed and merged into catch bursts. The
// displayed count is every other committed burst, representing catches made by
// the hand wearing the watch. The detector also tracks each completed run's
// elapsed time from first to last counted watch-hand catch. After a pause the
// run is finished and the count moves to "previous run".
class JugglingDetector {
    private const MILLI_G_TO_MS2 = 9.80665f / 1000.0f;

    // Gravity low-pass filter coefficient.
    // During active juggling the filter slows down (_ACTIVE) to prevent gravity
    // drift from absorbing the sustained arm motion and attenuating the signal.
    private const GRAVITY_ALPHA_IDLE = 0.95f;
    private const GRAVITY_ALPHA_ACTIVE = 0.99f;

    // Per-ball-count refractory period (ms) between consecutive candidates.
    // Data-driven from delayed burst-clustering sweep for watch-hand catches.
    private const REFRACTORY_MS_3 = 80;
    private const REFRACTORY_MS_4 = 80;
    private const REFRACTORY_MS_5PLUS = 40;
    private const REFRACTORY_MS_7PLUS = 160;

    // Candidates closer than this are treated as lobes of one catch motion.
    private const MERGE_WINDOW_MS_3 = 160;
    private const MERGE_WINDOW_MS_4 = 160;
    private const MERGE_WINDOW_MS_5PLUS = 160;
    private const MERGE_WINDOW_MS_7PLUS = 80;

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

    // Detection thresholds for the highpass-filtered signal. Tuned against
    // labels that count catches by the watch-wearing hand only: total absolute
    // error 55 across 36 runs, positive overcount error 5.
    private const HP_THRESHOLD_3 = 2.0f;
    private const HP_THRESHOLD_4 = 3.0f;
    private const HP_THRESHOLD_5PLUS = 3.0f;
    private const HP_THRESHOLD_7PLUS = 3.0f;
    private const HP_HYSTERESIS = 0.3f;  // signal must drop below threshold * 0.3

    // Minimum raw (pre-highpass) magnitude for a candidate to count.
    // Prevents false positives from noise that the highpass filter amplifies.
    // Per-ball-count: 3b and 5+b use gates to suppress arm-swing noise;
    // 4b disables the gate because threshold/cluster timing is selective enough.
    private const MIN_RAW_MAG_3 = 7.0f;
    private const MIN_RAW_MAG_4 = 11.0f;
    private const MIN_RAW_MAG_5PLUS = 13.0f;
    private const MIN_RAW_MAG_7PLUS = 7.0f;

    // Number of balls being juggled (3-9), selected at startup.
    public var ballCount as Number;

    private var _gravityX as Float;
    private var _gravityY as Float;
    private var _gravityZ as Float;
    private var _lastCandidateTime as Number;
    // Last time a candidate burst was committed. Used for auto-finish: the run
    // ends after no catch-like burst has appeared for AUTO_FINISH_DELAY_MS.
    // Low-level post-run wrist motion must not keep the run alive.
    private var _lastActiveTime as Number;

    // Number of samples to wait before detecting catches, giving the gravity
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

    // Watch-hand catch counts of each completed run this session, in order.
    private var _runCatches as Array<Number>;
    // Milliseconds from first to last counted watch-hand catch for each run.
    private var _runDurationsMillis as Array<Number>;

    // Number of committed candidate bursts in the current run. The detector
    // counts odd-numbered bursts as the watch-hand catches and skips the
    // alternating bursts from the other hand.
    private var _committedBurstCount as Number;
    private var _hasFirstCatchTime as Boolean;
    private var _firstCatchTime as Number;
    private var _lastCatchTime as Number;

    // IIR highpass filter state.
    private var _hpX1 as Float;  // x[n-1]
    private var _hpX2 as Float;  // x[n-2]
    private var _hpY1 as Float;  // y[n-1]
    private var _hpY2 as Float;  // y[n-2]

    // Threshold-crossing candidate detection state.
    // When the filtered signal rises above HP_THRESHOLD we enter the "above"
    // state and track the strongest filtered peak. The candidate is emitted
    // only when the signal drops back below HP_THRESHOLD * HP_HYSTERESIS.
    private var _above as Boolean;   // true while filtered signal > threshold
    private var _peakTime as Number; // timestamp of highest sample in current crossing
    private var _peakRawMag as Float; // raw magnitude at highest filtered sample in current crossing
    private var _peakFiltered as Float;

    // Delayed burst-clustering state. A pending candidate becomes a count only
    // after no nearby candidate has appeared within _mergeWindowMs.
    private var _hasPendingPeak as Boolean;
    private var _pendingPeakTime as Number;
    private var _pendingPeakScore as Float;
    private var _clusterLastCandidateTime as Number;

    // Cached per-ball-count parameters (set once in initialize).
    private var _hpThreshold as Float;
    private var _refractoryMs as Number;
    private var _minRawMag as Float;
    private var _mergeWindowMs as Number;

    public function initialize(balls as Number) {
        ballCount = balls;
        _gravityX = 0.0f;
        _gravityY = 0.0f;
        _gravityZ = 9.80665f;
        _lastCandidateTime = 0;
        _lastActiveTime = 0;
        _samplesSeen = 0;
        _gravityInitialized = false;
        currentCount = 0;
        previousCount = 0;
        _sessionRuns = 0;
        _sessionTotal = 0;
        sessionMax = 0;
        _runCatches = [];
        _runDurationsMillis = [];
        _committedBurstCount = 0;
        _hasFirstCatchTime = false;
        _firstCatchTime = 0;
        _lastCatchTime = 0;
        _hpX1 = 0.0f;
        _hpX2 = 0.0f;
        _hpY1 = 0.0f;
        _hpY2 = 0.0f;
        _above = false;
        _peakTime = 0;
        _peakRawMag = 0.0f;
        _peakFiltered = 0.0f;
        _hasPendingPeak = false;
        _pendingPeakTime = 0;
        _pendingPeakScore = 0.0f;
        _clusterLastCandidateTime = 0;

        // Cache ball-count-adaptive parameters.
        if (balls <= 3) {
            _hpThreshold = HP_THRESHOLD_3;
            _refractoryMs = REFRACTORY_MS_3;
            _minRawMag = MIN_RAW_MAG_3;
            _mergeWindowMs = MERGE_WINDOW_MS_3;
        } else if (balls == 4) {
            _hpThreshold = HP_THRESHOLD_4;
            _refractoryMs = REFRACTORY_MS_4;
            _minRawMag = MIN_RAW_MAG_4;
            _mergeWindowMs = MERGE_WINDOW_MS_4;
        } else if (balls <= 6) {
            _hpThreshold = HP_THRESHOLD_5PLUS;
            _refractoryMs = REFRACTORY_MS_5PLUS;
            _minRawMag = MIN_RAW_MAG_5PLUS;
            _mergeWindowMs = MERGE_WINDOW_MS_5PLUS;
        } else {
            // 7+ is a distinctly faster cadence than 5, and was previously run
            // on parameters fitted entirely to 5-ball data.
            _hpThreshold = HP_THRESHOLD_7PLUS;
            _refractoryMs = REFRACTORY_MS_7PLUS;
            _minRawMag = MIN_RAW_MAG_7PLUS;
            _mergeWindowMs = MERGE_WINDOW_MS_7PLUS;
        }
    }

    // Watch-hand catch counts of all completed runs this session, in order.
    public function runCatches() as Array<Number> {
        return _runCatches;
    }

    public function runDurationsMillis() as Array<Number> {
        return _runDurationsMillis;
    }

    // Fold a finished run's watch-hand catch count into the session stats and
    // per-run list. Shared by auto-finish and manual session end.
    private function recordRun(catches as Number) as Void {
        previousCount = catches;
        _sessionRuns += 1;
        _sessionTotal += catches;
        if (catches > sessionMax) {
            sessionMax = catches;
        }
        _runCatches.add(catches);
        _runDurationsMillis.add(currentRunDurationMillis());
    }

    private function currentRunDurationMillis() as Number {
        if (!_hasFirstCatchTime || _lastCatchTime < _firstCatchTime) {
            return 0;
        }
        return _lastCatchTime - _firstCatchTime;
    }

    private function hasActiveRun() as Boolean {
        return currentCount > 0 || _hasPendingPeak || _committedBurstCount > 0;
    }

    public function isRunActive() as Boolean {
        return hasActiveRun();
    }

    private function clearRunDetectionState() as Void {
        _lastCandidateTime = 0;
        _lastActiveTime = 0;
        _above = false;
        _peakTime = 0;
        _peakRawMag = 0.0f;
        _peakFiltered = 0.0f;
        _hasPendingPeak = false;
        _pendingPeakTime = 0;
        _pendingPeakScore = 0.0f;
        _clusterLastCandidateTime = 0;
        _committedBurstCount = 0;
        _hasFirstCatchTime = false;
        _firstCatchTime = 0;
        _lastCatchTime = 0;
    }

    private function commitPendingPeak(nowMs as Number) as Void {
        if (!_hasPendingPeak) {
            return;
        }
        _committedBurstCount += 1;
        if ((_committedBurstCount % 2) == 1) {
            currentCount += 1;
            if (!_hasFirstCatchTime) {
                _firstCatchTime = _pendingPeakTime;
                _hasFirstCatchTime = true;
            }
            _lastCatchTime = _pendingPeakTime;
        }
        _lastActiveTime = nowMs;
        _hasPendingPeak = false;
        _pendingPeakTime = 0;
        _pendingPeakScore = 0.0f;
        _clusterLastCandidateTime = 0;
    }

    private function addCandidate(candidateTime as Number, score as Float, nowMs as Number) as Void {
        if (!_hasPendingPeak) {
            _hasPendingPeak = true;
            _pendingPeakTime = candidateTime;
            _pendingPeakScore = score;
            _clusterLastCandidateTime = candidateTime;
            return;
        }

        if (candidateTime - _clusterLastCandidateTime < _mergeWindowMs) {
            if (score > _pendingPeakScore) {
                _pendingPeakTime = candidateTime;
                _pendingPeakScore = score;
            }
            _clusterLastCandidateTime = candidateTime;
            return;
        }

        commitPendingPeak(nowMs);
        _hasPendingPeak = true;
        _pendingPeakTime = candidateTime;
        _pendingPeakScore = score;
        _clusterLastCandidateTime = candidateTime;
    }

    private function flushPendingPeak(nowMs as Number) as Void {
        if (_hasPendingPeak && !_above && nowMs - _clusterLastCandidateTime >= _mergeWindowMs) {
            commitPendingPeak(nowMs);
        }
    }

    // Ends a run that is still in progress (e.g. when the user stops the
    // session manually). Records it if it has any watch-hand catches. Returns
    // the watch-hand catch count.
    public function finishCurrentRun() as Number {
        if (_hasPendingPeak) {
            commitPendingPeak(_pendingPeakTime);
        }
        if (currentCount > 0) {
            var finished = currentCount;
            recordRun(finished);
            currentCount = 0;
            clearRunDetectionState();
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

    // Average watch-hand catches per completed run this session (0.0 if no runs yet).
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
            var alpha = hasActiveRun() ? GRAVITY_ALPHA_ACTIVE : GRAVITY_ALPHA_IDLE;
            _gravityX = alpha * _gravityX + (1.0f - alpha) * ax;
            _gravityY = alpha * _gravityY + (1.0f - alpha) * ay;
            _gravityZ = alpha * _gravityZ + (1.0f - alpha) * az;
        }

        // Linear acceleration = total - gravity.
        var lx = ax - _gravityX;
        var ly = ay - _gravityY;
        var lz = az - _gravityZ;

        // Acceleration magnitude — captures catch energy in all directions
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

        // Threshold-crossing candidate detection on the highpass-filtered signal.
        // One candidate per complete above→below cycle: signal must rise above
        // _hpThreshold and then fall below _hpThreshold * HP_HYSTERESIS.
        // Candidates are delayed and clustered before they become counts.

        if (!_above) {
            // Waiting for signal to rise above threshold.
            if (filtered > _hpThreshold) {
                _above = true;
                _peakTime = nowMs;
                _peakFiltered = filtered;
                _peakRawMag = mag;
            }
        } else {
            // Track the strongest filtered peak while above threshold.
            if (filtered > _peakFiltered) {
                _peakFiltered = filtered;
                _peakTime = nowMs;
            }
            if (mag > _peakRawMag) {
                _peakRawMag = mag;
            }
            // Wait for it to drop below hysteresis level.
            if (filtered < _hpThreshold * HP_HYSTERESIS) {
                _above = false;
                // Add a candidate if refractory period has elapsed and the raw
                // magnitude confirms a real catch motion (not just noise).
                if (_peakTime - _lastCandidateTime > _refractoryMs &&
                    _peakRawMag > _minRawMag) {
                    addCandidate(_peakTime, _peakFiltered, nowMs);
                    _lastCandidateTime = _peakTime;
                }
            }
        }

        flushPendingPeak(nowMs);
    }

    // Finishes the current run if it has been idle long enough. Returns the
    // number of watch-hand catches in the just-finished run, or 0 if no run finished.
    public function checkAutoFinish(nowMs as Number) as Number {
        flushPendingPeak(nowMs);
        if (currentCount > 0 && _lastActiveTime > 0 && (nowMs - _lastActiveTime > AUTO_FINISH_DELAY_MS)) {
            var finished = currentCount;

            // Fold the finished run into the session statistics.
            recordRun(finished);

            currentCount = 0;
            clearRunDetectionState();

            return finished;
        }
        return 0;
    }
}
