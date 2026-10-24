"""
Tests for the juggling watch-hand catch detection algorithm.

Runs the exact watch simulation (from eval_new_watch.py) against all recorded
datasets with the current JugglingDetector.mc parameters, asserting that
detection performance does not regress.

The expected detection counts come from the delayed burst-clustering algorithm
with alternating watch-hand burst counting: total absolute error = 245 and
positive overcount error = 99 across 129 recordings (3246 actual watch-hand catches).
"""
import math
import os
import re
import sys
import pytest

# Ensure the simulation directory is importable.
sys.path.insert(0, os.path.dirname(__file__))

from eval_new_watch import simulate_watch, simulate_session, CURRENT_WATCH_PARAMS, params_for_balls
from data_utils import load_all_runs

# ── Current watch parameters (must match JugglingDetector.mc) ──────────────
WATCH_PARAMS = {
    3: {"threshold": 2.0, "refractory_ms": 80, "raw_gate": 7.0, "merge_window_ms": 160},
    4: {"threshold": 3.0, "refractory_ms": 80, "raw_gate": 11.0, "merge_window_ms": 160},
    5: {"threshold": 3.0, "refractory_ms": 40, "raw_gate": 13.0, "merge_window_ms": 160},
    6: {"threshold": 5.0, "refractory_ms": 40, "raw_gate": 17.0, "merge_window_ms": 120},
    7: {"threshold": 2.5, "refractory_ms": 160, "raw_gate": 16.0, "merge_window_ms": 80},
}

