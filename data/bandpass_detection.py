"""
Bandpass filter + peak detection algorithm for juggling throw counting.

Approach:
1. Compute linear acceleration magnitude (same gravity removal as watch)
2. Bandpass filter to isolate throw frequency band (removes gravity drift + noise)
3. Peak detection with prominence (adapts to signal amplitude automatically)

Key advantage over raw threshold: bandpass removes the slow gravity adaptation
transient that causes missed early throws and the DC offset that varies per session.
"""
import math
import os
import glob
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.gridspec as gridspec
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

def compute_magnitude_raw(x_mg, y_mg, z_mg):
    """Compute raw acceleration magnitude (no gravity removal)."""
    mags = []
    for i in range(len(x_mg)):
        ax = x_mg[i] * MILLI_G_TO_MS2
        ay = y_mg[i] * MILLI_G_TO_MS2
        az = z_mg[i] * MILLI_G_TO_MS2
        mags.append(math.sqrt(ax*ax + ay*ay + az*az))
    return np.array(mags)

def compute_magnitude_gravity(x_mg, y_mg, z_mg):
    """Compute linear acceleration magnitude with dynamic gravity alpha (matches watch)."""
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


# ─── Algorithm 1: Bandpass + Prominence Peak Detection ─────────────────────
def bandpass_prominence_detect(x_mg, y_mg, z_mg,
                                low_hz=0.5, high_hz=4.0,
                                min_prominence=1.0, min_height=1.5,
                                refractory_ms=100):
    """
    1. Compute raw magnitude
    2. Bandpass filter (removes gravity DC + high-freq noise)
    3. Find peaks by prominence
    """
    raw_mag = compute_magnitude_raw(x_mg, y_mg, z_mg)
    
    # Bandpass filter
    sos = butter(2, [low_hz, high_hz], btype='bandpass', fs=SAMPLE_RATE, output='sos')
    filtered = sosfiltfilt(sos, raw_mag)
    
    # Take absolute value (we care about deviations in both directions)
    envelope = np.abs(filtered)
    
    # Find peaks with prominence
    min_distance = max(1, int(refractory_ms / 1000.0 * SAMPLE_RATE))
    peaks, props = find_peaks(envelope,
                              prominence=min_prominence,
                              height=min_height,
                              distance=min_distance)
    
    return len(peaks), peaks, envelope, filtered, raw_mag


# ─── Algorithm 2: Highpass on gravity-removed signal ───────────────────────
def highpass_gravity_detect(x_mg, y_mg, z_mg,
                            highpass_hz=0.3,
                            min_prominence=2.0, min_height=3.0,
                            refractory_ms=100):
    """
    1. Compute gravity-removed magnitude (like watch)
    2. Highpass filter to remove remaining low-freq drift
    3. Peak detection with prominence
    """
    mag = compute_magnitude_gravity(x_mg, y_mg, z_mg)
    
    # Highpass to remove drift from gravity estimation
    sos = butter(2, highpass_hz, btype='highpass', fs=SAMPLE_RATE, output='sos')
    filtered = sosfiltfilt(sos, mag)
    
    # Peak detection
    min_distance = max(1, int(refractory_ms / 1000.0 * SAMPLE_RATE))
    peaks, props = find_peaks(filtered,
                              prominence=min_prominence,
                              height=min_height,
                              distance=min_distance)
    
    return len(peaks), peaks, filtered, mag


