import Toybox.Lang;
import Toybox.Math;

// Shape consistency: how alike each hand cycle is to the one before it.
//
// Every sample is highpassed per axis (the detector's 0.7 Hz filter, which
// also removes gravity) and kept in a short history. Once a second during a
// run, the last WINDOW samples are compared with the WINDOW samples one cycle
// earlier, for every lag from MIN_LAG to MAX_LAG (0.36 s to 1.4 s, one hand
// cycle at 3 to 7+ balls). The score is the best normalised 3-axis correlation
// over those lags: 1.0 when the wrist repeats exactly the same motion every
// cycle, near 0 when one cycle says nothing about the next.
//
// Only windows lying wholly between the run's first and last watch-hand catch
// count, so the start-up throws and the drop at the end do not. A window is
// held as pending until a later catch confirms it. The session score weights
// each run by its windows and is reported as a whole percentage.
//
// Reference: simulation/shape_consistency.py. Keep in step with it and with
// shared/.../ShapeConsistency.kt.
class ShapeConsistency {
    private const SAMPLE_PERIOD_MS = 40;
    private const WINDOW = 50;
    private const MIN_LAG = 9;
    private const MAX_LAG = 35;
    private const HISTORY = 85;  // WINDOW + MAX_LAG
    private const SCORE_EVERY = 25;
    private const MAX_PENDING = 8;

    // Same 0.7 Hz highpass as JugglingDetector.
    private const HP_B0 =  0.883002f;
    private const HP_B1 = -1.766004f;
    private const HP_B2 =  0.883002f;
    private const HP_A1 = -1.752268f;
    private const HP_A2 =  0.779739f;

    private var _histX as Array<Float>;
    private var _histY as Array<Float>;
    private var _histZ as Array<Float>;
    private var _head as Number;
    private var _samples as Number;

    // Scratch for score(), allocated once: the history unrolled newest first,
    // plus per-sample energy. Allocating these every second churned the heap.
    private var _ax as Array<Float>;
    private var _ay as Array<Float>;
    private var _az as Array<Float>;
    private var _e as Array<Float>;

    // Highpass state per axis: x[n-1], x[n-2], y[n-1], y[n-2].
    private var _hpX as Array<Float>;
    private var _hpY as Array<Float>;
    private var _hpZ as Array<Float>;

    private var _pendingEnd as Array<Number>;
    private var _pendingScore as Array<Float>;

    private var _runSum as Float;
    private var _runCount as Number;
    private var _runSums as Array<Float>;
    private var _runCounts as Array<Number>;

    public function initialize() {
        _histX = new [HISTORY] as Array<Float>;
        _histY = new [HISTORY] as Array<Float>;
        _histZ = new [HISTORY] as Array<Float>;
        for (var i = 0; i < HISTORY; i++) {
            _histX[i] = 0.0f;
            _histY[i] = 0.0f;
            _histZ[i] = 0.0f;
        }
        _ax = new [HISTORY] as Array<Float>;
        _ay = new [HISTORY] as Array<Float>;
        _az = new [HISTORY] as Array<Float>;
        _e = new [HISTORY] as Array<Float>;
        _head = 0;
        _samples = 0;
        _hpX = [0.0f, 0.0f, 0.0f, 0.0f] as Array<Float>;
        _hpY = [0.0f, 0.0f, 0.0f, 0.0f] as Array<Float>;
        _hpZ = [0.0f, 0.0f, 0.0f, 0.0f] as Array<Float>;
        _pendingEnd = [] as Array<Number>;
        _pendingScore = [] as Array<Float>;
        _runSum = 0.0f;
        _runCount = 0;
        _runSums = [] as Array<Float>;
        _runCounts = [] as Array<Number>;
    }

    private function highpass(s as Array<Float>, v as Float) as Float {
        var out = HP_B0 * v + HP_B1 * s[0] + HP_B2 * s[1] - HP_A1 * s[2] - HP_A2 * s[3];
        s[1] = s[0];
        s[0] = v;
        s[3] = s[2];
        s[2] = out;
        return out;
    }

