"""
Sweep detection parameters against all recorded data to find optimal settings.
Tests different threshold / hysteresis / smoothing combinations and reports
accuracy for each.
"""
import math

from data_utils import (
    MILLI_G_TO_MS2, SAMPLE_RATE, WARMUP,
    load_all_runs,
)

def compute_signal(x_mg, y_mg, z_mg, gravity_alpha_idle, gravity_alpha_active, smooth_window,
                   throw_count=0):
    """Compute smoothed linear acceleration magnitude.
    
    throw_count: if > 0, use active alpha from the start (for visualization
    of runs where detection is already in progress). For accurate simulation,
    use detect_with_params which switches alpha dynamically.
    """
    gx = x_mg[0] * MILLI_G_TO_MS2
    gy = y_mg[0] * MILLI_G_TO_MS2
    gz = z_mg[0] * MILLI_G_TO_MS2
    
    raw_mags = []
    
    for i in range(len(x_mg)):
        ax = x_mg[i] * MILLI_G_TO_MS2
        ay = y_mg[i] * MILLI_G_TO_MS2
        az = z_mg[i] * MILLI_G_TO_MS2
        if i > 0:
            alpha = gravity_alpha_active if throw_count > 0 else gravity_alpha_idle
            gx = alpha * gx + (1 - alpha) * ax
            gy = alpha * gy + (1 - alpha) * ay
            gz = alpha * gz + (1 - alpha) * az
        lx = ax - gx
        ly = ay - gy
        lz = az - gz
        mag = math.sqrt(lx*lx + ly*ly + lz*lz)
        raw_mags.append(mag)
    
    # Smoothing
    smoothed = []
    for i in range(len(raw_mags)):
        if i < smooth_window - 1:
            smoothed.append(raw_mags[i])
        else:
            avg = sum(raw_mags[i - smooth_window + 1:i + 1]) / smooth_window
            smoothed.append(avg)
    
    return raw_mags, smoothed

def detect_with_params(x, y, z, ball_count, threshold, hysteresis_factor, 
                        refractory_base_ms, smooth_window,
                        gravity_alpha_idle=0.95, gravity_alpha_active=0.99,
                        count_per_peak=1):
    """Run detection with given parameters, matching watch behavior exactly.
    
    Gravity alpha switches from idle to active after the first peak is
    detected, just like the watch code does with currentCount > 0.
    Signal and detection are computed in a single pass.
    """
    refractory_ms = refractory_base_ms / (ball_count - 1)
    refractory_samples = max(1, int(refractory_ms / (1000 / SAMPLE_RATE)))
    
    # Gravity filter state
    gx = x[0] * MILLI_G_TO_MS2
    gy = y[0] * MILLI_G_TO_MS2
    gz = z[0] * MILLI_G_TO_MS2
    
    # Raw magnitude history for smoothing
    raw_hist = []
    
    # Peak detection state
    armed = True
    last_peak_idx = -refractory_samples - 1
    peaks = []
    prev_mag = 0.0
    prev_prev_mag = 0.0
    throw_count = 0
    
    for i in range(len(x)):
        ax = x[i] * MILLI_G_TO_MS2
        ay = y[i] * MILLI_G_TO_MS2
        az = z[i] * MILLI_G_TO_MS2
        
        if i > 0:
            # Switch alpha based on whether we've detected watch-hand catches.
            alpha = gravity_alpha_active if throw_count > 0 else gravity_alpha_idle
            gx = alpha * gx + (1 - alpha) * ax
            gy = alpha * gy + (1 - alpha) * ay
            gz = alpha * gz + (1 - alpha) * az
        
        lx = ax - gx
        ly = ay - gy
        lz = az - gz
        mag = math.sqrt(lx*lx + ly*ly + lz*lz)
        raw_hist.append(mag)
        
        # Smoothing (trailing window average)
        if len(raw_hist) < smooth_window:
            sm = mag
        else:
            sm = sum(raw_hist[-smooth_window:]) / smooth_window
        
        if i < WARMUP:
            prev_prev_mag = prev_mag
            prev_mag = mag
            continue
        
        if not armed and sm < threshold * hysteresis_factor:
            armed = True
        
        is_peak = (prev_mag >= mag and prev_mag >= prev_prev_mag and
                   sm > threshold and armed and
                   (i - last_peak_idx) > refractory_samples)
        
        if is_peak:
            peaks.append(i - 1)
            last_peak_idx = i
            armed = False
            throw_count += count_per_peak
        
        prev_prev_mag = prev_mag
        prev_mag = mag
    
    return throw_count, peaks


