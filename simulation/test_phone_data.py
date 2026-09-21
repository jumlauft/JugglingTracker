"""Simulate the Android PhoneJugglingDetector on phone-recorded accelerometer CSVs.

Exact Python port of
android/app/src/main/java/com/juggling/tracker/logic/PhoneJugglingDetector.kt
(200 Hz pipeline). Used to validate the Kotlin algorithm offline and to tune
its detection parameters.

Usage:
    python test_phone_data.py <csv_file> [csv_file ...]
    python test_phone_data.py --sweep <csv_file> [csv_file ...]
"""

import math
import sys

MILLI_G_TO_MS2 = 9.80665 / 1000.0
SAMPLE_PERIOD_MS = 5  # 200 Hz

# Default parameters (mirror PhoneJugglingDetector.kt companion object)
DEFAULT_PARAMS = {
    3: dict(hp_threshold=2.5, refractory_ms=40, min_raw_mag=7.0, merge_window_ms=60),
    4: dict(hp_threshold=2.5, refractory_ms=80, min_raw_mag=5.0, merge_window_ms=60),
    5: dict(hp_threshold=2.5, refractory_ms=40, min_raw_mag=7.0, merge_window_ms=60),
}

WARMUP_SAMPLES = 200
GRAVITY_ALPHA_IDLE = 0.99
GRAVITY_ALPHA_ACTIVE = 0.999
HP_B0 = 0.96716
HP_B1 = -1.93432
HP_B2 = 0.96716
HP_A1 = -1.93365
HP_A2 = 0.93547
HP_HYSTERESIS = 0.3
AUTO_FINISH_DELAY_MS = 2000


def params_for_balls(ball_count, overrides=None):
    key = 3 if ball_count <= 3 else (4 if ball_count == 4 else 5)
    p = dict(DEFAULT_PARAMS[key])
    if overrides and key in overrides:
        p.update(overrides[key])
    return p


class PhoneDetectorSim:
    def __init__(self, ball_count, params=None, odd_burst_rule=True):
        p = params or params_for_balls(ball_count)
        self.hp_threshold = p["hp_threshold"]
        self.refractory_ms = p["refractory_ms"]
        self.min_raw_mag = p["min_raw_mag"]
        self.merge_window_ms = p["merge_window_ms"]
        self.odd_burst_rule = odd_burst_rule

        self.current_count = 0
        self.gx = 0.0
        self.gy = 0.0
        self.gz = 9.80665
        self.gravity_initialized = False
        self.samples_seen = 0

        self.last_candidate_time = 0
        self.last_active_time = 0
        self.committed_burst_count = 0

        self.hp_x1 = self.hp_x2 = 0.0
        self.hp_y1 = self.hp_y2 = 0.0

        self.above = False
        self.peak_time = 0
        self.peak_raw_mag = 0.0
        self.peak_filtered = 0.0
        self.peak_samples = 0

        self.has_pending = False
        self.pending_time = 0
        self.pending_score = 0.0
        self.cluster_last_time = 0

    def _has_active_run(self):
        return self.current_count > 0 or self.has_pending or self.committed_burst_count > 0

    def _highpass(self, x):
        y = (HP_B0 * x + HP_B1 * self.hp_x1 + HP_B2 * self.hp_x2
             - HP_A1 * self.hp_y1 - HP_A2 * self.hp_y2)
        self.hp_x2 = self.hp_x1
        self.hp_x1 = x
        self.hp_y2 = self.hp_y1
        self.hp_y1 = y
        return y

    def process_sample(self, ax, ay, az, now_ms):
        if not self.gravity_initialized:
            self.gx, self.gy, self.gz = ax, ay, az
            self.gravity_initialized = True
        else:
            alpha = GRAVITY_ALPHA_ACTIVE if self._has_active_run() else GRAVITY_ALPHA_IDLE
            self.gx = alpha * self.gx + (1.0 - alpha) * ax
            self.gy = alpha * self.gy + (1.0 - alpha) * ay
            self.gz = alpha * self.gz + (1.0 - alpha) * az

        lx = ax - self.gx
        ly = ay - self.gy
        lz = az - self.gz

        g_mag_sq = self.gx * self.gx + self.gy * self.gy + self.gz * self.gz
        g_mag = math.sqrt(g_mag_sq)
        if g_mag > 1.0:
            upward = -(lx * self.gx + ly * self.gy + lz * self.gz) / g_mag
        else:
            upward = math.sqrt(lx * lx + ly * ly + lz * lz)

        self.samples_seen += 1
        mag = max(upward, 0.0)
        filtered = self._highpass(mag)

        if self.samples_seen <= WARMUP_SAMPLES:
            return

        if not self.above:
            if filtered > self.hp_threshold:
                self.above = True
                self.peak_time = now_ms
                self.peak_filtered = filtered
                self.peak_raw_mag = mag
                self.peak_samples = 1
        else:
            self.peak_samples += 1
            if filtered > self.peak_filtered:
                self.peak_filtered = filtered
                self.peak_time = now_ms
            if mag > self.peak_raw_mag:
                self.peak_raw_mag = mag
            if filtered < self.hp_threshold * HP_HYSTERESIS:
                self.above = False
                is_not_a_spike = self.peak_samples >= 3
                if (self.peak_time - self.last_candidate_time > self.refractory_ms
                        and self.peak_raw_mag > self.min_raw_mag
                        and is_not_a_spike):
                    self._add_candidate(self.peak_time, self.peak_filtered, now_ms)
                    self.last_candidate_time = self.peak_time

        self._flush_pending(now_ms)

    def _commit_pending(self, now_ms):
        if not self.has_pending:
            return
        self.committed_burst_count += 1
        if not self.odd_burst_rule or self.committed_burst_count % 2 == 1:
            self.current_count += 1
        self.last_active_time = now_ms
        self.has_pending = False
        self.pending_time = 0
        self.pending_score = 0.0
        self.cluster_last_time = 0

    def _add_candidate(self, t, score, now_ms):
        if not self.has_pending:
            self.has_pending = True
            self.pending_time = t
            self.pending_score = score
            self.cluster_last_time = t
            return
        if t - self.cluster_last_time < self.merge_window_ms:
            if score > self.pending_score:
                self.pending_time = t
                self.pending_score = score
            self.cluster_last_time = t
            return
        self._commit_pending(now_ms)
        self.has_pending = True
        self.pending_time = t
        self.pending_score = score
        self.cluster_last_time = t

    def _flush_pending(self, now_ms):
        if self.has_pending and not self.above and now_ms - self.cluster_last_time >= self.merge_window_ms:
            self._commit_pending(now_ms)

    def finish(self):
        if self.has_pending:
            self._commit_pending(self.pending_time)
        return self.current_count