# ── Expected detection counts per run ──────────────────────────────────────
# Each entry: (run_id, balls, actual_watch_hand_catches, expected_detected)
# run_id is the recording's stable identifier and file name, so entries never
# depend on a run's position within a file.
EXPECTED_RUNS = [
    ("20260603_201719", 3, 20, 20),
    ("20260603_203220", 3, 6, 4),
    ("20260603_203258", 3, 17, 18),
    ("20260603_203431", 5, 4, 7),
    ("20260603_203544", 5, 13, 14),
    ("20260603_223012", 3, 25, 23),
    ("20260603_223112", 3, 26, 26),
    ("20260603_223207", 3, 5, 4),
    ("20260603_223230", 3, 7, 7),
    ("20260603_223252", 3, 8, 7),
    ("20260603_223335", 4, 13, 12),
    ("20260603_223404", 4, 0, 0),
    ("20260603_223429", 4, 8, 8),
    ("20260603_223524", 4, 21, 20),
    ("20260603_223607", 5, 4, 4),
    ("20260603_223631", 5, 9, 9),
    ("20260603_223722", 5, 9, 11),
    ("20260609_103255", 5, 22, 42),
    ("20260609_103537", 3, 19, 17),
    ("20260609_103633", 4, 4, 4),
    ("20260609_103716", 4, 26, 24),
    ("20260609_113823", 3, 5, 5),
    ("20260609_113851", 3, 15, 14),
    ("20260609_113926", 3, 26, 25),
    ("20260609_114030", 4, 25, 24),
    ("20260609_114116", 4, 41, 38),
    ("20260609_114247", 5, 4, 6),
    ("20260609_114309", 5, 4, 5),
    ("20260609_114328", 5, 5, 6),
    ("20260609_114344", 5, 6, 8),
    ("20260609_114403", 5, 10, 12),
    ("20260609_114430", 5, 21, 19),
    ("20260609_114455", 5, 10, 14),
    ("20260609_114524", 5, 19, 18),
    ("20260609_114557", 5, 21, 22),
    ("20260609_114620", 5, 5, 8),
    ("20260922_143708", 4, 31, 31),
    ("20260922_143912", 4, 24, 25),
    ("20260922_144309", 4, 0, 0),
    ("20260922_144802", 3, 89, 88),
    ("20260922_151259", 7, 6, 7),
    ("20260922_151339", 7, 6, 8),
    ("20260922_151408", 7, 8, 8),
    ("20260922_151455", 7, 8, 9),
    ("20260922_151547", 7, 7, 8),
    ("20260922_151715", 7, 16, 13),
    ("20260922_151758", 7, 10, 9),
    ("20260922_151910", 7, 11, 9),
    ("20260922_151957", 7, 10, 8),
    ("20260922_152032", 7, 13, 12),
    ("20260922_152153", 7, 9, 10),
    ("20260922_152424", 5, 49, 41),
    ("20260922_152542", 5, 39, 36),
    ("20260922_152712", 5, 61, 60),
    ("20260922_152928", 5, 85, 76),
    ("20260922_153051", 5, 51, 47),
    ("20260922_153218", 5, 66, 64),
    ("20260922_153408", 5, 107, 90),
    ("20260922_153603", 5, 94, 88),
    ("20260922_153715", 5, 11, 13),
    ("20260922_153754", 5, 24, 22),
    ("20260922_154055", 4, 60, 58),
    ("20260922_154204", 7, 11, 12),
    ("20260922_154237", 7, 11, 10),
    ("20260922_154319", 7, 10, 11),
    ("20260922_154408", 7, 18, 16),
    ("20260922_154457", 7, 21, 17),
    ("20260922_154640", 7, 15, 14),
    ("20260922_154722", 7, 15, 12),
    ("20260923_222840", 5, 36, 36),
    ("20260923_222936", 5, 35, 34),
    ("20260923_223021", 5, 15, 14),
    ("20260923_223144", 5, 94, 93),
    ("20260923_223329", 5, 18, 20),
    ("20260923_223403", 5, 15, 15),
    ("20260923_223542", 3, 75, 79),
    ("20260923_224723", 4, 38, 38),
    ("20260924_165934", 5, 12, 12),
    ("20260924_170001", 5, 12, 11),
    ("20260924_170059", 5, 49, 47),
    ("20260924_170153", 5, 23, 21),
    ("20260924_170236", 5, 25, 28),
    ("20260924_170352", 5, 88, 87),
    ("20260924_170632", 6, 9, 9),
    ("20260924_170706", 6, 11, 14),
    ("20260924_170736", 6, 26, 26),
    ("20260924_170756", 6, 9, 9),
    ("20260924_170818", 6, 14, 15),
    ("20260924_170844", 6, 15, 16),
    ("20260924_170918", 6, 8, 9),
    ("20260924_171031", 6, 44, 39),
    ("20260924_171108", 6, 14, 14),
    ("20260924_171239", 6, 32, 32),
    ("20260924_171333", 6, 33, 33),
    ("20260924_171644", 7, 12, 12),
    ("20260924_171849", 7, 13, 12),
    ("20260924_171927", 7, 11, 11),
    ("20260924_172053", 7, 13, 12),
    ("20260924_172136", 7, 11, 10),
    ("20260924_172333", 5, 25, 25),
    ("20260924_172451", 5, 77, 71),
    ("20260924_172608", 5, 68, 67),
    # Moritz Wich, watch on the left wrist, first throw with the right hand.
    ("20260930_162534", 3, 20, 23),
    ("20260930_162649", 3, 12, 12),
    ("20260930_162726", 3, 8, 10),
    ("20260930_162809", 3, 25, 30),
    ("20260930_162915", 3, 10, 12),
    ("20260930_162951", 3, 20, 22),
    # Jonas Umlauft, watch on the left wrist, first throw with the right hand.
    ("20260930_184904", 3, 21, 21),
    ("20260930_185035", 3, 20, 21),
    ("20260930_185258", 3, 18, 19),
    ("20260930_185440", 3, 26, 31),
    ("20260930_225225", 3, 14, 14),
    ("20261003_173929", 5, 19, 19),
    ("20261003_174200", 5, 63, 65),
    ("20261003_174341", 5, 102, 100),
    ("20261003_174457", 5, 22, 23),
    # Felix, watch on the left wrist, first throw with the right hand.
    ("20261003_172032", 4, 48, 48),
    ("20261003_172504", 4, 44, 43),
    ("20261003_172632", 4, 40, 37),
    ("20261003_172755", 4, 50, 47),
    # Jonas Umlauft: four 7-ball runs of 5, 9, 10 and 15 in one recording.
    # The whole-file count is checked here; how it splits into runs is
    # checked by test_back_to_back_runs_split_at_the_right_places.
    ("20261007_184543", 7, 39, 39),
    # Dominik aus Muc: one 4-ball run and four 7-ball runs. The 7-ball runs
    # were typed as catches in both hands; the counts here are the left
    # (watch) hand's share, rounded up since the first catch lands there.
    ("20261004_141326", 4, 52, 52),
    ("20261006_183939", 7, 27, 19),
    ("20261006_184057", 7, 11, 10),
    ("20261006_184141", 7, 6, 6),
    ("20261006_184234", 7, 30, 22),
    # Stefan the Juggling Noob: two 3-ball runs, both typed as 20. The watch
    # counted 22 and 24 at capture, the same as the simulation here.
    ("20261010_155338", 3, 20, 22),
    ("20261010_155424", 3, 20, 24),
]