def detect_adaptive(x, y, z, ball_count, 
                     peak_prominence, smooth_window,
                     refractory_base_ms,
                     gravity_alpha_idle=0.95, gravity_alpha_active=0.99,
                     min_magnitude=5.0,
                     count_per_peak=1):
    """
    Adaptive detection: find local maxima with sufficient prominence.
    Single-pass with dynamic gravity alpha (matches watch behavior).
    """
    refractory_ms = refractory_base_ms / (ball_count - 1)
    refractory_samples = max(1, int(refractory_ms / (1000 / SAMPLE_RATE)))
    
    gx = x[0] * MILLI_G_TO_MS2
    gy = y[0] * MILLI_G_TO_MS2
    gz = z[0] * MILLI_G_TO_MS2
    
    raw_hist = []
    
    peaks = []
    last_peak_idx = -refractory_samples - 1
    last_peak_val = 0.0
    min_since_peak = float('inf')
    
    prev_mag = 0.0
    prev_prev_mag = 0.0
    throw_count = 0
    
    for i in range(len(x)):
        ax_v = x[i] * MILLI_G_TO_MS2
        ay_v = y[i] * MILLI_G_TO_MS2
        az_v = z[i] * MILLI_G_TO_MS2
        
        if i > 0:
            alpha = gravity_alpha_active if throw_count > 0 else gravity_alpha_idle
            gx = alpha * gx + (1 - alpha) * ax_v
            gy = alpha * gy + (1 - alpha) * ay_v
            gz = alpha * gz + (1 - alpha) * az_v
        
        lx = ax_v - gx
        ly = ay_v - gy
        lz = az_v - gz
        mag = math.sqrt(lx*lx + ly*ly + lz*lz)
        raw_hist.append(mag)
        
        if len(raw_hist) < smooth_window:
            sm = mag
        else:
            sm = sum(raw_hist[-smooth_window:]) / smooth_window
        
        if i < WARMUP:
            prev_prev_mag = prev_mag
            prev_mag = mag
            continue
        
        if sm < min_since_peak:
            min_since_peak = sm
        
        is_local_max = (prev_mag >= mag and prev_mag >= prev_prev_mag)
        has_prominence = (last_peak_val - min_since_peak) >= peak_prominence or len(peaks) == 0
        above_min = sm > min_magnitude
        past_refractory = (i - last_peak_idx) > refractory_samples
        
        if is_local_max and has_prominence and above_min and past_refractory:
            peaks.append(i - 1)
            last_peak_idx = i
            last_peak_val = sm
            min_since_peak = sm
            throw_count += count_per_peak
        
        prev_prev_mag = prev_mag
        prev_mag = mag
    
    return throw_count, peaks


def evaluate(runs, detect_fn, **params):
    """Evaluate a detection function across all runs. Returns total error."""
    total_error = 0
    total_abs_error = 0
    results = []
    for r in runs:
        actual = r['meta']['catches']
        detected, peaks = detect_fn(r['x'], r['y'], r['z'], r['meta']['balls'], **params)
        error = detected - actual
        results.append({
            'file': r['file'],
            'actual': actual,
            'detected': detected,
            'peaks': len(peaks),
            'error': error,
        })
        total_error += error
        total_abs_error += abs(error)
    return results, total_abs_error, total_error

def evaluate_per_ball(runs, detect_fn, params_by_balls, common_params=None):
    """Evaluate using different parameters for different ball counts."""
    total_abs_error = 0
    results = []
    for r in runs:
        actual = r['meta']['catches']
        balls = r['meta']['balls']
        params = params_by_balls.get(balls, params_by_balls.get('default', {}))
        if common_params:
            full_params = {**common_params, **params}
        else:
            full_params = params
        detected, peaks = detect_fn(r['x'], r['y'], r['z'], balls, **full_params)
        error = detected - actual
        results.append({
            'file': r['file'],
            'balls': balls,
            'actual': actual,
            'detected': detected,
            'peaks': len(peaks),
            'error': error,
        })
        total_abs_error += abs(error)
    return results, total_abs_error


