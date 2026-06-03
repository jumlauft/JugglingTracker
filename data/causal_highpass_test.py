"""
Test causal (real-time implementable) highpass filter detection.
The watch processes samples one at a time - no looking ahead.

This script verifies the algorithm works with:
1. Causal IIR highpass filter (sosfilt, not sosfiltfilt)
2. Simple peak detection that doesn't need prominence (watch can't do that)
"""
import math
import os
import glob
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from scipy.signal import butter, sosfilt, sosfiltfilt, find_peaks

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
    """Compute linear acceleration magnitude with dynamic gravity alpha."""
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


def get_highpass_coefficients(cutoff_hz, fs, order=2):
    """Get IIR highpass filter coefficients (SOS form for numerical stability)."""
    sos = butter(order, cutoff_hz, btype='highpass', fs=fs, output='sos')
    return sos


def detect_causal_highpass(x_mg, y_mg, z_mg,
                           highpass_hz=0.5,
                           threshold=3.0,
                           hysteresis_factor=0.5,
                           refractory_ms=150):
    """
    Watch-implementable algorithm:
    1. Gravity removal via low-pass (existing watch code)
    2. Causal IIR highpass filter on magnitude (removes drift)
    3. Simple threshold + hysteresis peak detection (existing pattern)
    
    The highpass filter is the key innovation - it removes the slow drift
    from gravity estimation errors, making peaks much cleaner.
    """
    # Step 1: gravity-removed magnitude (same as watch)
    mag = compute_magnitude_gravity(x_mg, y_mg, z_mg)
    
    # Step 2: causal highpass filter
    sos = get_highpass_coefficients(highpass_hz, SAMPLE_RATE)
    filtered = sosfilt(sos, mag)
    
    # Step 3: simple threshold + hysteresis peak detection (like watch)
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


def detect_causal_highpass_prominence(x_mg, y_mg, z_mg,
                                       highpass_hz=0.5,
                                       min_prominence=4.0,
                                       min_height=3.0,
                                       refractory_ms=150):
    """
    Same but using scipy find_peaks with prominence on the causal-filtered signal.
    This is the "ideal causal" version - filter is causal but peak detection has lookahead.
    """
    mag = compute_magnitude_gravity(x_mg, y_mg, z_mg)
    sos = get_highpass_coefficients(highpass_hz, SAMPLE_RATE)
    filtered = sosfilt(sos, mag)
    
    min_distance = max(1, int(refractory_ms / 1000.0 * SAMPLE_RATE))
    peaks, props = find_peaks(filtered,
                              prominence=min_prominence,
                              height=min_height,
                              distance=min_distance)
    
    return len(peaks), peaks, filtered, mag


# ─── Sweep ─────────────────────────────────────────────────────────────────
def sweep_causal_threshold(runs):
    """Sweep causal highpass + threshold/hysteresis detection."""
    print("\n" + "="*80)
    print("CAUSAL HIGHPASS + THRESHOLD/HYSTERESIS (watch-implementable)")
    print("="*80)
    
    best_error = float('inf')
    best_params = None
    best_results = None
    
    for hp_hz in [0.3, 0.4, 0.5, 0.6, 0.7, 0.8]:
        for thresh in [2.0, 2.5, 3.0, 3.5, 4.0, 4.5, 5.0, 6.0, 7.0, 8.0]:
            for hyst in [0.3, 0.4, 0.5, 0.6, 0.7]:
                for refr in [80, 100, 120, 150, 200]:
                    total_err = 0
                    results = []
                    for run in runs:
                        x, y, z = run['x'], run['y'], run['z']
                        actual = run['meta']['catches']
                        det, _, _, _ = detect_causal_highpass(
                            x, y, z,
                            highpass_hz=hp_hz,
                            threshold=thresh,
                            hysteresis_factor=hyst,
                            refractory_ms=refr)
                        err = det - actual
                        total_err += abs(err)
                        results.append({
                            'file': run['file'],
                            'balls': run['meta']['balls'],
                            'actual': actual,
                            'detected': det,
                            'error': err
                        })
                    if total_err < best_error:
                        best_error = total_err
                        best_params = {
                            'highpass_hz': hp_hz,
                            'threshold': thresh,
                            'hysteresis_factor': hyst,
                            'refractory_ms': refr
                        }
                        best_results = results
    
    print(f"Best params: {best_params}")
    print(f"Total absolute error: {best_error}")
    for r in best_results:
        print(f"  {r['file']}: {r['balls']}b actual={r['actual']:3d} "
              f"detected={r['detected']:3d} error={r['error']:+d}")
    return best_params, best_error, best_results