# ─── Algorithm 3: Causal bandpass (watch-implementable) ────────────────────
def causal_bandpass_detect(x_mg, y_mg, z_mg,
                           low_hz=0.5, high_hz=4.0,
                           min_prominence=1.0, min_height=1.5,
                           refractory_ms=100):
    """
    Same as bandpass_prominence_detect but uses causal filter (sosfilt, not sosfiltfilt).
    This simulates what the watch would actually compute in real-time.
    """
    raw_mag = compute_magnitude_raw(x_mg, y_mg, z_mg)
    
    sos = butter(2, [low_hz, high_hz], btype='bandpass', fs=SAMPLE_RATE, output='sos')
    filtered = sosfilt(sos, raw_mag)
    
    envelope = np.abs(filtered)
    
    min_distance = max(1, int(refractory_ms / 1000.0 * SAMPLE_RATE))
    peaks, props = find_peaks(envelope,
                              prominence=min_prominence,
                              height=min_height,
                              distance=min_distance)
    
    return len(peaks), peaks, envelope, filtered, raw_mag


# ─── Algorithm 4: Per-axis bandpass then combine ──────────────────────────
def per_axis_bandpass_detect(x_mg, y_mg, z_mg,
                              low_hz=0.5, high_hz=4.0,
                              min_prominence=1.0, min_height=2.0,
                              refractory_ms=100):
    """
    Bandpass each axis independently, then combine into magnitude.
    This removes gravity from each axis via the highpass component.
    """
    sos = butter(2, [low_hz, high_hz], btype='bandpass', fs=SAMPLE_RATE, output='sos')
    
    x = np.array(x_mg, dtype=float) * MILLI_G_TO_MS2
    y = np.array(y_mg, dtype=float) * MILLI_G_TO_MS2
    z = np.array(z_mg, dtype=float) * MILLI_G_TO_MS2
    
    xf = sosfiltfilt(sos, x)
    yf = sosfiltfilt(sos, y)
    zf = sosfiltfilt(sos, z)
    
    # Magnitude of filtered axes
    mag = np.sqrt(xf**2 + yf**2 + zf**2)
    
    min_distance = max(1, int(refractory_ms / 1000.0 * SAMPLE_RATE))
    peaks, props = find_peaks(mag,
                              prominence=min_prominence,
                              height=min_height,
                              distance=min_distance)
    
    return len(peaks), peaks, mag, xf, yf, zf


# ─── Sweep helpers ─────────────────────────────────────────────────────────
def sweep_bandpass(runs):
    """Sweep bandpass + prominence parameters."""
    print("\n" + "="*80)
    print("BANDPASS + PROMINENCE SWEEP")
    print("="*80)
    
    best_error = float('inf')
    best_params = None
    best_results = None
    
    for low_hz in [0.3, 0.5, 0.7]:
        for high_hz in [3.0, 4.0, 5.0, 6.0]:
            for prom in [0.5, 1.0, 1.5, 2.0, 2.5, 3.0]:
                for height in [0.5, 1.0, 1.5, 2.0, 3.0]:
                    for refr in [80, 100, 150, 200]:
                        total_err = 0
                        results = []
                        for run in runs:
                            x, y, z = run['x'], run['y'], run['z']
                            actual = run['meta']['catches']
                            det, _, _, _, _ = bandpass_prominence_detect(
                                x, y, z, low_hz=low_hz, high_hz=high_hz,
                                min_prominence=prom, min_height=height,
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
                                'low_hz': low_hz, 'high_hz': high_hz,
                                'prominence': prom, 'height': height,
                                'refractory_ms': refr
                            }
                            best_results = results
    
    print(f"Best params: {best_params}")
    print(f"Total absolute error: {best_error}")
    for r in best_results:
        print(f"  {r['file']}: {r['balls']}b actual={r['actual']:3d} "
              f"detected={r['detected']:3d} error={r['error']:+d}")
    
    return best_params, best_error, best_results