if __name__ == '__main__':
    runs = load_all_runs()
    print(f"Loaded {len(runs)} runs:")
    for r in runs:
        m = r['meta']
        print(f"  {r['file']}: {m['balls']} balls, {m['catches']} catches, {len(r['x'])} samples")

    ball_counts = sorted(set(r['meta']['balls'] for r in runs))
    print(f"Ball counts in data: {ball_counts}")

    # ── Signal analysis ──
    print("\n" + "="*80)
    print("SIGNAL ANALYSIS")
    print("="*80)
    for r in runs:
        _, smoothed = compute_signal(r['x'], r['y'], r['z'], 0.95, 0.99, 3)
        active = [s for s in smoothed[WARMUP:] if s > 3.0]
        if active:
            m = r['meta']
            print(f"  {r['file']} ({m['balls']}b, {m['catches']} catches):")
            print(f"    Active region: min={min(active):.1f}, max={max(active):.1f}, "
                  f"mean={sum(active)/len(active):.1f}, median={sorted(active)[len(active)//2]:.1f}")

    # ── Legacy algorithm (fixed threshold=8.0 for all ball counts) ──
    print("\n" + "="*80)
    print("LEGACY ALGORITHM (threshold=8.0 for all)")
    print("="*80)
    results, total_abs, _ = evaluate(runs, detect_with_params,
        threshold=8.0,
        hysteresis_factor=0.6,
        refractory_base_ms=200,
        smooth_window=3)
    for r in results:
        print(f"  {r['file']}: {r.get('balls', '?')}b actual={r['actual']:3d}, detected={r['detected']:3d}, error={r['error']:+d}")
    print(f"  Total absolute error: {total_abs}")

    # ── Per-ball-count sweep ──
    print("\n" + "="*80)
    print("PER-BALL-COUNT THRESHOLD SWEEP")
    print("="*80)

    best_per_ball = {}
    for bc in ball_counts:
        bc_runs = [r for r in runs if r['meta']['balls'] == bc]
        print(f"\n--- {bc} balls ({len(bc_runs)} runs) ---")

        best_abs = float('inf')
        best_p = None

        for thresh in [4, 5, 6, 7, 8, 9, 10, 12, 14, 16, 18, 20, 22, 25]:
            for hyst in [0.3, 0.35, 0.4, 0.45, 0.5, 0.55, 0.6, 0.65, 0.7]:
                for refr in [100, 150, 200, 250, 300]:
                    for sw in [3, 5, 7]:
                        results, total_abs, _ = evaluate(bc_runs, detect_with_params,
                            threshold=thresh,
                            hysteresis_factor=hyst,
                            refractory_base_ms=refr,
                            smooth_window=sw)
                        if total_abs < best_abs:
                            best_abs = total_abs
                            best_p = {'threshold': thresh, 'hysteresis_factor': hyst,
                                      'refractory_base_ms': refr, 'smooth_window': sw}
                            best_r = results

        best_per_ball[bc] = (best_p, best_abs, best_r)
        print(f"  Best: {best_p}")
        print(f"  Total absolute error: {best_abs}")
        for r in best_r:
            print(f"    {r['file']}: actual={r['actual']:3d}, detected={r['detected']:3d}, error={r['error']:+d}")

    # ── Combined evaluation with per-ball params ──
    print("\n" + "="*80)
    print("COMBINED RESULT (per-ball-count params)")
    print("="*80)
    params_by_balls = {bc: best_per_ball[bc][0] for bc in ball_counts}
    results, total_abs = evaluate_per_ball(runs, detect_with_params, params_by_balls)
    for r in results:
        print(f"  {r['file']}: {r['balls']}b actual={r['actual']:3d}, detected={r['detected']:3d}, error={r['error']:+d}")
    print(f"  Total absolute error: {total_abs}")
    print(f"\nRecommended parameters by ball count:")
    for bc in ball_counts:
        p = params_by_balls[bc]
        print(f"  {bc} balls: threshold={p['threshold']}, hysteresis={p['hysteresis_factor']}, "
              f"refractory_base={p['refractory_base_ms']}ms, smooth={p['smooth_window']}")

    # ── Also try adaptive per-ball-count ──
    print("\n" + "="*80)
    print("PER-BALL-COUNT ADAPTIVE (PROMINENCE) SWEEP")
    print("="*80)

    best_per_ball_adp = {}
    for bc in ball_counts:
        bc_runs = [r for r in runs if r['meta']['balls'] == bc]
        print(f"\n--- {bc} balls ({len(bc_runs)} runs) ---")

        best_abs = float('inf')
        best_p = None

        for prom in [3, 5, 8, 10, 12, 15, 18, 20, 25]:
            for min_mag in [3, 5, 8, 10, 12, 15]:
                for refr in [100, 150, 200, 250, 300]:
                    for sw in [3, 5, 7]:
                        results, total_abs, _ = evaluate(bc_runs, detect_adaptive,
                            peak_prominence=prom,
                            smooth_window=sw,
                            refractory_base_ms=refr,
                            min_magnitude=min_mag)
                        if total_abs < best_abs:
                            best_abs = total_abs
                            best_p = {'peak_prominence': prom, 'min_magnitude': min_mag,
                                      'refractory_base_ms': refr, 'smooth_window': sw}
                            best_r = results

        best_per_ball_adp[bc] = (best_p, best_abs, best_r)
        print(f"  Best: {best_p}")
        print(f"  Total absolute error: {best_abs}")
        for r in best_r:
            print(f"    {r['file']}: actual={r['actual']:3d}, detected={r['detected']:3d}, error={r['error']:+d}")

    # ── Combined adaptive evaluation ──
    print("\n" + "="*80)
    print("COMBINED ADAPTIVE RESULT (per-ball-count params)")
    print("="*80)
    params_by_balls_adp = {bc: best_per_ball_adp[bc][0] for bc in ball_counts}
    results, total_abs = evaluate_per_ball(runs, detect_adaptive, params_by_balls_adp)
    for r in results:
        print(f"  {r['file']}: {r['balls']}b actual={r['actual']:3d}, detected={r['detected']:3d}, error={r['error']:+d}")
    print(f"  Total absolute error: {total_abs}")
    print(f"\nRecommended adaptive parameters by ball count:")
    for bc in ball_counts:
        p = params_by_balls_adp[bc]
        print(f"  {bc} balls: prominence={p['peak_prominence']}, min_mag={p['min_magnitude']}, "
              f"refractory_base={p['refractory_base_ms']}ms, smooth={p['smooth_window']}")