# Maximum allowed total absolute error across all runs.
MAX_TOTAL_ERROR = 245
MAX_TOTAL_OVERCOUNT = 99


def _get_data_dir():
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'connectiq', 'data')


def _load_runs_by_id():
    """Load every recording, keyed by its run id (one run per file)."""
    from data_utils import parse_runs
    import glob

    by_id = {}
    for path in sorted(glob.glob(os.path.join(_get_data_dir(), "*.csv"))):
        parsed = parse_runs(path)
        assert len(parsed) == 1, f"{path} holds {len(parsed)} runs, expected one"
        run = parsed[0]
        run_id = run["meta"]["run"]
        assert run_id == os.path.basename(path)[:-4], (
            f"run id {run_id} does not match file name {os.path.basename(path)}"
        )
        assert run_id not in by_id, f"duplicate run id {run_id}"
        by_id[run_id] = run
    return by_id


@pytest.fixture(scope="module")
def runs_by_id():
    return _load_runs_by_id()


def _bucket(balls):
    """Match JugglingDetector.mc: 3 / 4 / 5 / 6 / 7+."""
    if balls <= 3:
        return 3
    if balls == 4:
        return 4
    if balls == 5:
        return 5
    return 6 if balls == 6 else 7


def _detect(run, balls):
    """Run the watch simulation with current parameters."""
    p = WATCH_PARAMS[_bucket(balls)]
    use_gate = p["raw_gate"] > 0
    count, _ = simulate_watch(
        run["x"], run["y"], run["z"], balls,
        p["threshold"], p["refractory_ms"],
        use_gate, p["raw_gate"], p["merge_window_ms"],
    )
    return count


def _run_id(val):
    """Readable test ID for parametrize."""
    run_id, balls, actual, expected = val
    return f"{run_id}_{balls}b_{actual}catches"


@pytest.mark.parametrize("entry", EXPECTED_RUNS, ids=[_run_id(e) for e in EXPECTED_RUNS])
def test_detection_count_per_run(runs_by_id, entry):
    """Each run's detection count must match the baseline exactly.

    If this test fails, the algorithm parameters have changed in a way that
    alters detection on real data. Update EXPECTED_RUNS only after verifying
    the new counts are acceptable.
    """
    run_id, balls, actual_catches, expected_detected = entry
    assert run_id in runs_by_id, f"recording {run_id}.csv not found in data/"

    run = runs_by_id[run_id]
    assert run["meta"]["balls"] == balls
    assert run["meta"]["catches"] == actual_catches

    detected = _detect(run, balls)
    assert detected == expected_detected, (
        f"{run_id} ({balls}b, {actual_catches} actual): "
        f"detected {detected}, expected {expected_detected}"
    )


def test_total_absolute_error(runs_by_id):
    """Total absolute error across all runs must not exceed the baseline.

    Current baseline: 239 total absolute error across 127 recordings (3206 watch-hand catches).
    A regression means the algorithm is less accurate overall.
    """
    total_error = 0
    for run_id, balls, actual_catches, _ in EXPECTED_RUNS:
        detected = _detect(runs_by_id[run_id], balls)
        total_error += abs(detected - actual_catches)

    assert total_error <= MAX_TOTAL_ERROR, (
        f"Total absolute error {total_error} exceeds maximum {MAX_TOTAL_ERROR}"
    )


def test_total_overcount_error(runs_by_id):
    """Positive count error must stay low.

    The current watch issue is overcounting, so this protects the new detector's
    bias toward rejecting duplicate catch lobes.
    """
    total_overcount = 0
    for run_id, balls, actual_catches, _ in EXPECTED_RUNS:
        detected = _detect(runs_by_id[run_id], balls)
        total_overcount += max(0, detected - actual_catches)

    assert total_overcount <= MAX_TOTAL_OVERCOUNT, (
        f"Total overcount error {total_overcount} exceeds maximum {MAX_TOTAL_OVERCOUNT}"
    )


# 20261007_184543 holds four 7-ball runs back to back. Where the juggling is
# in it, in seconds from the start of the recording, read off the
# acceleration trace: the wind-up before each run and the ball gathering
# after each drop fall outside these windows.
BACK_TO_BACK_RUN = "20261007_184543"
BACK_TO_BACK_WINDOWS_S = [(1.5, 8.0), (18.9, 24.0), (34.3, 41.5), (51.4, 60.7)]
BACK_TO_BACK_ACTUAL = [5, 9, 10, 15]
BACK_TO_BACK_DETECTED = [9, 8, 10, 13]