def sweep_per_axis(runs):
    """Sweep per-axis bandpass parameters."""
    print("\n" + "="*80)
    print("PER-AXIS BANDPASS SWEEP")
    print("="*80)
    
    best_error = float('inf')
    best_params = None
    best_results = None
    
    for low_hz in [0.3, 0.5, 0.7]:
        for high_hz in [3.0, 4.0, 5.0, 6.0]:
            for prom in [0.5, 1.0, 1.5, 2.0, 3.0]:
                for height in [0.5, 1.0, 1.5, 2.0, 3.0]:
                    for refr in [80, 100, 150, 200]:
                        total_err = 0
                        results = []
                        for run in runs:
                            x, y, z = run['x'], run['y'], run['z']
                            actual = run['meta']['catches']
                            det, _, _, _, _, _ = per_axis_bandpass_detect(
                                x, y, z, low_hz=low_hz, high_hz=high_hz,
                                min_prominence=prom, min_height=height,
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
                                'low_hz': low_hz, 'high_hz': high_hz,
                                'prominence': prom, 'height': height,
                                'refractory_ms': refr
                            }
                            best_results = results
    
    print(f"Best params: {best_params}")
    print(f"Total absolute error: {best_error}")
    for r in best_results:
        print(f"  {r['file']}: {r['balls']}b actual={r['actual']:3d} "
              f"detected={r['detected']:3d} error={r['error']:+d}")
    
    return best_params, best_error, best_results


def sweep_causal(runs):
    """Sweep causal bandpass (watch-implementable) parameters."""
    print("\n" + "="*80)
    print("CAUSAL BANDPASS SWEEP (watch-implementable)")
    print("="*80)
    
    best_error = float('inf')
    best_params = None
    best_results = None
    
    for low_hz in [0.3, 0.5, 0.7]:
        for high_hz in [3.0, 4.0, 5.0, 6.0]:
            for prom in [0.5, 1.0, 1.5, 2.0, 2.5, 3.0]:
                for height in [0.5, 1.0, 1.5, 2.0, 3.0]:
                    for refr in [80, 100, 150, 200]:
                        total_err = 0
                        results = []
                        for run in runs:
                            x, y, z = run['x'], run['y'], run['z']
                            actual = run['meta']['catches']
                            det, _, _, _, _ = causal_bandpass_detect(
                                x, y, z, low_hz=low_hz, high_hz=high_hz,
                                min_prominence=prom, min_height=height,
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
                                'low_hz': low_hz, 'high_hz': high_hz,
                                'prominence': prom, 'height': height,
                                'refractory_ms': refr
                            }
                            best_results = results
    
    print(f"Best params: {best_params}")
    print(f"Total absolute error: {best_error}")
    for r in best_results:
        print(f"  {r['file']}: {r['balls']}b actual={r['actual']:3d} "
              f"detected={r['detected']:3d} error={r['error']:+d}")
    
    return best_params, best_error, best_results


def sweep_highpass(runs):
    """Sweep highpass on gravity-removed signal."""
    print("\n" + "="*80)
    print("HIGHPASS ON GRAVITY-REMOVED SWEEP")
    print("="*80)
    
    best_error = float('inf')
    best_params = None
    best_results = None
    
    for hp_hz in [0.2, 0.3, 0.5, 0.7]:
        for prom in [1.0, 2.0, 3.0, 4.0, 5.0]:
            for height in [1.0, 2.0, 3.0, 4.0, 5.0]:
                for refr in [80, 100, 150, 200]:
                    total_err = 0
                    results = []
                    for run in runs:
                        x, y, z = run['x'], run['y'], run['z']
                        actual = run['meta']['catches']
                        det, _, _, _ = highpass_gravity_detect(
                            x, y, z, highpass_hz=hp_hz,
                            min_prominence=prom, min_height=height,
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
                            'prominence': prom, 'height': height,
                            'refractory_ms': refr
                        }
                        best_results = results
    
    print(f"Best params: {best_params}")
    print(f"Total absolute error: {best_error}")
    for r in best_results:
        print(f"  {r['file']}: {r['balls']}b actual={r['actual']:3d} "
              f"detected={r['detected']:3d} error={r['error']:+d}")
    
    return best_params, best_error, best_results