def sweep_causal_prominence(runs):
    """Sweep causal highpass + prominence-based detection."""
    print("\n" + "="*80)
    print("CAUSAL HIGHPASS + PROMINENCE (filter causal, peaks use lookahead)")
    print("="*80)
    
    best_error = float('inf')
    best_params = None
    best_results = None
    
    for hp_hz in [0.3, 0.4, 0.5, 0.6, 0.7, 0.8]:
        for prom in [2.0, 3.0, 4.0, 5.0, 6.0]:
            for height in [1.0, 2.0, 3.0, 4.0, 5.0]:
                for refr in [80, 100, 120, 150, 200]:
                    total_err = 0
                    results = []
                    for run in runs:
                        x, y, z = run['x'], run['y'], run['z']
                        actual = run['meta']['catches']
                        det, _, _, _ = detect_causal_highpass_prominence(
                            x, y, z,
                            highpass_hz=hp_hz,
                            min_prominence=prom,
                            min_height=height,
                            refractory_ms=refr)
                        err = det - actual
                        total_err += abs(err)
                        results.append({
                            'file': run['file'],
                            'balls': run['meta']['balls'],
                            'actual': actual,
                            'detected': det,
                            'error': err
                        })
                    if total_err < best_error:
                        best_error = total_err
                        best_params = {
                            'highpass_hz': hp_hz,
                            'min_prominence': prom,
                            'min_height': height,
                            'refractory_ms': refr
                        }
                        best_results = results
    
    print(f"Best params: {best_params}")
    print(f"Total absolute error: {best_error}")
    for r in best_results:
        print(f"  {r['file']}: {r['balls']}b actual={r['actual']:3d} "
              f"detected={r['detected']:3d} error={r['error']:+d}")
    return best_params, best_error, best_results


def sweep_noncausal_reference(runs):
    """Non-causal (sosfiltfilt) as reference upper bound."""
    from scipy.signal import sosfiltfilt as filtfilt
    print("\n" + "="*80)
    print("NON-CAUSAL HIGHPASS + PROMINENCE (reference upper bound)")
    print("="*80)
    
    best_error = float('inf')
    best_params = None
    best_results = None
    
    for hp_hz in [0.3, 0.4, 0.5, 0.6, 0.7, 0.8]:
        for prom in [2.0, 3.0, 4.0, 5.0, 6.0]:
            for height in [1.0, 2.0, 3.0, 4.0, 5.0]:
                for refr in [80, 100, 120, 150, 200]:
                    total_err = 0
                    results = []
                    for run in runs:
                        x, y, z = run['x'], run['y'], run['z']
                        actual = run['meta']['catches']
                        mag = compute_magnitude_gravity(x, y, z)
                        sos = get_highpass_coefficients(hp_hz, SAMPLE_RATE)
                        filtered = filtfilt(sos, mag)
                        min_distance = max(1, int(refr / 1000.0 * SAMPLE_RATE))
                        peaks, _ = find_peaks(filtered, prominence=prom,
                                             height=height, distance=min_distance)
                        det = len(peaks)
                        err = det - actual
                        total_err += abs(err)
                        results.append({
                            'file': run['file'],
                            'balls': run['meta']['balls'],
                            'actual': actual,
                            'detected': det,
                            'error': err
                        })
                    if total_err < best_error:
                        best_error = total_err
                        best_params = {
                            'highpass_hz': hp_hz,
                            'min_prominence': prom,
                            'min_height': height,
                            'refractory_ms': refr
                        }
                        best_results = results
    
    print(f"Best params: {best_params}")
    print(f"Total absolute error: {best_error}")
    for r in best_results:
        print(f"  {r['file']}: {r['balls']}b actual={r['actual']:3d} "
              f"detected={r['detected']:3d} error={r['error']:+d}")
    return best_params, best_error, best_results


# ─── Plot ──────────────────────────────────────────────────────────────────
def plot_comparison(runs, causal_params, outpath):
    """Plot causal detection results."""
    n = len(runs)
    fig, axes = plt.subplots(n, 2, figsize=(18, 4*n))
    if n == 1:
        axes = axes.reshape(1, -1)
    
    for idx, run in enumerate(runs):
        x, y, z = run['x'], run['y'], run['z']
        meta = run['meta']
        actual = meta['catches']
        balls = meta['balls']
        t = np.arange(len(x)) / SAMPLE_RATE
        
        det, peaks, filtered, mag = detect_causal_highpass(x, y, z, **causal_params)
        
        ax1 = axes[idx, 0]
        ax1.plot(t, mag, 'b-', alpha=0.3, linewidth=0.5, label='Gravity-removed')
        ax1.plot(t, filtered, 'r-', linewidth=0.8, label='+ Causal highpass')
        ax1.axhline(y=causal_params['threshold'], color='green', linestyle='--', 
                    alpha=0.5, label=f'Threshold ({causal_params["threshold"]})')
        ax1.axhline(y=causal_params['threshold']*causal_params['hysteresis_factor'],
                    color='orange', linestyle=':', alpha=0.5, label='Hysteresis')
        ax1.set_title(f'{run["file"]}: {balls}b | actual={actual} detected={det} '
                     f'(err={det-actual:+d})', fontweight='bold')
        ax1.set_xlabel('Time (s)')
        ax1.set_ylabel('m/s²')
        ax1.legend(fontsize=7)
        
        ax2 = axes[idx, 1]
        ax2.plot(t, filtered, 'r-', linewidth=0.8, label='Causal highpass')
        if len(peaks) > 0:
            ax2.plot(np.array(peaks) / SAMPLE_RATE, filtered[peaks], 'gv',
                    markersize=8, label=f'Detected ({len(peaks)})')
        ax2.axhline(y=causal_params['threshold'], color='green', linestyle='--', alpha=0.5)
        ax2.axhline(y=causal_params['threshold']*causal_params['hysteresis_factor'],
                    color='orange', linestyle=':', alpha=0.5)
        ax2.set_title(f'Peaks on filtered signal', fontweight='bold')
        ax2.set_xlabel('Time (s)')
        ax2.set_ylabel('m/s²')
        ax2.legend(fontsize=8)
    
    p = causal_params
    fig.suptitle(f'Causal Highpass + Threshold: hp={p["highpass_hz"]}Hz, '
                 f'thresh={p["threshold"]}, hyst={p["hysteresis_factor"]}, '
                 f'refr={p["refractory_ms"]}ms', fontsize=12)
    plt.tight_layout()
    plt.savefig(outpath, dpi=150, bbox_inches='tight')
    plt.close()
    print(f"Saved to {outpath}")