def test_back_to_back_runs_split_at_the_right_places(runs_by_id):
    """On the Juggle screen, four runs with pauses between them are four runs.

    Each run has to start and end inside its own stretch of juggling: a run
    started early by the wind-up, or kept open by catching the dropped balls,
    adds catches nobody juggled and shifts the next run's boundaries.
    """
    run = runs_by_id[BACK_TO_BACK_RUN]
    p = WATCH_PARAMS[_bucket(run["meta"]["balls"])]
    runs = simulate_session(run["x"], run["y"], run["z"], p["threshold"],
                            p["refractory_ms"], p["raw_gate"], p["merge_window_ms"])

    assert len(runs) == len(BACK_TO_BACK_WINDOWS_S), runs
    period_s = 1.0 / run["meta"]["sampleRate"]
    for (catches, first, last), (start, end) in zip(runs, BACK_TO_BACK_WINDOWS_S):
        assert start <= first * period_s and last * period_s <= end, (
            f"run of {catches} spans {first * period_s:.2f}-{last * period_s:.2f} s, "
            f"outside the juggling at {start}-{end} s"
        )
    assert [r[0] for r in runs] == BACK_TO_BACK_DETECTED
    assert sum(BACK_TO_BACK_ACTUAL) == run["meta"]["catches"]

def test_every_recording_is_covered(runs_by_id):
    """Every recording on disk must appear in EXPECTED_RUNS.

    Ensures new recordings get added to the test suite.
    """
    covered = {e[0] for e in EXPECTED_RUNS}
    missing = sorted(set(runs_by_id) - covered)
    assert not missing, f"recordings not in EXPECTED_RUNS: {missing}"


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
    assert extract_const("HP_THRESHOLD_5") == pytest.approx(WATCH_PARAMS[5]["threshold"])
    assert extract_const("HP_THRESHOLD_6") == pytest.approx(WATCH_PARAMS[6]["threshold"])
    assert extract_const("HP_THRESHOLD_7PLUS") == pytest.approx(WATCH_PARAMS[7]["threshold"])

    assert int(extract_const("REFRACTORY_MS_3")) == WATCH_PARAMS[3]["refractory_ms"]
    assert int(extract_const("REFRACTORY_MS_4")) == WATCH_PARAMS[4]["refractory_ms"]
    assert int(extract_const("REFRACTORY_MS_5")) == WATCH_PARAMS[5]["refractory_ms"]
    assert int(extract_const("REFRACTORY_MS_6")) == WATCH_PARAMS[6]["refractory_ms"]
    assert int(extract_const("REFRACTORY_MS_7PLUS")) == WATCH_PARAMS[7]["refractory_ms"]

    assert extract_const("MIN_RAW_MAG_3") == pytest.approx(WATCH_PARAMS[3]["raw_gate"])
    assert extract_const("MIN_RAW_MAG_4") == pytest.approx(WATCH_PARAMS[4]["raw_gate"])
    assert extract_const("MIN_RAW_MAG_5") == pytest.approx(WATCH_PARAMS[5]["raw_gate"])
    assert extract_const("MIN_RAW_MAG_6") == pytest.approx(WATCH_PARAMS[6]["raw_gate"])
    assert extract_const("MIN_RAW_MAG_7PLUS") == pytest.approx(WATCH_PARAMS[7]["raw_gate"])

    assert int(extract_const("MERGE_WINDOW_MS_3")) == WATCH_PARAMS[3]["merge_window_ms"]
    assert int(extract_const("MERGE_WINDOW_MS_4")) == WATCH_PARAMS[4]["merge_window_ms"]
    assert int(extract_const("MERGE_WINDOW_MS_5")) == WATCH_PARAMS[5]["merge_window_ms"]
    assert int(extract_const("MERGE_WINDOW_MS_6")) == WATCH_PARAMS[6]["merge_window_ms"]
    assert int(extract_const("MERGE_WINDOW_MS_7PLUS")) == WATCH_PARAMS[7]["merge_window_ms"]

    assert extract_const("HP_HYSTERESIS") == pytest.approx(0.3)

    # 5, 6 and 7+ must each be selected separately, not folded together.
    assert "balls == 5" in source
    assert "balls == 6" in source


