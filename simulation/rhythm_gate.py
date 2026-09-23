"""NEGATIVE RESULT: low threshold plus a rhythm gate. Do not ship this.

Kept so the experiment is reproducible and is not attempted a third time.

The idea: the shipped detector uses one fixed threshold per ball count, and
lowering it drives the burst ratio to the theoretical 2.0 (one burst per throw
and per catch) because the bursts it misses are simply weak catches. The extra
bursts a low threshold admits would then be rejected on timing rather than
amplitude, juggling being strongly periodic.

Measured against 69 runs, per bucket, leave-one-out:

    bucket   runs   shipped   prototype LOO
    3        13     12        17    worse
    4        12     11        15    worse
    5-6      26     99        92    better
    7+       18     26        27    same
    total    69     148       151   worse

Why it fails: the spurious bursts a low threshold lets through are correctly
spaced, so timing cannot separate them from real ones. The gate only helps at
5 balls, by about 7 catches of error out of 753, which does not justify the
state and the mid-run lock-slip failure mode it would add on the watch.

What the experiment did establish, and what is worth keeping:

- A run opens with floor(N/2) throws that catch nothing, and ends with the same
  number of catches that throw nothing. They cancel, so counting every other
  burst needs no structural correction. Every offset model fitted here made
  things worse, which is the expected consequence.
- The opening phase is visible in the signal: at 7 balls it is a run of 200ms
  minimum-gap bursts before the rhythm settles. Usable as a per-session
  amplitude calibration window if that is ever wanted.
- The current fixed-threshold tuning is close to the practical ceiling for this
  front end. Further gains need a different feature, not better tuning: neither
  amplitude nor timing separates a spurious burst from a real catch.

Usage:
    python rhythm_gate.py            # compare against the shipped detector
    python rhythm_gate.py --sweep    # gate strength sweep
"""
import math
import statistics
import sys

from data_utils import (MILLI_G_TO_MS2, SAMPLE_RATE, WARMUP,
                        GRAVITY_ALPHA_IDLE, GRAVITY_ALPHA_ACTIVE, load_all_runs)
from eval_new_watch import simulate_watch, params_for_balls

HP_B0, HP_B1, HP_B2 = 0.883002, -1.766004, 0.883002
HP_A1, HP_A2 = -1.752268, 0.779739
HP_HYSTERESIS = 0.3

# Threshold low enough that the burst ratio reaches 2.0; the gate removes the
# extras this lets through.
# Refractory, gate and merge stay at the shipped values: only the threshold
# drops, to where the burst ratio reaches 2.0.
PROTOTYPE = {
    3: {"threshold": 1.5, "refractory_ms": 80, "raw_gate": 7.0, "merge_window_ms": 160},
    4: {"threshold": 2.0, "refractory_ms": 80, "raw_gate": 11.0, "merge_window_ms": 160},
    5: {"threshold": 2.0, "refractory_ms": 40, "raw_gate": 13.0, "merge_window_ms": 160},
    7: {"threshold": 1.5, "refractory_ms": 160, "raw_gate": 7.0, "merge_window_ms": 80},
}
GATE_FRAC = 0.55      # reject a burst closer than this fraction of the rhythm
GATE_ALPHA = 0.25     # how fast the rhythm estimate follows the run
GATE_WARMUP = 4       # bursts needed before the rhythm is trusted


def prototype_params(balls):
    if balls <= 3:
        return PROTOTYPE[3]
    if balls == 4:
        return PROTOTYPE[4]
    return PROTOTYPE[5] if balls <= 6 else PROTOTYPE[7]


def detect_bursts(x_mg, y_mg, z_mg, threshold, refractory_ms, raw_gate, merge_window_ms):
    """Front end only: committed burst times in ms. No counting rule."""
    n = len(x_mg)
    period = 1000.0 / SAMPLE_RATE
    low = threshold * HP_HYSTERESIS
    gx = x_mg[0] * MILLI_G_TO_MS2
    gy = y_mg[0] * MILLI_G_TO_MS2
    gz = z_mg[0] * MILLI_G_TO_MS2
    hx1 = hx2 = hy1 = hy2 = 0.0
    above = False
    last_cand = -(refractory_ms / period + 1)
    peak_i = 0
    peak_raw = 0.0
    peak_f = 0.0
    pending = None
    pending_score = 0.0
    cluster_last = None
    out = []
    use_gate = raw_gate > 0

    def commit():
        nonlocal pending, pending_score, cluster_last
        if pending is not None:
            out.append(pending * period)
            pending = None
            pending_score = 0.0
            cluster_last = None

    def add(i, score):
        nonlocal pending, pending_score, cluster_last
        if pending is None:
            pending, pending_score, cluster_last = i, score, i
            return
        if merge_window_ms > 0 and (i - cluster_last) * period < merge_window_ms:
            if score > pending_score:
                pending, pending_score = i, score
            cluster_last = i
            return
        commit()
        pending, pending_score, cluster_last = i, score, i

    for i in range(n):
        ax = x_mg[i] * MILLI_G_TO_MS2
        ay = y_mg[i] * MILLI_G_TO_MS2
        az = z_mg[i] * MILLI_G_TO_MS2
        if i > 0:
            alpha = (GRAVITY_ALPHA_ACTIVE if (out or pending is not None)
                     else GRAVITY_ALPHA_IDLE)
            gx = alpha * gx + (1 - alpha) * ax
            gy = alpha * gy + (1 - alpha) * ay
            gz = alpha * gz + (1 - alpha) * az
        lx, ly, lz = ax - gx, ay - gy, az - gz
        mag = math.sqrt(lx * lx + ly * ly + lz * lz)
        filtered = (HP_B0 * mag + HP_B1 * hx1 + HP_B2 * hx2
                    - HP_A1 * hy1 - HP_A2 * hy2)
        hx2, hx1 = hx1, mag
        hy2, hy1 = hy1, filtered
        if i < WARMUP:
            continue
        if not above:
            if filtered > threshold:
                above, peak_i, peak_f, peak_raw = True, i, filtered, mag
        else:
            if filtered > peak_f:
                peak_f, peak_i = filtered, i
            if mag > peak_raw:
                peak_raw = mag
            if filtered < low:
                above = False
                if ((peak_i - last_cand) * period > refractory_ms
                        and ((not use_gate) or peak_raw > raw_gate)):
                    add(peak_i, peak_f)
                    last_cand = peak_i
        if (pending is not None and not above and merge_window_ms > 0
                and (i - cluster_last) * period >= merge_window_ms):
            commit()
    commit()
    return out


