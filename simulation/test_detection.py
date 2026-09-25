"""
Tests for the juggling watch-hand catch detection algorithm.

Runs the exact watch simulation (from eval_new_watch.py) against all recorded
datasets with the current JugglingDetector.mc parameters, asserting that
detection performance does not regress.

The expected detection counts come from the delayed burst-clustering algorithm
with alternating watch-hand burst counting: total absolute error = 189 and
positive overcount error = 69 across 102 runs (2459 actual watch-hand catches).
"""
import os
import re
import sys
import pytest

# Ensure the simulation directory is importable.
sys.path.insert(0, os.path.dirname(__file__))

from eval_new_watch import simulate_watch
from data_utils import load_all_runs

# ── Current watch parameters (must match JugglingDetector.mc) ──────────────
WATCH_PARAMS = {
    3: {"threshold": 2.0, "refractory_ms": 80, "raw_gate": 7.0, "merge_window_ms": 160},
    4: {"threshold": 3.0, "refractory_ms": 80, "raw_gate": 11.0, "merge_window_ms": 160},
    5: {"threshold": 3.0, "refractory_ms": 40, "raw_gate": 13.0, "merge_window_ms": 160},
    6: {"threshold": 5.0, "refractory_ms": 40, "raw_gate": 17.0, "merge_window_ms": 120},
    7: {"threshold": 3.0, "refractory_ms": 160, "raw_gate": 7.0, "merge_window_ms": 80},
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
    ("20260922_151715", 7, 16, 14),
    ("20260922_151758", 7, 10, 9),
    ("20260922_151910", 7, 11, 9),
    ("20260922_151957", 7, 10, 8),
    ("20260922_152032", 7, 13, 13),
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
    ("20260922_154204", 7, 11, 11),
    ("20260922_154237", 7, 11, 10),
    ("20260922_154319", 7, 10, 10),
    ("20260922_154408", 7, 18, 17),
    ("20260922_154457", 7, 21, 16),
    ("20260922_154640", 7, 15, 13),
    ("20260922_154722", 7, 15, 11),
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
    ("20260924_171849", 7, 13, 11),
    ("20260924_171927", 7, 11, 13),
    ("20260924_172053", 7, 13, 12),
    ("20260924_172136", 7, 11, 11),
    ("20260924_172333", 5, 25, 25),
    ("20260924_172451", 5, 77, 71),
    ("20260924_172608", 5, 68, 67),
]

# Maximum allowed total absolute error across all runs.
MAX_TOTAL_ERROR = 189
MAX_TOTAL_OVERCOUNT = 69


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

    Current baseline: 148 total absolute error across 69 runs (1479 watch-hand catches).
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


def test_watch_startup_offers_recording_mode():
    """Startup opens the mode picker so users can contribute raw recordings.

    Recording mode ships enabled: the recordings users capture and send back
    are what grows the corpus these tests run against. Setting
    ENABLE_RECORDING_MODE to false must still skip straight to ball select,
    so both branches have to stay in place.
    """
    app_path = os.path.join(
        os.path.dirname(os.path.abspath(__file__)),
        "..", "connectiq", "source", "JugglingTrackerApp.mc",
    )
    if not os.path.exists(app_path):
        pytest.skip("JugglingTrackerApp.mc not found")

    with open(app_path, "r") as f:
        source = f.read()

    assert "private const ENABLE_RECORDING_MODE = true;" in source
    assert "new ModeSelectView()" in source
    assert "new BallSelectView(:juggle)" in source


def _read_source(*rel_parts):
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", *rel_parts)
    if not os.path.exists(path):
        pytest.skip(f"{os.path.join(*rel_parts)} not found")
    with open(path, "r") as f:
        return f.read()