def test_evaluator_params_match_the_tested_watch_params():
    """Pin eval_new_watch.py's own parameter table to the one these tests use.

    These tests drive simulate_watch() with WATCH_PARAMS, but the evaluator
    keeps a second table, CURRENT_WATCH_PARAMS, which is what params_for_balls()
    and the sweep's tie-breaker read -- and rhythm_gate.py imports. The two
    drifted silently once: the evaluator had no 6-ball entry and folded 6 balls
    onto the 5-ball values long after the watch shipped a separate bucket.
    Nothing failed, because no test had ever read that table.
    """
    assert set(CURRENT_WATCH_PARAMS) == set(WATCH_PARAMS), (
        "evaluator and test parameter tables cover different buckets: "
        f"{sorted(CURRENT_WATCH_PARAMS)} vs {sorted(WATCH_PARAMS)}"
    )
    for bucket, expected in WATCH_PARAMS.items():
        assert CURRENT_WATCH_PARAMS[bucket] == expected, (
            f"evaluator {bucket}-ball params disagree with the watch: "
            f"{CURRENT_WATCH_PARAMS[bucket]} vs {expected}"
        )

    # Every ball count the app offers has to land in its own bucket, which also
    # cross-checks the evaluator's bucketing against _bucket() used above.
    for balls in range(3, 10):
        assert params_for_balls(balls) == WATCH_PARAMS[_bucket(balls)], (
            f"{balls} balls routed to the wrong parameter set"
        )


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


def test_watch_transfers_run_durations():
    """The watch should transfer one first-to-last catch duration per run."""
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    detector_path = os.path.join(repo_root, "connectiq", "source", "JugglingDetector.mc")
    if not os.path.exists(detector_path):
        pytest.skip("watch sources not found")

    with open(detector_path, "r") as f:
        detector_source = f.read()

    assert "public function runDurationsMillis() as Array<Number>" in detector_source
    assert "_firstCatchTime = _pendingPeakTime;" in detector_source
    assert "_lastCatchTime = _pendingPeakTime;" in detector_source
    assert "_runDurationsMillis.add(currentRunDurationMillis());" in detector_source


def _read_source(*rel_parts):
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", *rel_parts)
    if not os.path.exists(path):
        pytest.skip(f"{os.path.join(*rel_parts)} not found")
    with open(path, "r") as f:
        return f.read()


def test_short_runs_are_ignored_as_false_starts():
    """A run under MIN_RUN_CATCHES catches must never reach session stats.

    Below any real ball count, fewer than three catches before a drop is a
    false start, not a run -- recording it just pollutes Prev/Avg/Max and the
    per-run list synced to the phone. recordRun() must bail out before it
    touches any of previousCount, _sessionRuns, _sessionTotal, sessionMax,
    _runCatches or _runDurationsMillis.
    """
    source = _read_source("connectiq", "source", "JugglingDetector.mc")

    m = re.search(r"private const MIN_RUN_CATCHES\s*=\s*(\d+);", source)
    assert m, "MIN_RUN_CATCHES constant not found in JugglingDetector.mc"
    assert int(m.group(1)) == 3

    m = re.search(
        r"private function recordRun\(catches as Number\) as Void \{(.*?)\n    \}",
        source, re.DOTALL,
    )
    assert m, "recordRun() not found in JugglingDetector.mc"
    body = m.group(1)

    guard = re.search(r"if \(catches < MIN_RUN_CATCHES\) \{(.*?)\}", body, re.DOTALL)
    assert guard, "recordRun() does not guard on MIN_RUN_CATCHES"
    assert "return;" in guard.group(1), "the MIN_RUN_CATCHES guard must return early"

    # The guard has to come before any of the state it must not touch, or an
    # early return added later in the function would silently stop protecting
    # some of these.
    guard_pos = body.find("if (catches < MIN_RUN_CATCHES)")
    for stat in (
        "previousCount = catches", "_sessionRuns +=", "_sessionTotal +=",
        "sessionMax = catches", "_runCatches.add", "_runDurationsMillis.add",
    ):
        stat_pos = body.find(stat)
        assert stat_pos == -1 or guard_pos < stat_pos, (
            f"{stat!r} in recordRun() is not protected by the MIN_RUN_CATCHES guard"
        )



def test_watch_menus_use_the_apps_own_labels():
    """Every Garmin prompt is a Menu2 with the app's English labels (JUG-5).

    WatchUi.Confirmation renders its yes/no in the watch's system language,
    which put German "Ja"/"Nein" in the middle of an otherwise English app.
    The labels themselves are checked by the shared scenarios.
    """
    source_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "connectiq", "source")
    for name in sorted(os.listdir(source_dir)):
        if name.endswith(".mc"):
            source = _read_source("connectiq", "source", name)
            assert "WatchUi.Confirmation(" not in source, f"{name} uses WatchUi.Confirmation"
    platform = _read_source("connectiq", "source", "GarminPlatform.mc")
    assert "new WatchUi.Menu2(" in platform
    assert "new SessionMenuDelegate(onSelect, onBack)" in platform


