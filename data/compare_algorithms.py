"""
Fair comparison: old threshold-only vs new highpass+threshold on all 17 runs.
Also tries hybrid: highpass filter + minimum raw magnitude gate.
"""
import math
import os
import glob
import numpy as np
from scipy.signal import butter, sosfilt

MILLI_G_TO_MS2 = 9.80665 / 1000.0
SAMPLE_RATE = 25

def parse_runs(filepath):
    runs = []
    current_run = None
    with open(filepath, 'r') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            if line.startswith('# '):
                meta = {}
                for part in line[2:].split(','):
                    k, v = part.split('=')
                    meta[k] = int(v) if v.lstrip('-').isdigit() else v
                current_run = {'meta': meta, 'x': [], 'y': [], 'z': []}
                runs.append(current_run)
            elif line == 'x,y,z':
                continue
            elif current_run is not None:
                parts = line.split(',')
                if len(parts) == 3:
                    current_run['x'].append(int(parts[0]))
                    current_run['y'].append(int(parts[1]))
                    current_run['z'].append(int(parts[2]))
    return runs


def compute_magnitude_gravity(x_mg, y_mg, z_mg):
    gx = x_mg[0] * MILLI_G_TO_MS2
    gy = y_mg[0] * MILLI_G_TO_MS2
    gz = z_mg[0] * MILLI_G_TO_MS2
    mags = []
    active = False
    for i in range(len(x_mg)):
        ax = x_mg[i] * MILLI_G_TO_MS2
        ay = y_mg[i] * MILLI_G_TO_MS2
        az = z_mg[i] * MILLI_G_TO_MS2
        if i > 0:
            alpha = 0.99 if active else 0.95
            gx = alpha * gx + (1 - alpha) * ax
            gy = alpha * gy + (1 - alpha) * ay
            gz = alpha * gz + (1 - alpha) * az
        lx = ax - gx
        ly = ay - gy
        lz = az - gz
        mag = math.sqrt(lx*lx + ly*ly + lz*lz)
        mags.append(mag)
        if mag > 5.0:
            active = True
    return np.array(mags)


def detect_threshold_only(x_mg, y_mg, z_mg, threshold=15, hysteresis_factor=0.7,
                          refractory_ms=100, smooth_window=3):
    """Old algorithm: gravity removal + moving average + threshold/hysteresis."""
    mag = compute_magnitude_gravity(x_mg, y_mg, z_mg)
    
    # Moving average smoothing
    if smooth_window > 1:
        kernel = np.ones(smooth_window) / smooth_window
        smoothed = np.convolve(mag, kernel, mode='same')
    else:
        smoothed = mag
    
    refractory_samples = int(refractory_ms / 1000.0 * SAMPLE_RATE)
    low_threshold = threshold * hysteresis_factor
    
    peaks = []
    above = False
    last_peak_idx = -refractory_samples
    peak_val = 0
    peak_idx = 0
    
    for i in range(len(smoothed)):
        v = smoothed[i]
        if not above:
            if v > threshold:
                above = True
                peak_val = v
                peak_idx = i
        else:
            if v > peak_val:
                peak_val = v
                peak_idx = i
            if v < low_threshold:
                above = False
                if peak_idx - last_peak_idx >= refractory_samples:
                    peaks.append(peak_idx)
                    last_peak_idx = peak_idx
    
    return len(peaks), peaks


def detect_highpass_threshold(x_mg, y_mg, z_mg, highpass_hz=0.8,
                              threshold=2.0, hysteresis_factor=0.6,
                              refractory_ms=200):
    """New algorithm: gravity removal + highpass filter + threshold/hysteresis."""
    mag = compute_magnitude_gravity(x_mg, y_mg, z_mg)
    
    sos = butter(2, highpass_hz, btype='highpass', fs=SAMPLE_RATE, output='sos')
    filtered = sosfilt(sos, mag)
    
    refractory_samples = int(refractory_ms / 1000.0 * SAMPLE_RATE)
    low_threshold = threshold * hysteresis_factor
    
    peaks = []
    above = False
    last_peak_idx = -refractory_samples
    peak_val = 0
    peak_idx = 0
    
    for i in range(len(filtered)):
        v = filtered[i]
        if not above:
            if v > threshold:
                above = True
                peak_val = v
                peak_idx = i
        else:
            if v > peak_val:
                peak_val = v
                peak_idx = i
            if v < low_threshold:
                above = False
                if peak_idx - last_peak_idx >= refractory_samples:
                    peaks.append(peak_idx)
                    last_peak_idx = peak_idx
    
    return len(peaks), peaks, filtered, mag


def detect_hybrid(x_mg, y_mg, z_mg, highpass_hz=0.8,
                  hp_threshold=2.0, hp_hysteresis=0.5,
                  min_raw_mag=3.0, refractory_ms=150):
    """
    Hybrid: highpass filter for clean peak detection + minimum raw magnitude gate.
    A peak only counts if both:
    - HP-filtered signal crosses threshold (clean peak detection)
    - Raw gravity-removed magnitude at peak > min_raw_mag (prevents noise triggers)
    """
    mag = compute_magnitude_gravity(x_mg, y_mg, z_mg)
    
    sos = butter(2, highpass_hz, btype='highpass', fs=SAMPLE_RATE, output='sos')
    filtered = sosfilt(sos, mag)
    
    refractory_samples = int(refractory_ms / 1000.0 * SAMPLE_RATE)
    low_threshold = hp_threshold * hp_hysteresis
    
    peaks = []
    above = False
    last_peak_idx = -refractory_samples
    peak_val = 0
    peak_idx = 0
    
    for i in range(len(filtered)):
        v = filtered[i]
        if not above:
            if v > hp_threshold:
                above = True
                peak_val = v
                peak_idx = i
        else:
            if v > peak_val:
                peak_val = v
                peak_idx = i
            if v < low_threshold:
                above = False
                if (peak_idx - last_peak_idx >= refractory_samples and
                    mag[peak_idx] > min_raw_mag):
                    peaks.append(peak_idx)
                    last_peak_idx = peak_idx
    
    return len(peaks), peaks, filtered, mag


