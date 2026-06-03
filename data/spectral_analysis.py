"""
Spectral analysis of juggling accelerometer data.
Explores FFT, autocorrelation, and frequency-based throw counting.
"""
import math
import os
import sys
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.gridspec as gridspec

MILLI_G_TO_MS2 = 9.80665 / 1000.0
SAMPLE_RATE = 25
WARMUP = 25

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

def compute_magnitude(x_mg, y_mg, z_mg):
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
        if mag > 5.0:  # rough activity detection
            active = True
    return np.array(mags)

def find_active_region(mags, threshold=5.0, margin_samples=10):
    """Find start and end of active juggling region using energy windowing."""
    # Use a sliding window energy measure
    window = SAMPLE_RATE  # 1-second window
    n = len(mags)
    if n < window:
        return WARMUP, n
    
    # Compute windowed RMS energy
    energy = np.zeros(n)
    for i in range(n):
        start = max(0, i - window // 2)
        end = min(n, i + window // 2)
        energy[i] = np.sqrt(np.mean(mags[start:end] ** 2))
    
    # Active = energy above threshold
    active = energy > threshold
    indices = np.where(active)[0]
    if len(indices) < margin_samples:
        return WARMUP, n
    
    # Trim to active region with small margin
    return max(indices[0], WARMUP), min(indices[-1] + 1, n)

def autocorrelation_period(signal, min_period_samples=5, max_period_samples=None):
    """Find dominant period using autocorrelation on DC-removed signal."""
    n = len(signal)
    if max_period_samples is None:
        max_period_samples = n // 3
    if n < max_period_samples * 2:
        max_period_samples = n // 2
    
    # Subtract mean (remove DC) and normalize
    sig = signal - np.mean(signal)
    var = np.var(sig)
    if var < 1e-6:
        return None, None
    
    # Compute normalized autocorrelation
    autocorr = []
    for lag in range(min_period_samples, max_period_samples + 1):
        c = np.sum(sig[:n-lag] * sig[lag:]) / ((n - lag) * var)
        autocorr.append((lag, c))
    
    if not autocorr:
        return None, None
    
    # Find the first significant peak in autocorrelation
    # (fundamental period = first peak after the initial decay)
    best_lag = None
    best_val = -1
    for i in range(1, len(autocorr) - 1):
        lag, val = autocorr[i]
        prev_val = autocorr[i-1][1]
        next_val = autocorr[i+1][1]
        if val > prev_val and val > next_val and val > 0.15 and val > best_val:
            if best_lag is None:  # Take first significant peak
                best_lag = lag
                best_val = val
                break
    
    return best_lag, autocorr

def frequency_based_count(mags, ball_count):
    """
    Count throws using frequency analysis:
    1. Find active region
    2. Determine dominant juggling frequency via autocorrelation
    3. catches = frequency * duration * 2
    """
    start, end = find_active_region(mags, threshold=3.0)
    active_signal = mags[start:end]
    
    if len(active_signal) < SAMPLE_RATE:  # Less than 1 second
        return 0, start, end, None, None
    
    # Find period via autocorrelation
    # Expected: 3-ball cascade ~0.75 Hz per hand → period ~33 samples
    #           5-ball cascade ~0.85 Hz per hand → period ~29 samples
    # Range: 0.5 to 4 Hz → period 6-50 samples at 25 Hz
    period_samples, autocorr = autocorrelation_period(
        active_signal, 
        min_period_samples=5,    # max ~5 Hz
        max_period_samples=50    # min ~0.5 Hz
    )
    
    if period_samples is None:
        return 0, start, end, None, autocorr
    
    frequency = SAMPLE_RATE / period_samples  # Hz
    duration = len(active_signal) / SAMPLE_RATE  # seconds
    
    # Each cycle of the signal = one throw from the monitored arm
    # Multiply by 2 for both arms
    raw_count = frequency * duration * 2
    catches = round(raw_count)
    
    return catches, start, end, frequency, autocorr


def load_all_runs():
    data_dir = os.path.dirname(__file__)
    all_runs = []
    for csvfile in sorted(glob.glob(os.path.join(data_dir, '*.csv'))):
        runs = parse_runs(csvfile)
        for r in runs:
            r['file'] = os.path.basename(csvfile)
            all_runs.append(r)
    return all_runs

import glob

if __name__ == '__main__':
    data_dir = os.path.dirname(__file__) or '.'
    all_runs = []
    for csvfile in sorted(glob.glob(os.path.join(data_dir, '*.csv'))):
        runs = parse_runs(csvfile)
        for r in runs:
            r['file'] = os.path.basename(csvfile)
            all_runs.append(r)
    
    print(f"Loaded {len(all_runs)} runs")
    
    n_runs = len(all_runs)
    fig = plt.figure(figsize=(20, 8 * n_runs))
    
    total_abs_error = 0
    
    for idx, run in enumerate(all_runs):
        meta = run['meta']
        x, y, z = run['x'], run['y'], run['z']
        balls = meta['balls']
        actual = meta['catches']
        
        mags = compute_magnitude(x, y, z)
        t = np.arange(len(mags)) / SAMPLE_RATE
        
        # Frequency analysis
        detected, start, end, freq, autocorr = frequency_based_count(mags, balls)
        error = detected - actual
        total_abs_error += abs(error)
        
        active_signal = mags[start:end]
        
        # Plot: 3 columns
        gs = gridspec.GridSpec(n_runs, 3, hspace=0.5, wspace=0.3)
        
        # 1. Time-domain signal with active region
        ax1 = fig.add_subplot(gs[idx, 0])
        ax1.plot(t, mags, 'b-', linewidth=0.8, alpha=0.7)
        ax1.axvspan(start/SAMPLE_RATE, end/SAMPLE_RATE, alpha=0.15, color='green', label='Active region')
        ax1.axhline(y=3.0, color='gray', linestyle=':', alpha=0.5)
        ax1.set_title(f'Run {idx+1}: {balls}b | actual={actual} | detected={detected} (err={error:+d})\n'
                      f'freq={freq:.2f} Hz' if freq else 
                      f'Run {idx+1}: {balls}b | actual={actual} | detected={detected} (err={error:+d})\n'
                      f'freq=N/A',
                      fontsize=10, fontweight='bold')
        ax1.set_xlabel('Time (s)')
        ax1.set_ylabel('Linear accel (m/s²)')
        ax1.legend(fontsize=7)
        ax1.set_ylim(bottom=0)
        
        # 2. FFT of active region
        ax2 = fig.add_subplot(gs[idx, 1])
        if len(active_signal) > 10:
            # Apply Hanning window
            windowed = active_signal * np.hanning(len(active_signal))
            fft_vals = np.abs(np.fft.rfft(windowed))
            freqs = np.fft.rfftfreq(len(active_signal), d=1.0/SAMPLE_RATE)
            
            # Normalize
            fft_vals = fft_vals / np.max(fft_vals) if np.max(fft_vals) > 0 else fft_vals
            
            ax2.plot(freqs, fft_vals, 'r-', linewidth=1)
            ax2.set_xlim(0, 12)
            ax2.set_xlabel('Frequency (Hz)')
            ax2.set_ylabel('Normalized magnitude')
            ax2.set_title('FFT of active region', fontsize=10)
            ax2.grid(True, alpha=0.3)
            
            # Mark the autocorrelation-detected frequency
            if freq:
                ax2.axvline(x=freq, color='green', linestyle='--', linewidth=1.5, 
                           label=f'Detected: {freq:.2f} Hz')
                ax2.legend(fontsize=8)
        
        # 3. Autocorrelation
        ax3 = fig.add_subplot(gs[idx, 2])
        if autocorr:
            lags = [a[0] / SAMPLE_RATE for a in autocorr]  # Convert to seconds
            vals = [a[1] for a in autocorr]
            ax3.plot(lags, vals, 'g-', linewidth=1.2)
            ax3.set_xlabel('Lag (seconds)')
            ax3.set_ylabel('Autocorrelation')
            ax3.set_title('Autocorrelation', fontsize=10)
            ax3.grid(True, alpha=0.3)
            ax3.axhline(y=0, color='gray', linestyle='-', alpha=0.3)
            
            if freq:
                period = 1.0 / freq
                ax3.axvline(x=period, color='red', linestyle='--', 
                           label=f'Period: {period:.3f}s ({freq:.2f} Hz)')
                ax3.legend(fontsize=8)
        
        print(f"  {run['file']}: {balls}b actual={actual:3d} detected={detected:3d} "
              f"err={error:+d} freq={'%.2f'%freq if freq else 'N/A'} Hz "
              f"active={len(active_signal)/SAMPLE_RATE:.1f}s")
    
    print(f"\nTotal absolute error: {total_abs_error}")
    
    outpath = os.path.join(data_dir, 'spectral_analysis.png')
    plt.savefig(outpath, dpi=150, bbox_inches='tight')
    plt.close()
    print(f"Saved to {outpath}")
