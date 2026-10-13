import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
import os
import seaborn as sns

def visualize_dataset(npz_path, output_dir="simulation/output"):
    if not os.path.exists(npz_path):
        print(f"Dataset not found: {npz_path}")
        return

    # Load data
    data = np.load(npz_path)
    X, y = data['X'], data['y']
    
    os.makedirs(output_dir, exist_ok=True)

    # 1. Class Distribution
    plt.figure(figsize=(8, 5))
    unique, counts = np.unique(y, return_counts=True)
    sns.barplot(x=unique, y=counts, palette='viridis')
    plt.title("Dataset: Class Distribution (Number of Balls)")
    plt.xlabel("Ball Count")
    plt.ylabel("Number of 200ms Windows")
    dist_path = os.path.join(output_dir, "class_distribution.png")
    plt.savefig(dist_path)
    print(f"Saved distribution plot to: {dist_path}")

    # 2. Window Visualizations (3 examples per class)
    fig, axes = plt.subplots(3, 3, figsize=(15, 12), sharex=True, sharey=True)
    channels = ['X', 'Y', 'Z', 'Mag']
    colors = ['r', 'g', 'b', 'k']

    for i, ball_count in enumerate([3, 4, 5]):
        indices = np.where(y == ball_count)[0]
        if len(indices) < 3: continue
        
        sample_indices = np.random.choice(indices, 3, replace=False)
        for j, idx in enumerate(sample_indices):
            window = X[idx]
            ax = axes[i, j]
            for c in range(4):
                ax.plot(window[:, c], label=channels[c], color=colors[c], alpha=0.7)
            
            if j == 0:
                ax.set_ylabel(f"{ball_count} Balls\nAccel (m/s²)")
            if i == 2:
                ax.set_xlabel("Samples (5ms ticks)")
            if i == 0 and j == 1:
                ax.set_title("Random 200ms Window Examples")
            
            ax.grid(True, alpha=0.3)
            if i == 0 and j == 0:
                ax.legend(loc='upper right', fontsize=8)

    plt.tight_layout()
    windows_path = os.path.join(output_dir, "window_samples.png")
    plt.savefig(windows_path)
    print(f"Saved window samples to: {windows_path}")

    # 3. Raw Signal Trace (First 5 seconds of the first run)
    # We need the original CSV for this to show a continuous trace
    raw_csv = "android/data/juggling_recordings_20260611_232502.csv"
    if os.path.exists(raw_csv):
        trace_df = []
        with open(raw_csv, 'r') as f:
            for line in f:
                if line.startswith("#"): continue
                if line.startswith("x,y,z"): continue
                parts = line.strip().split(',')
                if len(parts) == 3:
                    trace_df.append([float(p) for p in parts])
                if len(trace_df) > 1000: break # First 5 seconds
        
        trace = np.array(trace_df) * 9.80665 / 1000.0
        plt.figure(figsize=(15, 5))
        plt.plot(trace[:, 0], 'r', label='X', alpha=0.6)
        plt.plot(trace[:, 1], 'g', label='Y', alpha=0.6)
        plt.plot(trace[:, 2], 'b', label='Z', alpha=0.6)
        plt.title("Continuous Trace (First 5 Seconds of Recording)")
        plt.xlabel("Samples (5ms ticks)")
        plt.ylabel("Accel (m/s²)")
        plt.legend()
        plt.grid(True, alpha=0.3)
        trace_path = os.path.join(output_dir, "raw_trace.png")
        plt.savefig(trace_path)
        print(f"Saved raw trace to: {trace_path}")

if __name__ == "__main__":
    visualize_dataset("simulation/output/juggling_dataset.npz")