def test_back_button_confirms_discarding_a_run_instead_of_exiting():
    """BACK during a juggling session must discard a run, never exit.

    Before this fix, MainDelegate had no handler for the back button at all,
    so it fell through to the system default: pop the only view on the
    stack, which exits the app and discards whatever had been juggled with no
    warning. Both onKey(KEY_ESC) and onBack() (different devices route the
    back gesture through different callbacks) must now prompt instead, and
    that prompt must be a yes/no confirmation -- the discard is destructive
    and a single button press away.
    """
    source = _read_source("connectiq", "source", "MainView.mc")

    m = re.search(r"class MainDelegate extends WatchUi\.BehaviorDelegate \{(.*)", source, re.DOTALL)
    assert m, "MainDelegate not found in MainView.mc"
    delegate_source = m.group(1)

    assert "WatchUi.KEY_ESC" in delegate_source, "MainDelegate.onKey must handle KEY_ESC"
    assert re.search(r"onKey\(evt as WatchUi\.KeyEvent\) as Boolean \{.*promptDiscardRun\(\)",
                      delegate_source, re.DOTALL)
    assert re.search(r"public function onBack\(\) as Boolean \{\s*_view\.promptDiscardRun\(\)",
                      delegate_source)

    m = re.search(r"public function promptDiscardRun\(\) as Void \{(.*?)\n    \}", source, re.DOTALL)
    assert m, "promptDiscardRun() not found in MainView.mc"
    prompt_body = m.group(1)

    # It must ask, not act, and ask with our own labels: WatchUi.Confirmation
    # renders its yes/no in the watch's system language, which put German
    # "Ja"/"Nein" in the middle of an otherwise English app.
    assert "new WatchUi.Confirmation(" not in source, (
        "WatchUi.Confirmation localises its yes/no labels to the watch language; "
        "use a Menu2 with our own English labels instead"
    )
    assert ":discard_yes" in prompt_body and ":discard_no" in prompt_body, (
        "back press must offer an explicit discard/keep choice before discarding"
    )
    assert "new DiscardRunDelegate(self)" in prompt_body

    # The prompt must name which run is at stake, because the two cases remove
    # different things: an active run is stopped and dropped, otherwise the
    # last completed run of the session is removed retroactively.
    assert "_detector.isRunActive()" in prompt_body
    assert "_detector.sessionRuns() > 0" in prompt_body

    # Nothing recorded yet means nothing to confirm -- but the press still has
    # to be swallowed, or it reaches the system and exits the app.
    assert re.search(r"\} else \{.*?\n\s*return;\n\s*\}", prompt_body, re.DOTALL), (
        "promptDiscardRun() must return early (not fall through) when there is "
        "no run to discard"
    )

    # Only a "yes" may touch session state.
    m = re.search(
        r"public function onDiscardResponse\(confirmed as Boolean\) as Void \{(.*?)\n    \}",
        source, re.DOTALL,
    )
    assert m, "onDiscardResponse() not found in MainView.mc"
    body = m.group(1)
    guarded = re.search(r"if \(confirmed\) \{(.*?)\n        \}", body, re.DOTALL)
    assert guarded, "onDiscardResponse() must guard the discard on the answer"
    assert "_detector.discardLastRun();" in guarded.group(1)
    assert "_detector.discardLastRun();" not in body.replace(guarded.group(0), ""), (
        "discardLastRun() must only run inside the if (confirmed) branch"
    )

    assert re.search(r'MenuItem\("Discard"', prompt_body), "the discard label must be English"
    assert re.search(r'MenuItem\("Keep"', prompt_body), "the keep label must be English"


def test_menus_over_main_view_clear_the_pending_decision_flag_on_back():
    """Backing out of a menu must not wedge MainView's _awaitingDecision flag.

    Menu2InputDelegate's default onBack pops the menu without telling the
    view, so _awaitingDecision stayed true forever: every later START/STOP
    press became a no-op and the session could no longer be ended or synced
    at all. Both menus pushed over MainView must override onBack and route it
    to the benign choice.
    """
    source = _read_source("connectiq", "source", "MainView.mc")

    for cls, handler in (
        ("DiscardRunDelegate", "onDiscardResponse(false)"),
        ("SessionEndDelegate", "onContinueSession()"),
    ):
        m = re.search(
            r"class %s extends WatchUi\.Menu2InputDelegate \{(.*?)\n\}" % cls,
            source, re.DOTALL,
        )
        assert m, f"{cls} not found in MainView.mc"
        body = m.group(1)
        back = re.search(r"public function onBack\(\) as Void \{(.*?)\n    \}", body, re.DOTALL)
        assert back, f"{cls} must override onBack()"
        # Overriding replaces the default pop, so it has to pop itself.
        assert "WatchUi.popView" in back.group(1), f"{cls}.onBack must pop the menu itself"
        assert handler in back.group(1), f"{cls}.onBack must call _view.{handler}"


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



def test_main_view_reacquires_sensor_after_any_menu():
    """Pushing a menu over MainView must not permanently freeze the screen.

    On real hardware, pushing any view on top of MainView (the session-end
    menu, the back-press menu) hides it and drops the accelerometer listener
    with it. Without re-registering in onShow(), popping back to MainView
    left the screen frozen forever and detection silently stopped -- whether
    the menu was reached via Start/Stop with no run active (frozen at
    "WAITING", count 0) or mid-run (frozen at whatever count it showed).
    """
    source = _read_source("connectiq", "source", "MainView.mc")

    assert re.search(r"public function onShow\(\) as Void \{\s*startSensor\(\);\s*\}", source), (
        "onShow() must re-acquire the sensor by calling startSensor()"
    )

    m = re.search(r"public function onHide\(\) as Void \{(.*?)\}", source, re.DOTALL)
    assert m, "onHide() not found in MainView.mc"
    assert "Sensor.unregisterSensorDataListener();" in m.group(1)
    assert "_sensorActive = false;" in m.group(1), (
        "onHide() must clear _sensorActive so the next onShow() re-registers"
    )

    # startSensor() has to be idempotent: onShow() fires every time the view
    # is (re-)shown, including right after initialize()'s own call.
    m = re.search(r"private function startSensor\(\) as Void \{(.*?)\n    \}", source, re.DOTALL)
    assert m, "startSensor() not found in MainView.mc"
    assert "if (_sensorActive) {\n            return;" in m.group(1)


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
        m = re.match(r"\*\*((?:APP|DET|RUN|JUG|SENS|SYNC|REC)-\d+)\.\*\*", line)
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
    for filename in ("DetectorTest.mc", "SelectionTest.mc"):
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
