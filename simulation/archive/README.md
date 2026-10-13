# Archived simulation scripts

Experiments that nothing in the repo runs or imports any more, kept for
reference:

- `test_phone_data.py` - Python port of the old 200 Hz phone detector, which
  the phone apps replaced with the shared 25 Hz detector. Despite its name it
  is a script, not a test.
- `prepare_ml_data.py`, `train_model.py`, `train_tflite.py`,
  `inference_test.py`, `visualize_phone_data.py` - the machine-learning
  experiment built on it (needs scikit-learn, seaborn, joblib, TensorFlow).
- `causal_highpass_test.py` (with `causal_highpass_detection.png`) and
  `compare_algorithms.py` - early comparisons of detection approaches.

They are not maintained and may not match the current data format. Those
that import `data_utils` run from `simulation/` with
`PYTHONPATH=. python archive/<script>.py`. `simulation/conftest.py` keeps
`pytest` from collecting anything in this folder.