def evaluate(runs, detect_fn, **kwargs):
    total_err = 0
    results = []
    for run in runs:
        x, y, z = run['x'], run['y'], run['z']
        actual = run['meta']['catches']
        result = detect_fn(x, y, z, **kwargs)
        det = result[0]
        err = det - actual
        total_err += abs(err)
        results.append({
            'file': run['file'],
            'balls': run['meta']['balls'],
            'actual': actual,
            'detected': det,
            'error': err
        })
    return results, total_err


def print_results(label, results, total_err, params=None):
    print(f"\n{'='*80}")
    print(f"{label}")
    if params:
        print(f"Params: {params}")
    print(f"{'='*80}")
    print(f"Total absolute error: {total_err}")
    for r in results:
        print(f"  {r['file']}: {r['balls']}b actual={r['actual']:3d} "
              f"detected={r['detected']:3d} error={r['error']:+d}")


if __name__ == '__main__':
    data_dir = os.path.dirname(__file__) or '.'
    all_runs = []
    for csvfile in sorted(glob.glob(os.path.join(data_dir, '*.csv'))):
        runs = parse_runs(csvfile)
        for r in runs:
            r['file'] = os.path.basename(csvfile)
            all_runs.append(r)
    
    print(f"Loaded {len(all_runs)} runs")
    total_catches = sum(r['meta']['catches'] for r in all_runs)
    print(f"Total actual catches: {total_catches}")
    
    # 1. Old threshold-only approach (sweep)
    print("\n" + "#"*80)
    print("SWEEPING OLD THRESHOLD-ONLY APPROACH")
    print("#"*80)
    best_err = float('inf')
    best_p = None
    for thresh in range(8, 20):
        for hyst in [0.5, 0.55, 0.6, 0.65, 0.7, 0.75]:
            for refr in [100, 150, 200]:
                for smooth in [1, 3, 5]:
                    results, err = evaluate(all_runs, detect_threshold_only,
                                           threshold=thresh, hysteresis_factor=hyst,
                                           refractory_ms=refr, smooth_window=smooth)
                    if err < best_err:
                        best_err = err
                        best_p = {'threshold': thresh, 'hysteresis': hyst,
                                 'refractory_ms': refr, 'smooth': smooth}
                        best_r = results
    print_results("BEST THRESHOLD-ONLY", best_r, best_err, best_p)
    
    # 2. Highpass + threshold (sweep wider range)
    print("\n" + "#"*80)
    print("SWEEPING CAUSAL HIGHPASS + THRESHOLD")
    print("#"*80)
    best_hp_err = float('inf')
    best_hp_p = None
    for hp_hz in [0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 1.0]:
        for thresh in [1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 5.0, 6.0]:
            for hyst in [0.3, 0.4, 0.5, 0.6, 0.7]:
                for refr in [80, 100, 120, 150, 200]:
                    results, err = evaluate(all_runs, detect_highpass_threshold,
                                           highpass_hz=hp_hz, threshold=thresh,
                                           hysteresis_factor=hyst, refractory_ms=refr)
                    if err < best_hp_err:
                        best_hp_err = err
                        best_hp_p = {'highpass_hz': hp_hz, 'threshold': thresh,
                                    'hysteresis_factor': hyst, 'refractory_ms': refr}
                        best_hp_r = results
    print_results("BEST HIGHPASS + THRESHOLD", best_hp_r, best_hp_err, best_hp_p)
    
    # 3. Hybrid (sweep)
    print("\n" + "#"*80)
    print("SWEEPING HYBRID (HP + RAW MAGNITUDE GATE)")
    print("#"*80)
    best_hy_err = float('inf')
    best_hy_p = None
    for hp_hz in [0.5, 0.6, 0.7, 0.8, 1.0]:
        for hp_thresh in [1.0, 1.5, 2.0, 2.5, 3.0, 4.0]:
            for hp_hyst in [0.3, 0.4, 0.5, 0.6]:
                for min_raw in [2.0, 3.0, 4.0, 5.0, 6.0]:
                    for refr in [100, 120, 150, 200]:
                        results, err = evaluate(all_runs, detect_hybrid,
                                               highpass_hz=hp_hz,
                                               hp_threshold=hp_thresh,
                                               hp_hysteresis=hp_hyst,
                                               min_raw_mag=min_raw,
                                               refractory_ms=refr)
                        if err < best_hy_err:
                            best_hy_err = err
                            best_hy_p = {'highpass_hz': hp_hz, 'hp_threshold': hp_thresh,
                                        'hp_hysteresis': hp_hyst, 'min_raw_mag': min_raw,
                                        'refractory_ms': refr}
                            best_hy_r = results
    print_results("BEST HYBRID", best_hy_r, best_hy_err, best_hy_p)
    
    # Summary
    print(f"\n{'='*80}")
    print("FINAL COMPARISON ({} runs, {} total catches)".format(len(all_runs), total_catches))
    print(f"{'='*80}")
    print(f"  Threshold-only:    error={best_err:3d}  (avg {best_err/len(all_runs):.1f}/run)  {best_p}")
    print(f"  Highpass+thresh:   error={best_hp_err:3d}  (avg {best_hp_err/len(all_runs):.1f}/run)  {best_hp_p}")
    print(f"  Hybrid:            error={best_hy_err:3d}  (avg {best_hy_err/len(all_runs):.1f}/run)  {best_hy_p}")