def test_menus_report_backing_out():
    """Backing out of a menu over a tracking screen must reach the session.

    Menu2InputDelegate's default onBack pops the menu without telling anyone,
    which left the screen waiting on a menu that was gone: every later
    START/STOP was a no-op, and after a failed recording sync BACK and START
    were both ignored with no way to reopen the menu (JUG-6, REC-10). What
    the session then does is covered by the shared scenarios.
    """
    platform = _read_source("connectiq", "source", "GarminPlatform.mc")
    m = re.search(
        r"class SessionMenuDelegate extends WatchUi\.Menu2InputDelegate \{(.*?)\n\}",
        platform, re.DOTALL,
    )
    assert m, "SessionMenuDelegate not found in GarminPlatform.mc"
    back = re.search(r"public function onBack\(\) as Void \{(.*?)\n    \}", m.group(1), re.DOTALL)
    assert back, "SessionMenuDelegate must override onBack()"
    # Overriding replaces the default pop, so it has to pop itself.
    assert "WatchUi.popView" in back.group(1)
    assert "_onBack.invoke();" in back.group(1)


def test_tracking_views_reacquire_the_sensor_after_any_menu():
    """Pushing a menu over a tracking screen must not freeze it (SENS-1, SENS-2).

    On real hardware, pushing any view over the Juggle or Record screen hides
    it and drops the accelerometer listener with it. Without re-registering
    in onShow(), popping back left the screen frozen and detection silently
    stopped. Registration must be idempotent, as onShow() also fires right
    after initialize().
    """
    for name in ("MainView.mc", "RecordingView.mc"):
        source = _read_source("connectiq", "source", name)
        assert re.search(r"public function onShow\(\) as Void \{\s*startSensor\(\);", source), (
            f"onShow() in {name} must re-acquire the sensor"
        )
        m = re.search(r"private function startSensor\(\) as Void \{(.*?)\n    \}", source, re.DOTALL)
        assert m, f"startSensor() not found in {name}"
        assert "if (_sensorActive) {\n            return;" in m.group(1), f"startSensor() in {name} must be idempotent"
        assert "_sensorActive = true;" in m.group(1)
        unregister = source.find("Sensor.unregisterSensorDataListener();")
        assert unregister != -1, f"{name} must release the sensor"
        assert "_sensorActive = false;" in source[unregister:unregister + 200], (
            f"{name} must clear _sensorActive when it releases the sensor, so the next onShow() re-registers"
        )
        m = re.search(r"public function onHide\(\) as Void \{(.*?)\n    \}", source, re.DOTALL)
        assert m and ("Sensor.unregisterSensorDataListener();" in m.group(1) or "stopSensor();" in m.group(1)), (
            f"onHide() in {name} must release the sensor"
        )


def test_tracking_views_show_a_sensor_error():
    """A failed accelerometer registration must show on screen (SENS-4).

    It used to be only printed to the debug log, so the count silently stayed
    at 0 and the juggler had no idea why. That START then does nothing on the
    record screen is checked by sens4_recordStartDoesNothingWithoutTheSensor.
    """
    for name in ("MainView.mc", "RecordingView.mc"):
        source = _read_source("connectiq", "source", name)
        m = re.search(r"private function startSensor\(\) as Void \{(.*?)\n    \}", source, re.DOTALL)
        assert m, f"startSensor() not found in {name}"
        catch = m.group(1).split("catch (ex)")
        assert len(catch) == 2, f"startSensor() in {name} must catch a registration error"
        assert "_session.setSensorError(true);" in catch[1], f"{name} must flag a failed registration"
        assert "_session.setSensorError(false);" in catch[0], f"{name} must clear the flag once registered"
        m = re.search(r"public function onUpdate\(dc as Dc\) as Void \{(.*?)\n    \}", source, re.DOTALL)
        assert m, f"onUpdate() not found in {name}"
        assert "_session.sensorError()" in m.group(1) and "drawSensorError(dc);" in m.group(1), (
            f"onUpdate() in {name} must draw the sensor error"
        )
    main_view = _read_source("connectiq", "source", "MainView.mc")
    assert '"Sensor error"' in main_view and '"Restart the app"' in main_view


