import numpy as np
import os
import joblib
from test_phone_data import PhoneDetectorSim
from train_model import extract_features

def run_ml_inference(csv_path, window_size=40, step_size=5):
    if not os.path.exists(csv_path):
        print(f"CSV not found: {csv_path}")
        return

    # 1. Models loaded per-run to allow ball-count specialization
    models = {}
    
    # 2. Parse Runs
    runs = []
    current_run = []
    current_balls = 0
    actual_catches = 0
    with open(csv_path, 'r') as f:
        for line in f:
            if line.startswith("# balls="):
                if current_run:
                    runs.append({'balls': current_balls, 'actual': actual_catches, 'data': np.array(current_run)})
                parts = line.strip().split(',')
                current_balls = int(parts[0].split("=")[1])
                actual_catches = int(parts[1].split("=")[1])
                current_run = []
            elif not line.startswith("#") and line.strip() and not line.startswith("x,y,z"):
                parts = line.strip().split(',')
                if len(parts) >= 3:
                    current_run.append([float(p) for p in parts[:3]])
    if current_run:
        runs.append({'balls': current_balls, 'actual': actual_catches, 'data': np.array(current_run)})

    print(f"Comparing Heuristic vs Specialized ML on {len(runs)} runs...\n")
    print(f"{'Balls':<6} | {'Actual':<8} | {'Heuristic':<10} | {'ML (Raw)':<10} | {'ML (Spec.)':<10}")
    print("-" * 65)

    for run in runs:
        balls = run['balls']
        data = run['data']
        actual = run['actual']
        
        # Load specialized model for this ball count
        b_key = 3 if balls <= 3 else (4 if balls == 4 else 5)
        if b_key not in models:
            model_path = f"simulation/output/catch_detector_{b_key}.joblib"
            if os.path.exists(model_path):
                models[b_key] = joblib.load(model_path)
            else:
                combined_path = "simulation/output/catch_detector.joblib"
                if os.path.exists(combined_path):
                    models[b_key] = joblib.load(combined_path)
                else:
                    print(f"No model found for {balls} balls.")
                    continue

        clf = models[b_key]

        # --- Heuristic ---
        detector = PhoneDetectorSim(balls)
        for i, row in enumerate(data):
            ax, ay, az = [v * 9.80665 / 1000.0 for v in row]
            detector.process_sample(ax, ay, az, i * 5)
        heuristic_count = detector.finish()
        
        # --- ML Inference ---
        data_ms2 = data * 9.80665 / 1000.0
        mag = np.sqrt(np.sum(data_ms2**2, axis=1)).reshape(-1, 1)
        full_data = np.hstack([data_ms2, mag])
        
        windows = []
        for i in range(0, len(full_data) - window_size, step_size):
            windows.append(full_data[i : i + window_size])
        
        if not windows:
            print(f"{balls:<6} | {actual:<8} | {heuristic_count:<10} | {'0':<10} | {'0':<10}")
            continue
            
        X_test = np.array(windows)
        X_features, _ = extract_features(X_test)
        
        y_probs = clf.predict_proba(X_features)[:, 1]
        y_pred = (y_probs > 0.6).astype(int)
        raw_ml_hits = np.sum(y_pred)
        
        ref_ms = 350 if balls <= 3 else (250 if balls == 4 else 180)
        ref_steps = ref_ms // 25
        
        ml_burst_count = 0
        last_hit_idx = -ref_steps
        for idx, prob in enumerate(y_probs):
            if prob > 0.6:
                if idx - last_hit_idx >= ref_steps:
                    lookahead = y_probs[idx : idx + 4]
                    peak_offset = np.argmax(lookahead)
                    ml_burst_count += 1
                    last_hit_idx = idx + peak_offset
        
        cleaned_ml_count = (ml_burst_count + 1) // 2
        print(f"{balls:<6} | {actual:<8} | {heuristic_count:<10} | {raw_ml_hits:<10} | {cleaned_ml_count:<10}")

if __name__ == "__main__":
    import glob
    data_files = sorted(glob.glob("android/data/juggling_recordings_*.csv"))
    for data_file in data_files:
        print(f"\nEvaluating: {data_file}")
        run_ml_inference(data_file)
