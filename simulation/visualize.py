import math
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.gridspec as gridspec
import os

from data_utils import (
    MILLI_G_TO_MS2, GRAVITY_ALPHA_IDLE, GRAVITY_ALPHA_ACTIVE, SAMPLE_RATE,
    parse_runs,
)

def detect_and_compute(x_mg, y_mg, z_mg, ball_count):
    """Single-pass detection using the legacy smoothing-based algorithm.
    
    NOTE: This does NOT match the current watch algorithm, which uses an IIR
    highpass filter. This is kept for visualization of the old approach.
    Returns (raw_mags, smoothed, peaks, threshold).
    """
    threshold = 9.0 + ball_count
    hysteresis_factor = min(0.325 + 0.075 * ball_count, 0.8)
    refractory_ms = 100 / (ball_count - 1)
    refractory_samples = max(1, int(refractory_ms / (1000 / SAMPLE_RATE)))
    warmup = 25

    gx = x_mg[0] * MILLI_G_TO_MS2
    gy = y_mg[0] * MILLI_G_TO_MS2
    gz = z_mg[0] * MILLI_G_TO_MS2

    raw_mags = []
    smoothed = []
    peaks = []
    catch_count = 0
    armed = True
    last_throw_idx = -refractory_samples - 1
    prev_mag = 0.0
    prev_prev_mag = 0.0

    for i in range(len(x_mg)):
        ax = x_mg[i] * MILLI_G_TO_MS2
        ay = y_mg[i] * MILLI_G_TO_MS2
        az = z_mg[i] * MILLI_G_TO_MS2
        if i > 0:
            alpha = GRAVITY_ALPHA_ACTIVE if catch_count > 0 else GRAVITY_ALPHA_IDLE
            gx = alpha * gx + (1 - alpha) * ax
            gy = alpha * gy + (1 - alpha) * ay
            gz = alpha * gz + (1 - alpha) * az
        lx = ax - gx
        ly = ay - gy
        lz = az - gz
        mag = math.sqrt(lx*lx + ly*ly + lz*lz)
        raw_mags.append(mag)

        # 3-point moving average
        if i < 2:
            sm = mag
        else:
            sm = (raw_mags[i-2] + raw_mags[i-1] + mag) / 3.0
        smoothed.append(sm)

        if i < warmup:
            prev_prev_mag = prev_mag
            prev_mag = mag
            continue

        if not armed and sm < threshold * hysteresis_factor:
            armed = True

        is_peak = (prev_mag >= mag and prev_mag >= prev_prev_mag and
                   sm > threshold and armed and
                   (i - last_throw_idx) > refractory_samples)

        if is_peak:
            peaks.append(i - 1)
            last_throw_idx = i
            armed = False
            catch_count += 1

        prev_prev_mag = prev_mag
        prev_mag = mag

    return raw_mags, smoothed, peaks, threshold

def plot_runs(runs, basename='output'):
    n = len(runs)
    fig = plt.figure(figsize=(16, 5 * n))
    gs = gridspec.GridSpec(n, 1, hspace=0.4)

    for idx, run in enumerate(runs):
        meta = run['meta']
        x, y, z = run['x'], run['y'], run['z']
        n_samples = len(x)
        t = [i / SAMPLE_RATE for i in range(n_samples)]

        balls = meta.get('balls', 3)
        mags, smoothed, peaks, threshold = detect_and_compute(x, y, z, balls)
        detected_count = len(peaks)

        ax = fig.add_subplot(gs[idx])

        # Plot raw axes in milli-g (secondary, faded)
        ax2 = ax.twinx()
        ax2.plot(t, x, alpha=0.15, color='red', linewidth=0.5, label='X')
        ax2.plot(t, y, alpha=0.15, color='green', linewidth=0.5, label='Y')
        ax2.plot(t, z, alpha=0.15, color='blue', linewidth=0.5, label='Z')
        ax2.set_ylabel('Raw accel (milli-g)', color='gray', fontsize=9)
        ax2.tick_params(axis='y', labelcolor='gray', labelsize=8)
        ax2.legend(loc='upper right', fontsize=7, framealpha=0.5)

        # Plot linear accel magnitude and smoothed
        ax.plot(t, mags, color='steelblue', alpha=0.4, linewidth=0.8, label='Linear accel mag')
        ax.plot(t, smoothed, color='navy', linewidth=1.2, label='Smoothed (3-pt avg)')

        # Threshold line
        hysteresis_val = threshold * min(0.325 + 0.075 * balls, 0.8)
        ax.axhline(y=threshold, color='orange', linestyle='--', linewidth=1, label=f'Threshold ({threshold:.1f} m/s²)')
        ax.axhline(y=hysteresis_val, color='orange', linestyle=':', linewidth=0.7, alpha=0.5, label=f'Hysteresis ({hysteresis_val:.1f})')

        # Mark detected peaks
        for pi in peaks:
            ax.axvline(x=t[pi], color='red', alpha=0.3, linewidth=0.8)
            ax.plot(t[pi], smoothed[pi], 'rv', markersize=8)

        ax.set_xlabel('Time (s)')
        ax.set_ylabel('Linear accel magnitude (m/s²)')
        ax.set_title(
            f'Run {idx+1}: {meta.get("balls","")} balls | '
            f'Actual watch-hand catches: {meta.get("catches","")} | '
            f'Algorithm detected: {detected_count} (peaks: {len(peaks)}) | '
            f'Watch reported: {meta.get("detected","")}',
            fontsize=11, fontweight='bold'
        )
        ax.legend(loc='upper left', fontsize=8)
        ax.set_ylim(bottom=0)
        ax.grid(True, alpha=0.3)

    plt.savefig(os.path.join(os.path.dirname(__file__), f'{basename}_analysis.png'), dpi=150, bbox_inches='tight')
    plt.close()
    print(f"Saved to {basename}_analysis.png")

if __name__ == '__main__':
    import sys
    if len(sys.argv) > 1:
        filepath = sys.argv[1]
    else:
        filepath = os.path.join(os.path.dirname(__file__), '..', 'data', 'juggling_recordings_20260603_223806.csv')
    basename = os.path.splitext(os.path.basename(filepath))[0]
    runs = parse_runs(filepath)
    print(f"Parsed {len(runs)} runs")
    for i, r in enumerate(runs):
        m = r['meta']
        print(f"  Run {i+1}: {len(r['x'])} samples, balls={m.get('balls')}, catches={m.get('catches')}, detected={m.get('detected')}")
    plot_runs(runs, basename)