def test_garmin_scenario_tests_are_up_to_date():
    """connectiq/test/ScenarioTest.mc must match connectiq/scenarios/*.json.

    The Garmin replays the shared scenarios as code generated by
    connectiq/scenarios/garmin_tests.py, and runs it only on a Mac. This
    catches a scenario changed without regenerating, in CI.
    """
    import importlib.util
    script = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "connectiq", "scenarios", "garmin_tests.py")
    spec = importlib.util.spec_from_file_location("garmin_tests", script)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    current = _read_source("connectiq", "test", "ScenarioTest.mc")
    assert current == module.generate(), (
        "connectiq/test/ScenarioTest.mc is out of date: run python3 connectiq/scenarios/garmin_tests.py"
    )


# ── Requirements traceability ──────────────────────────────────────────────
#
# connectiq/REQUIREMENTS.md is the written specification of the watch app.
# These tests keep it honest: a requirement that cites a test which no longer
# exists, or a watch unit test that no requirement claims, both fail here.


def _requirement_verifications():
    """Every requirement ID in REQUIREMENTS.md with the tests it cites."""
    doc = _read_source("connectiq", "REQUIREMENTS.md")

    found = {}
    current = None
    for line in doc.splitlines():
        m = re.match(r"\*\*((?:APP|DET|RUN|SHAPE|JUG|LATER|ACT|SENS|SYNC|REC)-\d+)\.\*\*", line)
        if m:
            current = m.group(1)
            found.setdefault(current, [])
        elif line.startswith("*Verified by:*") and current is not None:
            if "not automatically tested" in line:
                found[current] = None  # explicitly, honestly uncovered
            else:
                found[current] = re.findall(r"`([A-Za-z_][A-Za-z0-9_]*)`", line)
            current = None
    return found


def _watch_unit_test_names():
    names = set()
    test_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "connectiq", "test")
    for filename in sorted(os.listdir(test_dir)):
        if not filename.endswith(".mc"):
            continue
        source = _read_source("connectiq", "test", filename)
        names |= set(re.findall(r"\(:test\)\s*\nfunction (\w+)", source))
    return names


def test_every_requirement_states_how_it_is_verified():
    """No requirement may silently lack a verification line."""
    verifications = _requirement_verifications()
    assert verifications, "no requirements parsed out of REQUIREMENTS.md"

    missing = [req for req, tests in verifications.items() if tests == []]
    assert not missing, f"requirements with no 'Verified by:' line: {missing}"


def test_requirements_only_cite_tests_that_exist():
    """A requirement pointing at a deleted test is worse than no citation."""
    verifications = _requirement_verifications()

    own_source = _read_source("simulation", "test_detection.py")
    python_tests = set(re.findall(r"^def (test_\w+)", own_source, re.M))
    watch_tests = _watch_unit_test_names()
    known = python_tests | watch_tests

    unknown = {}
    for req, tests in verifications.items():
        if not tests:
            continue
        gone = [t for t in tests if t not in known]
        if gone:
            unknown[req] = gone
    assert not unknown, f"requirements cite tests that do not exist: {unknown}"


def test_every_watch_unit_test_backs_a_requirement():
    """A watch test nobody claims means the spec is missing something."""
    verifications = _requirement_verifications()
    cited = set()
    for tests in verifications.values():
        if tests:
            cited |= set(tests)

    orphans = sorted(_watch_unit_test_names() - cited)
    assert not orphans, (
        "these watch unit tests are not cited by any requirement in "
        f"REQUIREMENTS.md: {orphans}"
    )


# ── Shape consistency (SHAPE-*) ──────────────────────────────────────────
# simulation/shape_consistency.py is the reference for ShapeConsistency.mc and
# the shared Kotlin ShapeConsistency.kt. The labelled runs in regularity_data were
# juggled on purpose as regular or messy.

# Session score of each labelled run. The shared ShapeConsistencyTest reads
# this table, so the Kotlin port must reproduce it exactly.
EXPECTED_SHAPE = [
    ("20260930_184904", "regular-low", 90),
    ("20260930_185035", "regular-high", 86),
    ("20260930_185258", "very-messy", 51),
    ("20260930_185440", "medium-messy", 55),
]


def _shape_tracker_fed(signal, first_catch_ms=0):
    """A tracker fed `signal(i) -> (x, y, z)` for 400 samples inside one run."""
    from shape_consistency import ShapeConsistency
    tracker = ShapeConsistency()
    for i in range(400):
        x, y, z = signal(i)
        tracker.add_sample(x, y, z, i * 40, True, True, first_catch_ms)
    return tracker


