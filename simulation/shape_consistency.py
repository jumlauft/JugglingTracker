"""
Shape consistency: how alike each hand cycle is to the one before it.

Reference implementation of `connectiq/source/ShapeConsistency.mc` and
`wearos/.../logic/ShapeConsistency.kt`. Keep the three in step.

Every sample is highpassed per axis (the detector's 0.7 Hz filter, which also
removes gravity) and kept in a short history. Once a second during a run, the
last WINDOW samples are compared with the WINDOW samples one cycle earlier, for
every lag from MIN_LAG to MAX_LAG (0.36 s to 1.4 s, one hand cycle at 3 to 7+
balls). The score is the best normalised 3-axis correlation over those lags:
1.0 when the wrist repeats exactly the same motion every cycle, near 0 when one
cycle says nothing about the next. It needs no catch timing and no integration,
so a missed or extra detection cannot disturb it.

Only windows lying wholly between the run's first and last watch-hand catch
count, so the start-up throws and the drop at the end do not. A run's score is
the mean of its windows; the session score weights each run by its windows,
reported as a whole percentage, or -1 before any window counts.

Measured on four labelled 3-ball runs (simulation/regularity_data): the two
regular runs score about 90 and 85, the two messy ones about 50. It separates
regular from messy juggling; it does not yet rank very messy below medium
messy.

Pure Python on purpose: CI runs these tests with pytest alone.

Usage:
    python shape_consistency.py            # score the labelled regularity runs
"""
import glob
import math
import os

from data_utils import parse_runs
from eval_new_watch import HP_B0, HP_B1, HP_B2, HP_A1, HP_A2, params_for_balls, simulate_watch

SAMPLE_PERIOD_MS = 40
WINDOW = 50          # samples compared, 2 s
MIN_LAG = 9          # 0.36 s
MAX_LAG = 35         # 1.4 s
HISTORY = WINDOW + MAX_LAG
SCORE_EVERY = 25     # one score per second
MAX_PENDING = 8      # scored windows waiting for a later catch to confirm them


class ShapeConsistency:
    def __init__(self):
        self._hist = [[0.0] * HISTORY for _ in range(3)]
        self._head = 0          # index the next sample is written to
        self._samples = 0
        self._hp = [[0.0, 0.0, 0.0, 0.0] for _ in range(3)]  # x1, x2, y1, y2
        self._pending_end = []
        self._pending_score = []
        self._run_sum = 0.0
        self._run_count = 0
        self._run_sums = []
        self._run_counts = []

    def add_sample(self, x, y, z, now_ms, run_active, has_first_catch, first_catch_ms):
        """Feed one raw sample (milli-g) after the detector has processed it."""
        for axis, v in enumerate((x, y, z)):
            s = self._hp[axis]
            out = HP_B0 * v + HP_B1 * s[0] + HP_B2 * s[1] - HP_A1 * s[2] - HP_A2 * s[3]
            s[1] = s[0]
            s[0] = float(v)
            s[3] = s[2]
            s[2] = out
            self._hist[axis][self._head] = out
        self._head = (self._head + 1) % HISTORY
        self._samples += 1

        if self._samples < HISTORY or self._samples % SCORE_EVERY != 0:
            return
        if not run_active or not has_first_catch:
            return
        start_ms = now_ms - (HISTORY - 1) * SAMPLE_PERIOD_MS
        if start_ms < first_catch_ms:
            return
        if len(self._pending_end) >= MAX_PENDING:
            self._pending_end.pop(0)
            self._pending_score.pop(0)
        self._pending_end.append(now_ms)
        self._pending_score.append(self._score())

    def _score(self):
        # Newest first: a[k] is the sample k steps back.
        a = [[self._hist[axis][(self._head - 1 - k) % HISTORY] for k in range(HISTORY)]
             for axis in range(3)]
        ax, ay, az = a
        e = [ax[k] * ax[k] + ay[k] * ay[k] + az[k] * az[k] for k in range(HISTORY)]
        e0 = sum(e[0:WINDOW])
        if e0 <= 0.0:
            return 0.0
        el = sum(e[MIN_LAG:MIN_LAG + WINDOW])
        best = 0.0
        for lag in range(MIN_LAG, MAX_LAG + 1):
            if lag > MIN_LAG:
                el += e[lag + WINDOW - 1] - e[lag - 1]
            cross = 0.0
            for k in range(WINDOW):
                j = k + lag
                cross += ax[k] * ax[j] + ay[k] * ay[j] + az[k] * az[j]
            denom = e0 * el
            if denom > 0.0:
                c = cross / math.sqrt(denom)
                if c > best:
                    best = c
        return min(best, 1.0)

    def on_catch(self, catch_ms):
        """A watch-hand catch at catch_ms confirms every window ending by then."""
        while self._pending_end and self._pending_end[0] <= catch_ms:
            self._run_sum += self._pending_score.pop(0)
            self._run_count += 1
            self._pending_end.pop(0)

    def commit_run(self):
        self._run_sums.append(self._run_sum)
        self._run_counts.append(self._run_count)
        self.clear_run()

    def clear_run(self):
        self._run_sum = 0.0
        self._run_count = 0
        self._pending_end = []
        self._pending_score = []

    def discard_last_run(self):
        if self._run_sums:
            self._run_sums.pop()
            self._run_counts.pop()

    def session_percent(self):
        count = sum(self._run_counts)
        if count == 0:
            return -1
        return int(math.floor(100.0 * sum(self._run_sums) / count + 0.5))


def session_percent_for_recording(run):
    """Replay one single-run recording as the watch would and return its score.

    The catch times come from the watch detector's reference port, so this
    sees exactly the run boundaries the watch does.
    """
    p = params_for_balls(run["meta"]["balls"])
    _, peaks = simulate_watch(
        run["x"], run["y"], run["z"], run["meta"]["balls"],
        p["threshold"], p["refractory_ms"], p["raw_gate"] > 0, p["raw_gate"],
        p["merge_window_ms"],
    )
    tracker = ShapeConsistency()
    catches_ms = [i * SAMPLE_PERIOD_MS for i in peaks]
    if not catches_ms:
        return -1
    first, last = catches_ms[0], catches_ms[-1]
    for i in range(len(run["x"])):
        now_ms = i * SAMPLE_PERIOD_MS
        # The run is live from the first catch until the last one; windows
        # past the last catch are never confirmed, so ending here is exact.
        tracker.add_sample(run["x"][i], run["y"][i], run["z"][i], now_ms,
                           first <= now_ms <= last + 2000, now_ms >= first, first)
        for c in catches_ms:
            if c == now_ms:
                tracker.on_catch(c)
    tracker.commit_run()
    return tracker.session_percent()


REGULARITY_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "regularity_data")


def load_regularity_runs():
    runs = []
    for path in sorted(glob.glob(os.path.join(REGULARITY_DIR, "*.csv"))):
        runs.extend(parse_runs(path))
    return runs


if __name__ == "__main__":
    for run in load_regularity_runs():
        m = run["meta"]
        print(f"{m['run']}  {m.get('regularity', '?'):16} {session_percent_for_recording(run):4d}")