def rhythm_gate(times, gate_frac=GATE_FRAC, alpha=GATE_ALPHA, warmup=GATE_WARMUP):
    """Drop bursts that arrive too soon for the rhythm the run has settled into.

    Spacing is compared against the burst two back rather than the last one.
    Consecutive bursts alternate catch-to-throw (the ball dwelling in the hand)
    and throw-to-catch (its flight), so consecutive gaps are unequal by
    construction and gating on them rejects every short one. Same-phase bursts
    are a full catch period apart, which is the rhythm worth locking to.
    """
    if len(times) < 3:
        return list(times)
    kept = [times[0], times[1]]
    spans = []
    period = None
    for t in times[2:]:
        span = t - kept[-2]
        if period is None:
            kept.append(t)
            spans.append(span)
            if len(spans) >= warmup:
                period = statistics.median(spans)
            continue
        if span < gate_frac * period:
            continue                      # duplicates a phase already counted
        kept.append(t)
        if 0.7 * period < span < 1.4 * period:
            period = (1 - alpha) * period + alpha * span
    return kept


def count_catches(times):
    """Every other burst: throws and catches alternate once the stream is clean."""
    return sum(1 for i in range(len(times)) if i % 2 == 0)


def evaluate(runs, gate_frac=GATE_FRAC):
    """Per-ball-count comparison of the shipped detector and this prototype."""
    from collections import defaultdict
    agg = defaultdict(lambda: [0, 0, 0, 0, 0, 0, []])
    for r in runs:
        b = r["meta"]["balls"]
        actual = r["meta"]["catches"]
        p = params_for_balls(b)
        shipped, _ = simulate_watch(
            r["x"], r["y"], r["z"], b, p["threshold"], p["refractory_ms"],
            p["raw_gate"] > 0, p["raw_gate"], p["merge_window_ms"])
        q = prototype_params(b)
        raw = detect_bursts(r["x"], r["y"], r["z"], q["threshold"],
                            q["refractory_ms"], q["raw_gate"], q["merge_window_ms"])
        gated = rhythm_gate(raw, gate_frac)
        proto = count_catches(gated)
        a = agg[b]
        a[0] += 1
        a[1] += actual
        a[2] += shipped
        a[3] += abs(shipped - actual)
        a[4] += proto
        a[5] += abs(proto - actual)
        if actual:
            a[6].append((len(raw) / actual, len(gated) / actual))
    return agg


def report(agg):
    print(f"{'balls':>5}{'runs':>5}{'actual':>8} | {'SHIPPED':>8}{'acc%':>7}{'err':>6}"
          f" | {'PROTO':>7}{'acc%':>7}{'err':>6} | {'ratio raw':>10}{'gated':>7}")
    print("-" * 82)
    tot = [0] * 6
    for b in sorted(agg):
        n, actual, shipped, serr, proto, perr, ratios = agg[b]
        rr = statistics.median([x for x, _ in ratios]) if ratios else 0
        rg = statistics.median([y for _, y in ratios]) if ratios else 0
        print(f"{b:>5}{n:>5}{actual:>8} | {shipped:>8}{100.0*shipped/actual:>7.1f}{serr:>6}"
              f" | {proto:>7}{100.0*proto/actual:>7.1f}{perr:>6} | {rr:>10.2f}{rg:>7.2f}")
        tot = [tot[0]+n, tot[1]+actual, tot[2]+shipped, tot[3]+serr, tot[4]+proto, tot[5]+perr]
    print("-" * 82)
    print(f"{'ALL':>5}{tot[0]:>5}{tot[1]:>8} | {tot[2]:>8}{100.0*tot[2]/tot[1]:>7.1f}{tot[3]:>6}"
          f" | {tot[4]:>7}{100.0*tot[4]/tot[1]:>7.1f}{tot[5]:>6}")
    return tot[3], tot[5]


if __name__ == "__main__":
    import os
    runs = load_all_runs(os.path.join(os.path.dirname(__file__), "..", "connectiq", "data"))
    print(f"{len(runs)} runs, {sum(r['meta']['catches'] for r in runs)} catches\n")
    shipped_err, proto_err = report(evaluate(runs))
    print(f"\nshipped abs error {shipped_err}   prototype abs error {proto_err}")

    if "--sweep" in sys.argv:
        print("\ngate_frac sweep (all ball counts):")
        for gf in (0.40, 0.45, 0.50, 0.55, 0.60, 0.65, 0.70):
            agg = evaluate(runs, gf)
            err = sum(v[5] for v in agg.values())
            print(f"  gate_frac={gf:.2f}  abs error={err}")
