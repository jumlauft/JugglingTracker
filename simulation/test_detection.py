"""
Tests for the juggling watch-hand catch detection algorithm.

Runs the exact watch simulation (from eval_new_watch.py) against all recorded
datasets with the current JugglingDetector.mc parameters, asserting that
detection performance does not regress.

The expected detection counts come from the delayed burst-clustering algorithm
with alternating watch-hand burst counting: total absolute error = 382 and
positive overcount error = 5 across 69 runs (1479 actual watch-hand catches).
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
    3: {"threshold": 2.0, "refractory_ms": 80, "raw_gate": 7.0, "merge_window_ms": 160},
    4: {"threshold": 4.0, "refractory_ms": 40, "raw_gate": 0.0, "merge_window_ms": 80},
    5: {"threshold": 0.8, "refractory_ms": 320, "raw_gate": 13.0, "merge_window_ms": 160},
}

# ── Expected detection counts per run ──────────────────────────────────────
# Each entry: (csv_filename, run_index_in_file, balls, actual_watch_hand_catches, expected_detected)
# Run index disambiguates multiple runs of the same ball count in one file.
EXPECTED_RUNS = [
    ("juggling_recordings_20260603_201546.csv", 0, 3, 6, 4),
    ("juggling_recordings_20260603_201546.csv", 1, 3, 17, 18),
    ("juggling_recordings_20260603_201546.csv", 2, 5, 4, 4),
    ("juggling_recordings_20260603_201546.csv", 3, 5, 13, 9),
    ("juggling_recordings_20260603_203008.csv", 0, 3, 20, 20),
    ("juggling_recordings_20260603_223806.csv", 0, 3, 25, 23),
    ("juggling_recordings_20260603_223806.csv", 1, 3, 26, 26),
    ("juggling_recordings_20260603_223806.csv", 2, 3, 5, 4),
    ("juggling_recordings_20260603_223806.csv", 3, 3, 7, 7),
    ("juggling_recordings_20260603_223806.csv", 4, 3, 8, 7),
    ("juggling_recordings_20260603_223806.csv", 5, 4, 13, 12),
    ("juggling_recordings_20260603_223806.csv", 6, 4, 0, 0),
    ("juggling_recordings_20260603_223806.csv", 7, 4, 8, 8),
    ("juggling_recordings_20260603_223806.csv", 8, 4, 21, 21),
    ("juggling_recordings_20260603_223806.csv", 9, 5, 4, 3),
    ("juggling_recordings_20260603_223806.csv", 10, 5, 9, 6),
    ("juggling_recordings_20260603_223806.csv", 11, 5, 9, 6),
    ("juggling_recordings_20260609_103926.csv", 0, 5, 22, 24),
    ("juggling_recordings_20260609_103926.csv", 1, 3, 19, 17),
    ("juggling_recordings_20260609_103926.csv", 2, 4, 4, 4),
    ("juggling_recordings_20260609_103926.csv", 3, 4, 26, 25),
    ("juggling_recordings_20260609_114726.csv", 0, 3, 5, 5),
    ("juggling_recordings_20260609_114726.csv", 1, 3, 15, 14),
    ("juggling_recordings_20260609_114726.csv", 2, 3, 26, 25),
    ("juggling_recordings_20260609_114726.csv", 3, 4, 25, 24),
    ("juggling_recordings_20260609_114726.csv", 4, 4, 41, 36),
    ("juggling_recordings_20260609_114726.csv", 5, 5, 4, 4),
    ("juggling_recordings_20260609_114726.csv", 6, 5, 4, 4),
    ("juggling_recordings_20260609_114726.csv", 7, 5, 5, 5),
    ("juggling_recordings_20260609_114726.csv", 8, 5, 6, 6),
    ("juggling_recordings_20260609_114726.csv", 9, 5, 10, 9),
    ("juggling_recordings_20260609_114726.csv", 10, 5, 21, 13),
    ("juggling_recordings_20260609_114726.csv", 11, 5, 10, 9),
    ("juggling_recordings_20260609_114726.csv", 12, 5, 19, 13),
    ("juggling_recordings_20260609_114726.csv", 13, 5, 21, 16),
    ("juggling_recordings_20260609_114726.csv", 14, 5, 5, 7),
    ("juggling_recordings_20260922_160142.csv", 0, 4, 31, 29),
    ("juggling_recordings_20260922_160142.csv", 1, 4, 24, 21),
    ("juggling_recordings_20260922_160142.csv", 2, 4, 0, 0),
    ("juggling_recordings_20260922_160142.csv", 3, 3, 89, 88),
    ("juggling_recordings_20260922_160142.csv", 4, 7, 6, 6),
    ("juggling_recordings_20260922_160142.csv", 5, 7, 6, 6),
    ("juggling_recordings_20260922_160142.csv", 6, 7, 8, 7),
    ("juggling_recordings_20260922_160142.csv", 7, 7, 8, 7),
    ("juggling_recordings_20260922_160142.csv", 8, 7, 7, 7),
    ("juggling_recordings_20260922_160142.csv", 9, 7, 16, 10),
    ("juggling_recordings_20260922_160142.csv", 10, 7, 10, 8),
    ("juggling_recordings_20260922_160142.csv", 11, 7, 11, 6),
    ("juggling_recordings_20260922_160142.csv", 12, 7, 10, 7),
    ("juggling_recordings_20260922_160142.csv", 13, 7, 13, 9),
    ("juggling_recordings_20260922_160142.csv", 14, 7, 9, 9),
    ("juggling_recordings_20260922_160142.csv", 15, 5, 49, 27),
    ("juggling_recordings_20260922_160142.csv", 16, 5, 39, 22),
    ("juggling_recordings_20260922_160142.csv", 17, 5, 61, 34),
    ("juggling_recordings_20260922_160142.csv", 18, 5, 85, 47),
    ("juggling_recordings_20260922_160142.csv", 19, 5, 51, 28),
    ("juggling_recordings_20260922_160142.csv", 20, 5, 66, 38),
    ("juggling_recordings_20260922_160142.csv", 21, 5, 107, 56),
    ("juggling_recordings_20260922_160142.csv", 22, 5, 94, 49),
    ("juggling_recordings_20260922_160142.csv", 23, 5, 11, 8),
    ("juggling_recordings_20260922_160142.csv", 24, 5, 24, 15),
    ("juggling_recordings_20260922_160142.csv", 25, 4, 60, 55),
    ("juggling_recordings_20260922_160142.csv", 26, 7, 11, 9),
    ("juggling_recordings_20260922_160142.csv", 27, 7, 11, 9),
    ("juggling_recordings_20260922_160142.csv", 28, 7, 10, 8),
    ("juggling_recordings_20260922_160142.csv", 29, 7, 18, 12),
    ("juggling_recordings_20260922_160142.csv", 30, 7, 21, 13),
    ("juggling_recordings_20260922_160142.csv", 31, 7, 15, 9),
    ("juggling_recordings_20260922_160142.csv", 32, 7, 15, 10),
]

# Maximum allowed total absolute error across all runs.
MAX_TOTAL_ERROR = 382
MAX_TOTAL_OVERCOUNT = 5


def _get_data_dir():
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'connectiq', 'data')


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

    Current baseline: 382 total absolute error across 69 runs (1479 watch-hand catches).
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


def test_auto_finish_timer_uses_committed_bursts_not_low_level_motion():
    """Auto-finish should not require the watch hand to be perfectly still.

    The finish timer is intentionally refreshed by committed candidate bursts,
    not by every sample above a low activity floor. This prevents small post-run
    wrist motion from postponing run completion indefinitely.
    """
    mc_path = os.path.join(
        os.path.dirname(os.path.abspath(__file__)),
        "..", "connectiq", "source", "JugglingDetector.mc",
    )
    if not os.path.exists(mc_path):
        pytest.skip("JugglingDetector.mc not found")

    with open(mc_path, "r") as f:
        source = f.read()

    assert "filtered > _hpThreshold * 0.5f" not in source

    commit_start = source.index("private function commitPendingPeak")
    add_start = source.index("private function addCandidate")
    commit_body = source[commit_start:add_start]
    assert "_lastActiveTime = nowMs;" in commit_body


def test_main_view_displays_run_state():
    """The watch face should show whether detection is in or between runs."""
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
    detector_path = os.path.join(root, "connectiq", "source", "JugglingDetector.mc")
    main_view_path = os.path.join(root, "connectiq", "source", "MainView.mc")
    if not os.path.exists(detector_path) or not os.path.exists(main_view_path):
        pytest.skip("ConnectIQ source files not found")

    with open(detector_path, "r") as f:
        detector_source = f.read()
    with open(main_view_path, "r") as f:
        main_view_source = f.read()

    assert "public function isRunActive() as Boolean" in detector_source
    assert "_detector.isRunActive()" in main_view_source
    assert "RUN ACTIVE" in main_view_source
    assert "WAITING" in main_view_source


def test_main_view_displays_and_transfers_session_duration():
    """The watch should show and transfer total session duration."""
    main_view_path = os.path.join(
        os.path.dirname(os.path.abspath(__file__)),
        "..", "connectiq", "source", "MainView.mc",
    )
    if not os.path.exists(main_view_path):
        pytest.skip("MainView.mc not found")

    with open(main_view_path, "r") as f:
        source = f.read()

    assert "_sessionStartMs = System.getTimer();" in source
    assert "_sessionEndMs = System.getTimer();" in source
    assert "_sessionEndMs = null;" in source
    assert "Time: $1$" in source
    assert "private function sessionDurationSeconds() as Number" in source
    assert '"durationSeconds" => sessionDurationSeconds()' in source


def test_watch_transfers_run_durations():
    """The watch should transfer one first-to-last catch duration per run."""
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    detector_path = os.path.join(repo_root, "connectiq", "source", "JugglingDetector.mc")
    main_view_path = os.path.join(repo_root, "connectiq", "source", "MainView.mc")
    if not os.path.exists(detector_path) or not os.path.exists(main_view_path):
        pytest.skip("watch sources not found")

    with open(detector_path, "r") as f:
        detector_source = f.read()
    with open(main_view_path, "r") as f:
        main_view_source = f.read()

    assert "public function runDurationsMillis() as Array<Number>" in detector_source
    assert "_firstCatchTime = _pendingPeakTime;" in detector_source
    assert "_lastCatchTime = _pendingPeakTime;" in detector_source
    assert "_runDurationsMillis.add(currentRunDurationMillis());" in detector_source
    assert '"runDurationsMillis" => _detector.runDurationsMillis()' in main_view_source


def test_customer_watch_startup_hides_recording_mode():
    """Customer builds should start directly in normal tracking setup."""
    app_path = os.path.join(
        os.path.dirname(os.path.abspath(__file__)),
        "..", "connectiq", "source", "JugglingTrackerApp.mc",
    )
    if not os.path.exists(app_path):
        pytest.skip("JugglingTrackerApp.mc not found")

    with open(app_path, "r") as f:
        source = f.read()

    assert "private const ENABLE_RECORDING_MODE = false;" in source
    assert "new BallSelectView(:juggle)" in source
    assert "new ModeSelectView()" in source