def _periodic(i):
    phase = 2 * math.pi * i / 20
    return (round(500 * math.sin(phase)), round(300 * math.cos(phase)),
            1000 + round(200 * math.sin(2 * phase)))


def _unrelated(i, _state=[12345]):
    def nxt():
        _state[0] = (_state[0] * 1103515245 + 12345) % 2147483648
        return _state[0] % 1001 - 500
    return (nxt(), nxt(), 1000 + nxt())


def test_shape_periodic_motion_scores_full():
    tracker = _shape_tracker_fed(_periodic)
    tracker.on_catch(400 * 40)
    tracker.commit_run()
    assert tracker.session_percent() >= 99


def test_shape_unrelated_cycles_score_low():
    tracker = _shape_tracker_fed(_unrelated)
    tracker.on_catch(400 * 40)
    tracker.commit_run()
    assert 0 <= tracker.session_percent() < 50


def test_shape_only_windows_inside_the_run_count():
    from shape_consistency import HISTORY
    # Nothing is scored until a catch confirms it.
    tracker = _shape_tracker_fed(_periodic)
    tracker.commit_run()
    assert tracker.session_percent() == -1
    # A window reaching back before the first catch never counts.
    tracker = _shape_tracker_fed(_periodic, first_catch_ms=(400 - HISTORY + 2) * 40)
    tracker.on_catch(400 * 40)
    tracker.commit_run()
    assert tracker.session_percent() == -1


def test_shape_discard_removes_the_runs_score():
    from shape_consistency import ShapeConsistency
    tracker = ShapeConsistency()
    for i in range(800):
        x, y, z = _periodic(i) if i < 400 else _unrelated(i)
        tracker.add_sample(x, y, z, i * 40, True, True, 0 if i < 400 else 400 * 40)
        if i == 399:
            tracker.on_catch(i * 40)
            tracker.commit_run()
    tracker.on_catch(800 * 40)
    tracker.commit_run()
    mixed = tracker.session_percent()
    tracker.discard_last_run()
    assert tracker.session_percent() >= 99 > mixed


def test_shape_separates_regular_from_messy_juggling():
    from shape_consistency import load_regularity_runs, session_percent_for_recording
    scores = {r["meta"]["run"]: session_percent_for_recording(r) for r in load_regularity_runs()}
    assert sorted(scores) == sorted(run_id for run_id, _, _ in EXPECTED_SHAPE)
    for run_id, label, expected in EXPECTED_SHAPE:
        assert scores[run_id] == expected, f"{run_id} ({label})"
    regular = [scores[rid] for rid, label, _ in EXPECTED_SHAPE if label.startswith("regular")]
    messy = [scores[rid] for rid, label, _ in EXPECTED_SHAPE if label.endswith("messy")]
    assert min(regular) - max(messy) >= 20


def test_shape_constants_match_on_every_watch():
    """Window, lags and cadence are the same in the reference and both watches."""
    import shape_consistency as ref
    mc = _read_source("connectiq", "source", "ShapeConsistency.mc")
    kt = _read_source("shared", "src", "main", "java", "com", "juggling", "tracker",
                      "shared", "ShapeConsistency.kt")
    for name in ("WINDOW", "MIN_LAG", "MAX_LAG", "SCORE_EVERY", "MAX_PENDING"):
        value = getattr(ref, name)
        assert re.search(rf"const {name} = {value};", mc), f"{name} in ShapeConsistency.mc"
        assert re.search(rf"const val {name} = {value}\b", kt), f"{name} in ShapeConsistency.kt"
    assert f"const HISTORY = {ref.HISTORY};" in mc


def test_watch_shows_and_transfers_shape_consistency():
    detector = _read_source("connectiq", "source", "JugglingDetector.mc")
    main_view = _read_source("connectiq", "source", "MainView.mc")
    session = _read_source("connectiq", "source", "TrackerSession.mc")
    assert "public function shapeConsistencyPercent() as Number" in detector
    assert "_shape.addSample(gxMilliG, gyMilliG, gzMilliG, nowMs," in detector
    assert "_shape.onCatch(_lastCatchTime);" in detector
    assert "_shape.commitRun();" in detector
    assert "_shape.clearRun();" in detector
    assert "_shape.discardLastRun();" in detector
    assert '"Regularity: " + Format.percentOrDash(detector.shapeConsistencyPercent())' in main_view
    assert 'payload["shapeConsistency"] = shape;' in session