# ─── Visualization ─────────────────────────────────────────────────────────
def plot_best(runs, params, algo_name, outpath):
    """Plot the best algorithm results."""
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
        
        if algo_name == 'bandpass':
            det, peaks, envelope, filtered, raw = bandpass_prominence_detect(
                x, y, z, **params)
            
            ax1 = axes[idx, 0]
            ax1.plot(t, raw, 'b-', alpha=0.3, linewidth=0.5, label='Raw magnitude')
            ax1.plot(t, filtered, 'r-', linewidth=0.8, label='Bandpass filtered')
            ax1.set_title(f'{run["file"]}: {balls}b | actual={actual} detected={det} '
                         f'(err={det-actual:+d})', fontweight='bold')
            ax1.set_xlabel('Time (s)')
            ax1.set_ylabel('Acceleration (m/s²)')
            ax1.legend(fontsize=8)
            
            ax2 = axes[idx, 1]
            ax2.plot(t, envelope, 'g-', linewidth=0.8, label='|Filtered|')
            if len(peaks) > 0:
                ax2.plot(peaks / SAMPLE_RATE, envelope[peaks], 'rv', 
                        markersize=6, label=f'Peaks ({len(peaks)})')
            ax2.axhline(y=params.get('min_height', 1.5), color='orange', 
                       linestyle=':', alpha=0.5, label='Min height')
            ax2.set_title(f'Envelope + detected peaks', fontweight='bold')
            ax2.set_xlabel('Time (s)')
            ax2.set_ylabel('|Filtered| (m/s²)')
            ax2.legend(fontsize=8)
            
        elif algo_name == 'per_axis':
            det, peaks, mag, xf, yf, zf = per_axis_bandpass_detect(
                x, y, z, **params)
            
            ax1 = axes[idx, 0]
            ax1.plot(t, xf, 'r-', alpha=0.5, linewidth=0.5, label='X filtered')
            ax1.plot(t, yf, 'g-', alpha=0.5, linewidth=0.5, label='Y filtered')
            ax1.plot(t, zf, 'b-', alpha=0.5, linewidth=0.5, label='Z filtered')
            ax1.set_title(f'{run["file"]}: {balls}b | actual={actual} detected={det} '
                         f'(err={det-actual:+d})', fontweight='bold')
            ax1.set_xlabel('Time (s)')
            ax1.legend(fontsize=8)
            
            ax2 = axes[idx, 1]
            ax2.plot(t, mag, 'g-', linewidth=0.8, label='Combined magnitude')
            if len(peaks) > 0:
                ax2.plot(peaks / SAMPLE_RATE, mag[peaks], 'rv',
                        markersize=6, label=f'Peaks ({len(peaks)})')
            ax2.set_title(f'Magnitude + peaks', fontweight='bold')
            ax2.set_xlabel('Time (s)')
            ax2.legend(fontsize=8)
        
        elif algo_name == 'causal':
            det, peaks, envelope, filtered, raw = causal_bandpass_detect(
                x, y, z, **params)
            
            ax1 = axes[idx, 0]
            ax1.plot(t, raw, 'b-', alpha=0.3, linewidth=0.5, label='Raw magnitude')
            ax1.plot(t, filtered, 'r-', linewidth=0.8, label='Causal bandpass')
            ax1.set_title(f'{run["file"]}: {balls}b | actual={actual} detected={det} '
                         f'(err={det-actual:+d})', fontweight='bold')
            ax1.set_xlabel('Time (s)')
            ax1.legend(fontsize=8)
            
            ax2 = axes[idx, 1]
            ax2.plot(t, envelope, 'g-', linewidth=0.8, label='|Filtered|')
            if len(peaks) > 0:
                ax2.plot(peaks / SAMPLE_RATE, envelope[peaks], 'rv',
                        markersize=6, label=f'Peaks ({len(peaks)})')
            ax2.axhline(y=params.get('min_height', 1.5), color='orange',
                       linestyle=':', alpha=0.5, label='Min height')
            ax2.set_title(f'Causal envelope + peaks', fontweight='bold')
            ax2.set_xlabel('Time (s)')
            ax2.legend(fontsize=8)
        
        elif algo_name == 'highpass':
            hp_kw = {
                'highpass_hz': params['highpass_hz'],
                'min_prominence': params['prominence'],
                'min_height': params['height'],
                'refractory_ms': params['refractory_ms']
            }
            det, peaks, filtered, mag = highpass_gravity_detect(x, y, z, **hp_kw)
            
            ax1 = axes[idx, 0]
            ax1.plot(t, mag, 'b-', alpha=0.4, linewidth=0.5, label='Gravity-removed mag')
            ax1.plot(t, filtered, 'r-', linewidth=0.8, label='+ Highpass filtered')
            ax1.set_title(f'{run["file"]}: {balls}b | actual={actual} detected={det} '
                         f'(err={det-actual:+d})', fontweight='bold')
            ax1.set_xlabel('Time (s)')
            ax1.set_ylabel('Acceleration (m/s²)')
            ax1.legend(fontsize=8)
            
            ax2 = axes[idx, 1]
            ax2.plot(t, filtered, 'g-', linewidth=0.8, label='Highpass filtered')
            if len(peaks) > 0:
                ax2.plot(peaks / SAMPLE_RATE, filtered[peaks], 'rv',
                        markersize=6, label=f'Peaks ({len(peaks)})')
            ax2.axhline(y=params['height'], color='orange',
                       linestyle=':', alpha=0.5, label=f'Min height ({params["height"]})')
            ax2.set_title(f'Filtered + detected peaks (prom≥{params["prominence"]})',
                         fontweight='bold')
            ax2.set_xlabel('Time (s)')
            ax2.set_ylabel('Acceleration (m/s²)')
            ax2.legend(fontsize=8)
    
    fig.suptitle(f'Algorithm: {algo_name} | Params: {params}', fontsize=12)
    plt.tight_layout()
    plt.savefig(outpath, dpi=150, bbox_inches='tight')
    plt.close()
    print(f"Saved to {outpath}")


