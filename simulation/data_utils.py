"""
Shared utilities for juggling accelerometer data analysis scripts.

All analysis scripts import from here to avoid duplicating core functions
like CSV parsing, gravity removal, and data loading.
"""
import math
import os
import glob

MILLI_G_TO_MS2 = 9.80665 / 1000.0
SAMPLE_RATE = 25
WARMUP = 25

GRAVITY_ALPHA_IDLE = 0.95
GRAVITY_ALPHA_ACTIVE = 0.99


def parse_runs(filepath):
    """Parse multi-run CSV into list of dicts with metadata and samples.

    Each run dict has:
        'meta': dict with balls, catches, detected, sampleRate, timestamp.
        catches is the watch-hand ground-truth label in current datasets.
        detected is the detector output stored with the recording and may be
        historical for older captures.
        'x', 'y', 'z': lists of raw milli-g integers
    """
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
    """Compute linear acceleration magnitude with dynamic gravity alpha.

    Legacy helper for exploratory scripts. The watch-parity simulator in
    eval_new_watch.py uses detector-state-dependent alpha instead.
    """
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
            alpha = GRAVITY_ALPHA_ACTIVE if active else GRAVITY_ALPHA_IDLE
            gx = alpha * gx + (1 - alpha) * ax
            gy = alpha * gy + (1 - alpha) * ay
            gz = alpha * gz + (1 - alpha) * az
        lx = ax - gx
        ly = ay - gy
        lz = az - gz
        mag = math.sqrt(lx * lx + ly * ly + lz * lz)
        mags.append(mag)
        if mag > 5.0:
            active = True
    return mags


def compute_magnitude_raw(x_mg, y_mg, z_mg):
    """Compute raw acceleration magnitude (no gravity removal)."""
    mags = []
    for i in range(len(x_mg)):
        ax = x_mg[i] * MILLI_G_TO_MS2
        ay = y_mg[i] * MILLI_G_TO_MS2
        az = z_mg[i] * MILLI_G_TO_MS2
        mags.append(math.sqrt(ax * ax + ay * ay + az * az))
    return mags


def load_all_runs(data_dir=None):
    """Load all CSV recordings from data directory.

    Returns list of run dicts, each with an added 'file' key holding the
    basename of the source CSV file.
    """
    if data_dir is None:
        data_dir = os.path.join(os.path.dirname(__file__), '..', 'data')
    all_runs = []
    for csvfile in sorted(glob.glob(os.path.join(data_dir, '*.csv'))):
        runs = parse_runs(csvfile)
        for r in runs:
            r['file'] = os.path.basename(csvfile)
            all_runs.append(r)
    return all_runs