def load_runs(path):
    """Parse a phone recording CSV that may contain multiple runs.

    Each run starts with a '# balls=...' header line followed by an
    'x,y,z' column header and raw milli-g integer rows.
    Returns list of dicts: {balls, catches, detected, samples:[(x,y,z)...]}.
    """
    runs = []
    current = None
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            if line.startswith("#"):
                meta = {}
                for part in line.lstrip("#").strip().split(","):
                    if "=" in part:
                        k, v = part.split("=", 1)
                        meta[k.strip()] = v.strip()
                current = {
                    "balls": int(meta.get("balls", 3)),
                    "catches": int(meta.get("catches", -1)),
                    "detected": int(meta.get("detected", -1)),
                    "sample_rate": int(meta.get("sampleRate", 200)),
                    "samples": [],
                }
                runs.append(current)
                continue
            if line.startswith("x,") or line[0].isalpha():
                continue
            if current is None:
                continue
            parts = line.split(",")
            if len(parts) != 3:
                continue
            current["samples"].append(tuple(int(p) for p in parts))
    return runs


def simulate_run(run, overrides=None, odd_burst_rule=True):
    params = params_for_balls(run["balls"], overrides)
    det = PhoneDetectorSim(run["balls"], params, odd_burst_rule=odd_burst_rule)
    for i, (x, y, z) in enumerate(run["samples"]):
        ax = x * MILLI_G_TO_MS2
        ay = y * MILLI_G_TO_MS2
        az = z * MILLI_G_TO_MS2
        det.process_sample(ax, ay, az, i * SAMPLE_PERIOD_MS)
    return det.finish()


def evaluate(runs, overrides=None, odd_burst_rule=True):
    rows = []
    for run in runs:
        sim = simulate_run(run, overrides, odd_burst_rule)
        rows.append((run["balls"], run["catches"], run["detected"], sim,
                     len(run["samples"])))
    return rows


