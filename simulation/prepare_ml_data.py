import pandas as pd
import numpy as np
import os
from test_phone_data import PhoneDetectorSim

class CatchDetector(PhoneDetectorSim):
    def __init__(self, ball_count):
        super().__init__(ball_count)
        self.burst_timestamps = []

    def _commit_pending(self, now_ms):
        if not self.has_pending:
            return
        self.burst_timestamps.append(self.pending_time)
        super()._commit_pending(now_ms)

def prepare_ml_dataset(csv_path, window_size=40, step_size=5):
    if not os.path.exists(csv_path):
        return None, None, None

    runs = []
    current_run = []
    current_balls = 0
    with open(csv_path, 'r') as f:
        for line in f:
            if line.startswith("# balls="):
                if current_run:
                    runs.append((current_balls, np.array(current_run)))
                current_balls = int(line.split("=")[1].split(",")[0])
                current_run = []
            elif not line.startswith("#") and line.strip() and not line.startswith("x,y,z"):
                parts = line.strip().split(',')
                if len(parts) >= 3:
                    current_run.append([float(p) for p in parts[:3]])
    if current_run:
        runs.append((current_balls, np.array(current_run)))

    X = []
    y = []
    ball_labels = []

    print(f"Processing {len(runs)} runs from {csv_path}...")
    for balls, data in runs:
        detector = CatchDetector(balls)
        for i, row in enumerate(data):
            detector.process_sample(row[0], row[1], row[2], i * 5)
        detector.finish()
        catch_indices = [t // 5 for t in detector.burst_timestamps]
        
        data_ms2 = data * 9.80665 / 1000.0
        mag = np.sqrt(np.sum(data_ms2**2, axis=1)).reshape(-1, 1)
        full_data = np.hstack([data_ms2, mag])

        for i in range(0, len(full_data) - window_size, step_size):
            window = full_data[i : i + window_size]
            middle_start, middle_end = i + 10, i + 30
            is_catch = any(idx >= middle_start and idx < middle_end for idx in catch_indices)
            X.append(window)
            y.append(1 if is_catch else 0)
            ball_labels.append(balls)

    return np.array(X), np.array(y), np.array(ball_labels)

if __name__ == "__main__":
    import glob
    data_files = glob.glob("android/data/juggling_recordings_*.csv")
    print(f"Found {len(data_files)} data files")
    
    per_ball_X = {3: [], 4: [], 5: []}
    per_ball_y = {3: [], 4: [], 5: []}
    
    all_X = []
    all_y = []

    for data_file in data_files:
        X, y, balls = prepare_ml_dataset(data_file)
        if X is not None:
            all_X.append(X)
            all_y.append(y)
            for i in range(len(X)):
                b_key = 3 if balls[i] <= 3 else (4 if balls[i] == 4 else 5)
                per_ball_X[b_key].append(X[i])
                per_ball_y[b_key].append(y[i])
    
    os.makedirs("simulation/output", exist_ok=True)
    
    # Save per-ball datasets
    for b in [3, 4, 5]:
        if not per_ball_X[b]: continue
        X_b = np.array(per_ball_X[b])
        y_b = np.array(per_ball_y[b])
        
        # Balance
        c_idxs = np.where(y_b == 1)[0]
        n_idxs = np.where(y_b == 0)[0]
        np.random.seed(42)
        sel_n = np.random.choice(n_idxs, min(len(n_idxs), len(c_idxs) * 2), replace=False)
        final_idxs = np.concatenate([c_idxs, sel_n])
        np.random.shuffle(final_idxs)
        
        out_path = f"simulation/output/catch_dataset_{b}.npz"
        np.savez_compressed(out_path, X=X_b[final_idxs], y=y_b[final_idxs])
        print(f"Saved {b}-ball dataset: {len(final_idxs)} samples to {out_path}")

    # Save combined for backward compatibility
    if all_X:
        X_combined = np.concatenate(all_X)
        y_combined = np.concatenate(all_y)
        c_idxs = np.where(y_combined == 1)[0]
        n_idxs = np.where(y_combined == 0)[0]
        np.random.seed(42)
        sel_n = np.random.choice(n_idxs, min(len(n_idxs), len(c_idxs) * 2), replace=False)
        final_idxs = np.concatenate([c_idxs, sel_n])
        np.random.shuffle(final_idxs)
        np.savez_compressed("simulation/output/catch_dataset.npz", X=X_combined[final_idxs], y=y_combined[final_idxs])
        print(f"Saved combined dataset: {len(final_idxs)} samples")
