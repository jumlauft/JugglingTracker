import numpy as np
from sklearn.model_selection import train_test_split
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import classification_report, confusion_matrix
import joblib
import os
import matplotlib.pyplot as plt
import seaborn as sns

def extract_features(X):
    """
    Converts raw windows into statistical features.
    X shape: (windows, samples, features)
    """
    feature_names = []
    channels = ['x', 'y', 'z', 'mag']
    stats = ['mean', 'std', 'max', 'min', 'range']
    for c in channels:
        for s in stats:
            feature_names.append(f"{c}_{s}")

    features = []
    for window in X:
        win_features = []
        for i in range(4): # For each sensor channel
            channel = window[:, i]
            win_features.extend([
                np.mean(channel),
                np.std(channel),
                np.max(channel),
                np.min(channel),
                np.max(channel) - np.min(channel),
            ])
        features.append(win_features)
    return np.array(features), feature_names

def train_model(data_path, output_model="simulation/output/catch_detector.joblib"):
    if not os.path.exists(data_path):
        print(f"Data file not found: {data_path}. Run prepare_ml_data.py first.")
        return

    # 1. Load Data
    print(f"Loading dataset from {data_path}...")
    data = np.load(data_path)
    X, y = data['X'], data['y']

    # 2. Feature Engineering
    print("Extracting features from windows...")
    X_features, feature_names = extract_features(X)

    # 3. Split
    # Since this is a binary catch/no-catch task, stratification is critical
    X_train, X_test, y_train, y_test = train_test_split(
        X_features, y, test_size=0.2, random_state=42, stratify=y
    )

    # 4. Train
    print(f"Training Catch Detector (Random Forest) on {len(X_train)} samples...")
    clf = RandomForestClassifier(n_estimators=100, random_state=42, class_weight='balanced')
    clf.fit(X_train, y_train)

    # 5. Evaluate
    y_pred = clf.predict(X_test)
    print("\nModel Evaluation (Binary Catch Detection):")
    print(classification_report(y_test, y_pred, target_names=['None', 'Catch']))
    
    # Confusion Matrix
    cm = confusion_matrix(y_test, y_pred)
    plt.figure(figsize=(6, 5))
    sns.heatmap(cm, annot=True, fmt='d', cmap='Blues', 
                xticklabels=['None', 'Catch'], yticklabels=['None', 'Catch'])
    plt.title("Catch Detection Confusion Matrix")
    plt.ylabel("Actual")
    plt.xlabel("Predicted")
    plt.savefig("simulation/output/catch_confusion_matrix.png")
    print("Saved confusion matrix to: simulation/output/catch_confusion_matrix.png")

    # Feature Importance
    print("\nTop 5 Important Features for Catch Detection:")
    importances = clf.feature_importances_
    indices = np.argsort(importances)[::-1]
    for i in range(5):
        print(f"{i+1}. {feature_names[indices[i]]}: {importances[indices[i]]:.4f}")

    # 6. Save
    joblib.dump(clf, output_model)
    print(f"\nModel saved to: {output_model}")

if __name__ == "__main__":
    # Train combined model
    train_model("simulation/output/catch_dataset.npz", "simulation/output/catch_detector.joblib")
    
    # Train specialized models
    for b in [3, 4, 5]:
        data_path = f"simulation/output/catch_dataset_{b}.npz"
        if os.path.exists(data_path):
            print(f"\n--- Training specialized Random Forest for {b} balls ---")
            train_model(data_path, f"simulation/output/catch_detector_{b}.joblib")
