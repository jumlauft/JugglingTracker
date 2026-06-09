"""
Tests for the juggling watch-hand catch detection algorithm.

Runs the exact watch simulation (from eval_new_watch.py) against all recorded
datasets with the current JugglingDetector.mc parameters, asserting that
detection performance does not regress.

The expected detection counts come from the delayed burst-clustering algorithm
with alternating watch-hand burst counting: total absolute error = 20 and
positive overcount error = 0 across 21 runs (266 actual watch-hand catches).
"""
import os
import sys
import pytest

# Ensure the simulation directory is importable.
sys.path.insert(0, os.path.dirname(__file__))

from eval_new_watch import simulate_watch
from data_utils import load_all_runs

# ── Current watch parameters (must match JugglingDetector.mc) ──────────────
WATCH_PARAMS = {
    3: {"threshold": 2.6, "refractory_ms": 80, "raw_gate": 9.0, "merge_window_ms": 120},
    4: {"threshold": 4.0, "refractory_ms": 40, "raw_gate": 0.0, "merge_window_ms": 80},
    5: {"threshold": 0.5, "refractory_ms": 80, "raw_gate": 0.0, "merge_window_ms": 280},
}

# ── Expected detection counts per run ──────────────────────────────────────
# Each entry: (csv_filename, run_index_in_file, balls, actual_watch_hand_catches, expected_detected)
# Run index disambiguates multiple runs of the same ball count in one file.
EXPECTED_RUNS = [
    ("juggling_recordings_20260603_201546.csv", 0, 3, 6, 4),
    ("juggling_recordings_20260603_201546.csv", 1, 3, 17, 17),
    ("juggling_recordings_20260603_201546.csv", 2, 5, 4, 4),
    ("juggling_recordings_20260603_201546.csv", 3, 5, 13, 11),
    ("juggling_recordings_20260603_203008.csv", 0, 3, 20, 18),
    ("juggling_recordings_20260603_223806.csv", 0, 3, 25, 22),
    ("juggling_recordings_20260603_223806.csv", 1, 3, 26, 25),
    ("juggling_recordings_20260603_223806.csv", 2, 3, 5, 4),
    ("juggling_recordings_20260603_223806.csv", 3, 3, 7, 7),
    ("juggling_recordings_20260603_223806.csv", 4, 3, 8, 7),
    ("juggling_recordings_20260603_223806.csv", 5, 4, 13, 12),
    ("juggling_recordings_20260603_223806.csv", 6, 4, 0, 0),
    ("juggling_recordings_20260603_223806.csv", 7, 4, 8, 8),
    ("juggling_recordings_20260603_223806.csv", 8, 4, 21, 21),
    ("juggling_recordings_20260603_223806.csv", 9, 5, 4, 4),
    ("juggling_recordings_20260603_223806.csv", 10, 5, 9, 9),
    ("juggling_recordings_20260603_223806.csv", 11, 5, 9, 5),
    ("juggling_recordings_20260609_103926.csv", 0, 5, 22, 22),
    ("juggling_recordings_20260609_103926.csv", 1, 3, 19, 17),
    ("juggling_recordings_20260609_103926.csv", 2, 4, 4, 4),
    ("juggling_recordings_20260609_103926.csv", 3, 4, 26, 25),
]

# Maximum allowed total absolute error across all runs.
MAX_TOTAL_ERROR = 20
MAX_TOTAL_OVERCOUNT = 0


def _get_data_dir():
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'data')


def _load_runs_by_file():
    """Load all runs grouped by CSV filename."""
    from data_utils import parse_runs
    import glob

    data_dir = _get_data_dir()
    by_file = {}
    for path in sorted(glob.glob(os.path.join(data_dir, "juggling_recordings_*.csv"))):
        fname = os.path.basename(path)
        by_file[fname] = parse_runs(path)
    return by_file


@pytest.fixture(scope="module")
def runs_by_file():
    return _load_runs_by_file()


def _detect(run, balls):
    """Run the watch simulation with current parameters."""
    p = WATCH_PARAMS[min(balls, 5)]
    use_gate = p["raw_gate"] > 0
    count, _ = simulate_watch(
        run["x"], run["y"], run["z"], balls,
        p["threshold"], p["refractory_ms"],
        use_gate, p["raw_gate"], p["merge_window_ms"],
    )
    return count


def _run_id(val):
    """Readable test ID for parametrize."""
    fname, idx, balls, actual, expected = val
    short = fname.split("_")[-1].replace(".csv", "")
    return f"{short}_run{idx}_{balls}b_{actual}catches"


@pytest.mark.parametrize("entry", EXPECTED_RUNS, ids=[_run_id(e) for e in EXPECTED_RUNS])
def test_detection_count_per_run(runs_by_file, entry):
    """Each run's detection count must match the baseline exactly.

    If this test fails, the algorithm parameters have changed in a way that
    alters detection on real data. Update EXPECTED_RUNS only after verifying
    the new counts are acceptable.
    """
    fname, run_idx, balls, actual_catches, expected_detected = entry
    assert fname in runs_by_file, f"CSV file {fname} not found in data/"
    runs = runs_by_file[fname]
    assert run_idx < len(runs), f"Run index {run_idx} out of range (file has {len(runs)} runs)"

    run = runs[run_idx]
    assert run["meta"]["balls"] == balls
    assert run["meta"]["catches"] == actual_catches

    detected = _detect(run, balls)
    assert detected == expected_detected, (
        f"{fname} run {run_idx} ({balls}b, {actual_catches} actual): "
        f"detected {detected}, expected {expected_detected}"
    )


