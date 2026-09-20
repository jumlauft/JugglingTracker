import numpy as np
import tensorflow as tf
from sklearn.model_selection import train_test_split
from train_model import extract_features
import os

def train_all_variants():
    for b in [3, 4, 5]:
        data_path = f"simulation/output/catch_dataset_{b}.npz"
        if not os.path.exists(data_path):
            continue
        print(f"\n--- Training specialized model for {b} balls ---")
        train_and_export_tflite(
            data_path, 
            output_model=f"simulation/output/catch_detector_{b}.tflite",
            norm_output=f"simulation/output/norm_params_{b}.json"
        )

def train_and_export_tflite(data_path, output_model, norm_output):
    # 1. Load Data
    data = np.load(data_path)
    X, y = data['X'], data['y']
    X_features, _ = extract_features(X)

    # 2. Preprocess
    mean = X_features.mean(axis=0)
    std = X_features.std(axis=0)
    X_norm = (X_features - mean) / (std + 1e-7)

    # Save normalization constants as JSON
    import json
    with open(norm_output, 'w') as f:
        json.dump({'mean': mean.tolist(), 'std': std.tolist()}, f)

    X_train, X_test, y_train, y_test = train_test_split(
        X_norm, y, test_size=0.2, random_state=42, stratify=y
    )

    # 3. Build model
    model = tf.keras.Sequential([
        tf.keras.layers.Dense(32, activation='relu', input_shape=(X_norm.shape[1],)),
        tf.keras.layers.Dropout(0.2),
        tf.keras.layers.Dense(16, activation='relu'),
        tf.keras.layers.Dense(1, activation='sigmoid')
    ])
    model.compile(optimizer='adam', loss='binary_crossentropy', metrics=['accuracy'])

    # 4. Train
    model.fit(X_train, y_train, epochs=30, batch_size=32, validation_split=0.1, verbose=0)

    # 6. Export to TFLite
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    tflite_model = converter.convert()
    with open(output_model, "wb") as f:
        f.write(tflite_model)
    
    print(f"Exported: {output_model} and {norm_output}")

if __name__ == "__main__":
    train_all_variants()
