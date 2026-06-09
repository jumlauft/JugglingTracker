import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import os

from data_utils import MILLI_G_TO_MS2, SAMPLE_RATE, parse_runs

def plot_accel(runs, basename='output'):
    n = len(runs)
    fig, axes = plt.subplots(n, 1, figsize=(16, 4 * n), squeeze=False)

    for idx, run in enumerate(runs):
        meta = run['meta']
        x = [v * MILLI_G_TO_MS2 for v in run['x']]
        y = [v * MILLI_G_TO_MS2 for v in run['y']]
        z = [v * MILLI_G_TO_MS2 for v in run['z']]
        t = [i / SAMPLE_RATE for i in range(len(x))]

        ax = axes[idx][0]
        ax.plot(t, x, color='red', linewidth=0.8, label='X')
        ax.plot(t, y, color='green', linewidth=0.8, label='Y')
        ax.plot(t, z, color='blue', linewidth=0.8, label='Z')

        ax.set_xlabel('Time (s)')
        ax.set_ylabel('Acceleration (m/s²)')
        ax.set_title(
            f'Run {idx+1}: {meta.get("balls","")} balls | '
            f'Catches: {meta.get("catches","")} | '
            f'{len(run["x"])} samples ({len(run["x"])/SAMPLE_RATE:.1f}s)',
            fontsize=11, fontweight='bold'
        )
        ax.legend(loc='upper right', fontsize=9)
        ax.grid(True, alpha=0.3)

    plt.tight_layout()
    out = os.path.join(os.path.dirname(__file__), '..', 'data', f'{basename}_accel.png')
    plt.savefig(out, dpi=150, bbox_inches='tight')
    plt.close()
    print(f"Saved to {out}")

if __name__ == '__main__':
    import sys
    if len(sys.argv) > 1:
        filepath = sys.argv[1]
    else:
        filepath = os.path.join(os.path.dirname(__file__), '..', 'data', 'juggling_recordings_20260603_223806.csv')
    basename = os.path.splitext(os.path.basename(filepath))[0]
    runs = parse_runs(filepath)
    print(f"Parsed {len(runs)} runs")
    plot_accel(runs, basename)