def print_report(rows, label="current parameters"):
    print(f"\n=== Simulation with {label} ===")
    print(f"{'balls':>5} {'actual':>7} {'app':>5} {'sim':>5} {'err':>5} {'samples':>8}")
    total_actual = 0
    total_sim = 0
    total_abs_err = 0
    per_balls = {}
    for balls, actual, detected, sim, n in rows:
        err = sim - actual
        total_actual += actual
        total_sim += sim
        total_abs_err += abs(err)
        b = per_balls.setdefault(balls, [0, 0, 0])
        b[0] += actual
        b[1] += sim
        b[2] += abs(err)
        print(f"{balls:>5} {actual:>7} {detected:>5} {sim:>5} {err:>+5} {n:>8}")
    print("-" * 40)
    for balls in sorted(per_balls):
        a, s, e = per_balls[balls]
        acc = 100.0 * s / a if a else 0.0
        print(f"{balls}-ball: actual={a} sim={s} ({acc:.1f}%) abs_err={e}")
    acc = 100.0 * total_sim / total_actual if total_actual else 0.0
    print(f"TOTAL : actual={total_actual} sim={total_sim} ({acc:.1f}%) "
          f"abs_err={total_abs_err}")
    return total_abs_err


def cost(rows):
    """Total |error| with extra penalty for overcounting."""
    c = 0
    for _, actual, _, sim, _ in rows:
        err = sim - actual
        c += abs(err) + (2 * err if err > 0 else 0)
    return c


def sweep(runs):
    """Per-ball-count grid search over detection parameters."""
    best = {}
    for key in (3, 4, 5):
        subset = [r for r in runs
                  if (r["balls"] <= 3 and key == 3)
                  or (r["balls"] == 4 and key == 4)
                  or (r["balls"] >= 5 and key == 5)]
        if not subset:
            continue
        best_cost = None
        best_combo = None
        for hp in (0.5, 0.8, 1.0, 1.2, 1.5, 2.0, 2.5, 3.0, 4.0):
            for mrm in (0.0, 2.0, 4.0, 5.0, 7.0, 9.0, 11.0, 13.0):
                for refr in (40, 80, 120, 160, 240, 320):
                    for merge in (60, 80, 100, 120, 160, 200):
                        for odd in (True, False):
                            overrides = {key: dict(
                                hp_threshold=hp, min_raw_mag=mrm,
                                refractory_ms=refr, merge_window_ms=merge)}
                            rows = evaluate(subset, overrides, odd_burst_rule=odd)
                            c = cost(rows)
                            if best_cost is None or c < best_cost:
                                best_cost = c
                                best_combo = (hp, mrm, refr, merge, odd)
        hp, mrm, refr, merge, odd = best_combo
        actual = sum(r["catches"] for r in subset)
        best[key] = dict(hp_threshold=hp, min_raw_mag=mrm, refractory_ms=refr,
                         merge_window_ms=merge, odd_burst_rule=odd,
                         cost=best_cost, actual=actual)
        print(f"\n{key}-ball best: hp={hp} minRawMag={mrm} refractory={refr}ms "
              f"merge={merge}ms oddBurstRule={odd} cost={best_cost} "
              f"(actual={actual})")
    return best


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    do_sweep = "--sweep" in sys.argv
    if not args:
        print(__doc__)
        sys.exit(1)

    runs = []
    for path in args:
        runs.extend(load_runs(path))
    print(f"Loaded {len(runs)} runs from {len(args)} file(s)")

    rows = evaluate(runs)
    print_report(rows, "current parameters")

    if do_sweep:
        best = sweep(runs)
        overrides = {}
        odd_flags = {}
        for key, p in best.items():
            overrides[key] = dict(hp_threshold=p["hp_threshold"],
                                  min_raw_mag=p["min_raw_mag"],
                                  refractory_ms=p["refractory_ms"],
                                  merge_window_ms=p["merge_window_ms"])
            odd_flags[key] = p["odd_burst_rule"]
        # Report each ball-class with its own odd-burst setting
        print("\n=== Re-simulation with best parameters ===")
        all_rows = []
        for key in sorted(overrides):
            subset = [r for r in runs
                      if (r["balls"] <= 3 and key == 3)
                      or (r["balls"] == 4 and key == 4)
                      or (r["balls"] >= 5 and key == 5)]
            all_rows.extend(evaluate(subset, overrides, odd_burst_rule=odd_flags[key]))
        print_report(all_rows, "best parameters")


if __name__ == "__main__":
    main()
