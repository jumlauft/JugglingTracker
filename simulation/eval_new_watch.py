"""
Evaluate the adaptive watch algorithm against all recorded data.
Single-pass simulation matching JugglingDetector.mc exactly:
    - Sample-by-sample gravity removal with detection-state-dependent alpha
    - Sample-by-sample IIR highpass (causal, matching watch coefficients)
    - Threshold crossings create candidate events, not immediate catches
    - Nearby candidates are delayed and merged into one catch burst
    - The strongest filtered peak in a burst represents the catch motion
    - Every other committed burst is counted as a catch by the watch hand
"""
import math

from data_utils import (
    MILLI_G_TO_MS2, SAMPLE_RATE, WARMUP,
    GRAVITY_ALPHA_IDLE, GRAVITY_ALPHA_ACTIVE,
    load_all_runs,
)

# IIR highpass coefficients (must match watch code exactly)
# butter(2, 0.7, btype='highpass', fs=25, output='sos')
HP_B0 =  0.883002
HP_B1 = -1.766004
HP_B2 =  0.883002
HP_A1 = -1.752268  # negated in difference equation
HP_A2 =  0.779739  # negated in difference equation

HP_HYSTERESIS = 0.3

CURRENT_WATCH_PARAMS = {
    3: {'threshold': 2.6, 'refractory_ms': 80, 'raw_gate': 9.0, 'merge_window_ms': 120},
    4: {'threshold': 4.0, 'refractory_ms': 40, 'raw_gate': 0.0, 'merge_window_ms': 80},
    5: {'threshold': 0.5, 'refractory_ms': 80, 'raw_gate': 0.0, 'merge_window_ms': 280},
}


def _distance_from_current(params, balls):
    current = CURRENT_WATCH_PARAMS[min(balls, 5)]
    return sum(abs(params[key] - current[key]) for key in current)


def simulate_watch(x_mg, y_mg, z_mg, balls, threshold, refractory_ms,
                   use_raw_gate, raw_gate_val=None, merge_window_ms=0):
    """
    Exact single-pass simulation of the watch algorithm.
    Gravity alpha switches based on currentCount > 0 (not magnitude threshold).
    IIR highpass applied sample-by-sample (causal).
    Threshold crossings become candidates; candidates close together are
    clustered and committed after the signal leaves the cluster window. The
    returned count is watch-hand catches, modeled as odd-numbered committed
    bursts in the alternating hand sequence.
    """
    if raw_gate_val is None:
        raw_gate_val = 0.0
    n = len(x_mg)
    sample_period_ms = 1000.0 / SAMPLE_RATE  # 40 ms at 25 Hz
    low_threshold = threshold * HP_HYSTERESIS

    # Gravity estimate - seeded from first sample
    gx = x_mg[0] * MILLI_G_TO_MS2
    gy = y_mg[0] * MILLI_G_TO_MS2
    gz = z_mg[0] * MILLI_G_TO_MS2

    # IIR filter state
    hp_x1 = 0.0
    hp_x2 = 0.0
    hp_y1 = 0.0
    hp_y2 = 0.0

    # Detection state
    current_count = 0
    committed_bursts = 0
    above = False
    last_candidate_idx = -(refractory_ms / sample_period_ms + 1)  # ensure first candidate always passes
    peak_idx = 0
    peak_raw_mag = 0.0
    peak_filtered = 0.0
    peaks = []

    # Burst clustering state. A pending candidate is only counted after no
    # candidate in the same burst has appeared for merge_window_ms.
    pending_idx = None
    pending_score = 0.0
    cluster_last_idx = None

    def commit_pending():
        nonlocal current_count, committed_bursts, pending_idx, pending_score, cluster_last_idx
        if pending_idx is not None:
            committed_bursts += 1
            if committed_bursts % 2 == 1:
                peaks.append(pending_idx)
                current_count += 1
            pending_idx = None
            pending_score = 0.0
            cluster_last_idx = None

    def add_candidate(candidate_idx, score):
        nonlocal pending_idx, pending_score, cluster_last_idx
        if pending_idx is None:
            pending_idx = candidate_idx
            pending_score = score
            cluster_last_idx = candidate_idx
            return

        gap_ms = (candidate_idx - cluster_last_idx) * sample_period_ms
        if merge_window_ms > 0 and gap_ms < merge_window_ms:
            if score > pending_score:
                pending_idx = candidate_idx
                pending_score = score
            cluster_last_idx = candidate_idx
            return

        commit_pending()
        pending_idx = candidate_idx
        pending_score = score
        cluster_last_idx = candidate_idx

    for i in range(n):
        ax = x_mg[i] * MILLI_G_TO_MS2
        ay = y_mg[i] * MILLI_G_TO_MS2
        az = z_mg[i] * MILLI_G_TO_MS2

        # Gravity update (matches watch: alpha depends on committed or pending catch motions)
        if i > 0:
            active_run = current_count > 0 or pending_idx is not None or committed_bursts > 0
            alpha = GRAVITY_ALPHA_ACTIVE if active_run else GRAVITY_ALPHA_IDLE
            gx = alpha * gx + (1 - alpha) * ax
            gy = alpha * gy + (1 - alpha) * ay
            gz = alpha * gz + (1 - alpha) * az

        # Linear acceleration magnitude
        lx = ax - gx
        ly = ay - gy
        lz = az - gz
        mag = math.sqrt(lx * lx + ly * ly + lz * lz)

        # IIR highpass filter (sample-by-sample, matching watch)
        filtered = (HP_B0 * mag + HP_B1 * hp_x1 + HP_B2 * hp_x2
                    - HP_A1 * hp_y1 - HP_A2 * hp_y2)
        hp_x2 = hp_x1
        hp_x1 = mag
        hp_y2 = hp_y1
        hp_y1 = filtered

        # Skip warmup
        if i < WARMUP:
            continue

        # Threshold-crossing detection
        if not above:
            if filtered > threshold:
                above = True
                peak_idx = i
                peak_filtered = filtered
                peak_raw_mag = mag
        else:
            if filtered > peak_filtered:
                peak_filtered = filtered
                peak_idx = i
            if mag > peak_raw_mag:
                peak_raw_mag = mag
            if filtered < low_threshold:
                above = False
                passes_refractory = (peak_idx - last_candidate_idx) * sample_period_ms > refractory_ms
                passes_raw_gate = (not use_raw_gate) or (peak_raw_mag > raw_gate_val)
                if passes_refractory and passes_raw_gate:
                    add_candidate(peak_idx, peak_filtered)
                    last_candidate_idx = peak_idx

        if (pending_idx is not None and not above and merge_window_ms > 0 and
                (i - cluster_last_idx) * sample_period_ms >= merge_window_ms):
            commit_pending()

    commit_pending()

    return current_count, peaks