def test_total_absolute_error(runs_by_file):
    """Total absolute error across all runs must not exceed the baseline.

    Current baseline: 20 total absolute error across 21 runs (266 watch-hand catches).
    A regression means the algorithm is less accurate overall.
    """
    total_error = 0
    for fname, run_idx, balls, actual_catches, _ in EXPECTED_RUNS:
        run = runs_by_file[fname][run_idx]
        detected = _detect(run, balls)
        total_error += abs(detected - actual_catches)

    assert total_error <= MAX_TOTAL_ERROR, (
        f"Total absolute error {total_error} exceeds maximum {MAX_TOTAL_ERROR}"
    )


def test_total_overcount_error(runs_by_file):
    """Positive count error must stay low.

    The current watch issue is overcounting, so this protects the new detector's
    bias toward rejecting duplicate catch lobes.
    """
    total_overcount = 0
    for fname, run_idx, balls, actual_catches, _ in EXPECTED_RUNS:
        run = runs_by_file[fname][run_idx]
        detected = _detect(run, balls)
        total_overcount += max(0, detected - actual_catches)

    assert total_overcount <= MAX_TOTAL_OVERCOUNT, (
        f"Total overcount error {total_overcount} exceeds maximum {MAX_TOTAL_OVERCOUNT}"
    )


def test_all_csv_files_covered(runs_by_file):
    """Every run in every CSV file must appear in EXPECTED_RUNS.

    Ensures new recordings get added to the test suite.
    """
    covered = {(e[0], e[1]) for e in EXPECTED_RUNS}
    for fname, runs in runs_by_file.items():
        for idx in range(len(runs)):
            assert (fname, idx) in covered, (
                f"Run {idx} in {fname} ({runs[idx]['meta']['balls']}b, "
                f"{runs[idx]['meta']['catches']} catches) is not in EXPECTED_RUNS"
            )


def test_watch_params_match_detector_constants():
    """Verify WATCH_PARAMS match the constants in JugglingDetector.mc.

    Parses the Monkey C source to extract the actual constants and checks
    they match the values used in these tests.
    """
    mc_path = os.path.join(
        os.path.dirname(os.path.abspath(__file__)),
        "..", "connectiq", "source", "JugglingDetector.mc",
    )
    if not os.path.exists(mc_path):
        pytest.skip("JugglingDetector.mc not found")

    with open(mc_path, "r") as f:
        source = f.read()

    def extract_const(name):
        import re
        m = re.search(rf"private const {name}\s*=\s*([0-9.]+)f?;", source)
        assert m, f"Constant {name} not found in JugglingDetector.mc"
        return float(m.group(1))

    assert extract_const("HP_THRESHOLD_3") == pytest.approx(WATCH_PARAMS[3]["threshold"])
    assert extract_const("HP_THRESHOLD_4") == pytest.approx(WATCH_PARAMS[4]["threshold"])
    assert extract_const("HP_THRESHOLD_5PLUS") == pytest.approx(WATCH_PARAMS[5]["threshold"])

    assert int(extract_const("REFRACTORY_MS_3")) == WATCH_PARAMS[3]["refractory_ms"]
    assert int(extract_const("REFRACTORY_MS_4")) == WATCH_PARAMS[4]["refractory_ms"]
    assert int(extract_const("REFRACTORY_MS_5PLUS")) == WATCH_PARAMS[5]["refractory_ms"]

    assert extract_const("MIN_RAW_MAG_3") == pytest.approx(WATCH_PARAMS[3]["raw_gate"])
    assert extract_const("MIN_RAW_MAG_4") == pytest.approx(WATCH_PARAMS[4]["raw_gate"])
    assert extract_const("MIN_RAW_MAG_5PLUS") == pytest.approx(WATCH_PARAMS[5]["raw_gate"])

    assert int(extract_const("MERGE_WINDOW_MS_3")) == WATCH_PARAMS[3]["merge_window_ms"]
    assert int(extract_const("MERGE_WINDOW_MS_4")) == WATCH_PARAMS[4]["merge_window_ms"]
    assert int(extract_const("MERGE_WINDOW_MS_5PLUS")) == WATCH_PARAMS[5]["merge_window_ms"]

    assert extract_const("HP_HYSTERESIS") == pytest.approx(0.3)


def test_watch_counts_alternating_bursts_as_watch_hand_catches():
    """The watch must count only odd-numbered committed bursts.

    Candidate bursts model alternating hand catch motions, so only the odd
    bursts are displayed as catches by the hand wearing the watch.
    """
    mc_path = os.path.join(
        os.path.dirname(os.path.abspath(__file__)),
        "..", "connectiq", "source", "JugglingDetector.mc",
    )
    if not os.path.exists(mc_path):
        pytest.skip("JugglingDetector.mc not found")

    with open(mc_path, "r") as f:
        source = f.read()

    assert "_committedBurstCount += 1;" in source
    assert "(_committedBurstCount % 2) == 1" in source
    assert "currentCount += 1;" in source
    assert "currentCount += 2;" not in source