    // Feed one raw sample (milli-g) after the detector has processed it.
    public function addSample(x as Number, y as Number, z as Number, nowMs as Number,
                              runActive as Boolean, hasFirstCatch as Boolean,
                              firstCatchMs as Number) as Void {
        _histX[_head] = highpass(_hpX, x.toFloat());
        _histY[_head] = highpass(_hpY, y.toFloat());
        _histZ[_head] = highpass(_hpZ, z.toFloat());
        _head = (_head + 1) % HISTORY;
        _samples += 1;

        if (_samples < HISTORY || _samples % SCORE_EVERY != 0) {
            return;
        }
        if (!runActive || !hasFirstCatch) {
            return;
        }
        if (nowMs - (HISTORY - 1) * SAMPLE_PERIOD_MS < firstCatchMs) {
            return;
        }
        if (_pendingEnd.size() >= MAX_PENDING) {
            _pendingEnd = _pendingEnd.slice(1, null);
            _pendingScore = _pendingScore.slice(1, null);
        }
        _pendingEnd.add(nowMs);
        _pendingScore.add(score());
    }

    private function score() as Float {
        // Newest first: ax[k] is the sample k steps back.
        var ax = _ax;
        var ay = _ay;
        var az = _az;
        var e = _e;
        for (var k = 0; k < HISTORY; k++) {
            var i = (_head - 1 - k + HISTORY) % HISTORY;
            ax[k] = _histX[i];
            ay[k] = _histY[i];
            az[k] = _histZ[i];
            e[k] = ax[k] * ax[k] + ay[k] * ay[k] + az[k] * az[k];
        }
        var e0 = 0.0f;
        for (var k = 0; k < WINDOW; k++) {
            e0 += e[k];
        }
        if (e0 <= 0.0f) {
            return 0.0f;
        }
        var el = 0.0f;
        for (var k = MIN_LAG; k < MIN_LAG + WINDOW; k++) {
            el += e[k];
        }
        var best = 0.0f;
        for (var lag = MIN_LAG; lag <= MAX_LAG; lag++) {
            if (lag > MIN_LAG) {
                el += e[lag + WINDOW - 1] - e[lag - 1];
            }
            var cross = 0.0f;
            for (var k = 0; k < WINDOW; k++) {
                var j = k + lag;
                cross += ax[k] * ax[j] + ay[k] * ay[j] + az[k] * az[j];
            }
            var denom = e0 * el;
            if (denom > 0.0f) {
                var c = cross / Math.sqrt(denom);
                if (c > best) {
                    best = c;
                }
            }
        }
        return best > 1.0f ? 1.0f : best;
    }

    // A watch-hand catch at catchMs confirms every window ending by then.
    public function onCatch(catchMs as Number) as Void {
        while (_pendingEnd.size() > 0 && _pendingEnd[0] <= catchMs) {
            _runSum += _pendingScore[0];
            _runCount += 1;
            _pendingEnd = _pendingEnd.slice(1, null);
            _pendingScore = _pendingScore.slice(1, null);
        }
    }

    public function commitRun() as Void {
        _runSums.add(_runSum);
        _runCounts.add(_runCount);
        clearRun();
    }

    public function clearRun() as Void {
        _runSum = 0.0f;
        _runCount = 0;
        _pendingEnd = [] as Array<Number>;
        _pendingScore = [] as Array<Float>;
    }

    public function discardLastRun() as Void {
        var n = _runSums.size();
        if (n > 0) {
            _runSums = _runSums.slice(0, n - 1);
            _runCounts = _runCounts.slice(0, n - 1);
        }
    }

    // Whole percentage over the session, or -1 before any window counts.
    public function sessionPercent() as Number {
        var sum = 0.0f;
        var count = 0;
        for (var i = 0; i < _runSums.size(); i++) {
            sum += _runSums[i];
            count += _runCounts[i];
        }
        if (count == 0) {
            return -1;
        }
        return Math.floor(100.0f * sum / count + 0.5f).toNumber();
    }
}