if __name__ == '__main__':
    runs = load_all_runs()
    print(f"Loaded {len(runs)} runs\n")

    # ── Parameter sweep for ball-count-adaptive params ──
    print("="*80)
    print("PARAMETER SWEEP: watch-hand catch threshold + refractory + raw gate + merge window")
    print("="*80)

    ball_counts = sorted(set(r['meta']['balls'] for r in runs))

    best_per_ball = {}
    for bc in ball_counts:
        bc_runs = [r for r in runs if r['meta']['balls'] == bc]
        bc_actual = sum(r['meta']['catches'] for r in bc_runs)
        print(f"\n--- {bc} balls ({len(bc_runs)} runs, {bc_actual} watch-hand catches) ---")

        best_key = (float('inf'), float('inf'), float('inf'))
        best_err = float('inf')
        best_over = float('inf')
        best_params = None
        for thresh in [0.3, 0.4, 0.5, 0.6, 0.8, 1.0, 1.5, 1.8, 2.0, 2.3, 2.6, 3.0, 3.5, 4.0]:
            for refr in [40, 80, 120, 160, 200, 240, 320, 400]:
                for raw_gate in [0.0, 3.0, 5.0, 7.0, 9.0]:
                    for merge_ms in [80, 120, 160, 200, 240, 280, 320, 400]:
                        total_err = 0
                        total_over = 0
                        for r in bc_runs:
                            det, _ = simulate_watch(r['x'], r['y'], r['z'], bc,
                                                    thresh, refr,
                                                    raw_gate > 0, raw_gate,
                                                    merge_ms)
                            actual = r['meta']['catches']
                            err = det - actual
                            total_err += abs(err)
                            total_over += max(0, err)
                        params = {'threshold': thresh, 'refractory_ms': refr,
                                  'raw_gate': raw_gate,
                                  'merge_window_ms': merge_ms}
                        key = (total_over, total_err, _distance_from_current(params, bc))
                        if key < best_key:
                            best_key = key
                            best_err = total_err
                            best_over = total_over
                            best_params = params
        best_per_ball[bc] = best_params
        print(f"  Best conservative: abs_error={best_err}, overcount={best_over}, params={best_params}")

        # Show per-run results with best params
        for r in bc_runs:
            p = best_params
            det, _ = simulate_watch(r['x'], r['y'], r['z'], bc,
                                    p['threshold'], p['refractory_ms'],
                                    p['raw_gate'] > 0, p['raw_gate'],
                                    p['merge_window_ms'])
            actual = r['meta']['catches']
            print(f"    {r['file']}: actual={actual:3d} det={det:3d} err={det-actual:+d}")

    # ── Overall comparison ──
    print(f"\n{'='*80}")
    print("OVERALL COMPARISON")
    print(f"{'='*80}")
    print(f"\n{'File':<30} {'Balls':>5} {'Actual':>6} {'Old':>6} {'Tuned':>6} {'OldErr':>7} {'TunedErr':>8}")
    print("-" * 85)

    old_total_abs = 0
    tuned_total_abs = 0
    for r in runs:
        balls = r['meta']['balls']
        actual = r['meta']['catches']
        fname = r['file']
        p = best_per_ball[balls]

        old_det, _ = simulate_watch(r['x'], r['y'], r['z'], balls,
                                     threshold=1.0, refractory_ms=200,
                                                                         use_raw_gate=False, merge_window_ms=0)
        tuned_det, _ = simulate_watch(r['x'], r['y'], r['z'], balls,
                                       p['threshold'], p['refractory_ms'],
                                                                             p['raw_gate'] > 0, p['raw_gate'],
                                                                             p['merge_window_ms'])

        old_err = old_det - actual
        tuned_err = tuned_det - actual

        old_total_abs += abs(old_err)
        tuned_total_abs += abs(tuned_err)

        print(f"{fname:<30} {balls:>5} {actual:>6} {old_det:>6} {tuned_det:>6} {old_err:>+7} {tuned_err:>+8}")

    print("-" * 85)
    print(f"Total absolute error:  old={old_total_abs}  tuned={tuned_total_abs}")
    print(f"\nSelected per-ball parameters:")
    for bc in sorted(best_per_ball.keys()):
        print(f"  {bc} balls: {best_per_ball[bc]}")