if __name__ == '__main__':
    data_dir = os.path.dirname(__file__) or '.'
    
    # Load all runs
    all_runs = []
    for csvfile in sorted(glob.glob(os.path.join(data_dir, '*.csv'))):
        runs = parse_runs(csvfile)
        for r in runs:
            r['file'] = os.path.basename(csvfile)
            all_runs.append(r)
    
    print(f"Loaded {len(all_runs)} runs")
    for r in all_runs:
        print(f"  {r['file']}: {r['meta']['balls']}b, {r['meta']['catches']} catches, "
              f"{len(r['x'])} samples")
    
    # Run all sweeps
    bp_params, bp_err, _ = sweep_bandpass(all_runs)
    pa_params, pa_err, _ = sweep_per_axis(all_runs)
    causal_params, causal_err, _ = sweep_causal(all_runs)
    hp_params, hp_err, _ = sweep_highpass(all_runs)
    
    # Summary
    print("\n" + "="*80)
    print("SUMMARY")
    print("="*80)
    print(f"  Bandpass + prominence:     error={bp_err:3d}  {bp_params}")
    print(f"  Per-axis bandpass:         error={pa_err:3d}  {pa_params}")
    print(f"  Causal bandpass (watch):   error={causal_err:3d}  {causal_params}")
    print(f"  Highpass on gravity-rem:   error={hp_err:3d}  {hp_params}")
    print(f"  (Previous best threshold:  error= 14  threshold=15, hyst=0.7, refr=100)")
    
    # Plot the highpass results (best algorithm)
    print(f"\nBest algorithm: highpass on gravity-removed (error={hp_err})")
    outpath = os.path.join(data_dir, 'bandpass_detection.png')
    plot_best(all_runs, hp_params, 'highpass', outpath)