# ─── Print IIR coefficients for watch implementation ───────────────────────
def print_watch_coefficients(highpass_hz):
    """Print the IIR filter coefficients needed for watch implementation."""
    sos = get_highpass_coefficients(highpass_hz, SAMPLE_RATE, order=2)
    print(f"\n{'='*80}")
    print(f"IIR HIGHPASS FILTER COEFFICIENTS (cutoff={highpass_hz} Hz, fs={SAMPLE_RATE} Hz, order=2)")
    print(f"{'='*80}")
    print(f"SOS sections (each row: [b0, b1, b2, a0, a1, a2]):")
    for i, section in enumerate(sos):
        print(f"  Section {i}: {section}")
    
    # For a 2nd-order Butterworth, there's 1 SOS section
    # y[n] = b0*x[n] + b1*x[n-1] + b2*x[n-2] - a1*y[n-1] - a2*y[n-2]
    # (a0 is always 1.0)
    if len(sos) == 1:
        b0, b1, b2, a0, a1, a2 = sos[0]
        print(f"\n  Monkey C implementation:")
        print(f"  // IIR Highpass filter coefficients")
        print(f"  const HP_B0 = {b0:.10f};")
        print(f"  const HP_B1 = {b1:.10f};")
        print(f"  const HP_B2 = {b2:.10f};")
        print(f"  const HP_A1 = {a1:.10f};  // note: negated in difference equation")
        print(f"  const HP_A2 = {a2:.10f};  // note: negated in difference equation")
        print(f"\n  // Filter state variables (initialize to 0)")
        print(f"  var _hp_x1 = 0.0;  // x[n-1]")
        print(f"  var _hp_x2 = 0.0;  // x[n-2]")
        print(f"  var _hp_y1 = 0.0;  // y[n-1]")
        print(f"  var _hp_y2 = 0.0;  // y[n-2]")
        print(f"\n  // Apply filter to each sample:")
        print(f"  // y = HP_B0*x + HP_B1*_hp_x1 + HP_B2*_hp_x2 - HP_A1*_hp_y1 - HP_A2*_hp_y2;")
        print(f"  // _hp_x2 = _hp_x1; _hp_x1 = x;")
        print(f"  // _hp_y2 = _hp_y1; _hp_y1 = y;")


if __name__ == '__main__':
    data_dir = os.path.dirname(__file__) or '.'
    
    all_runs = []
    for csvfile in sorted(glob.glob(os.path.join(data_dir, '*.csv'))):
        runs = parse_runs(csvfile)
        for r in runs:
            r['file'] = os.path.basename(csvfile)
            all_runs.append(r)
    
    print(f"Loaded {len(all_runs)} runs")
    
    # Run sweeps
    ct_params, ct_err, _ = sweep_causal_threshold(all_runs)
    cp_params, cp_err, _ = sweep_causal_prominence(all_runs)
    nc_params, nc_err, _ = sweep_noncausal_reference(all_runs)
    
    # Summary
    print(f"\n{'='*80}")
    print("SUMMARY")
    print(f"{'='*80}")
    print(f"  Causal HP + threshold/hyst:  error={ct_err:3d}  {ct_params}")
    print(f"  Causal HP + prominence:      error={cp_err:3d}  {cp_params}")
    print(f"  Non-causal HP + prominence:  error={nc_err:3d}  {nc_params}  (reference)")
    print(f"  Previous best (no filter):   error= 14  threshold=15, hyst=0.7, refr=100")
    
    # Print coefficients for watch
    print_watch_coefficients(ct_params['highpass_hz'])
    
    # Plot
    outpath = os.path.join(data_dir, 'causal_highpass_detection.png')
    plot_comparison(all_runs, ct_params, outpath)
