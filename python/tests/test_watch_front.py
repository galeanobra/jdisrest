"""tools/watch_front.py, which saves and summarizes the archive snapshots of a run."""
import csv
import importlib.util
import itertools
import math
import os
import random
import shutil
import subprocess
import sys
import time
from datetime import datetime, timedelta
from pathlib import Path

import pytest

# tools/ is not a package: load the script by path, so no installed "tools" package can shadow it.
_SPEC = importlib.util.spec_from_file_location("watch_front", Path(__file__).parents[1] / "tools" / "watch_front.py")
watch_front = importlib.util.module_from_spec(_SPEC)
sys.modules[_SPEC.name] = watch_front  # dataclasses look the module up while it runs
_SPEC.loader.exec_module(watch_front)


# The traces TraceWriter writes for each shape of solution (TraceWriterTest pins them on the Java side).
GOLDEN = Path(__file__).parent / "data" / "traces"
BITS_30 = ["000000000000000000000000000101", "110010000000000000000000000001"]
# The variables of the two solutions of each golden front, as front_extremes.csv saves them.
GOLDEN_FRONTS = {
    "double": (["0.25", "1e-05"], ["-3.5", "2.5"]),
    "int": (["3", "-7", "0"], ["10", "2", "-10"]),
    "binary": ([BITS_30[0], "00000", "1"], [BITS_30[1], "00110", "0"]),
    "int-double": (["3", "-7", "0.25"], ["10", "2", "1e-05"]),
    "int-binary": (["3", "-7", "00000101", "000"], ["10", "2", "11000000", "010"]),
    "double-binary": (["0.25", "1e-05", "00110"], ["-3.5", "2.5", "10000"]),
    "int-double-binary": (["3", "-7", "0.25", "101", "00110"], ["10", "2", "1e-05", "000", "00000"]),
    "binary-binary": (["0101", "10", "000000"], ["0000", "01", "111000"]),
}


# ── Fixtures ────────────────────────────────────────────────────────────────

def _lines(rows):
    return "".join(",".join(map(str, row)) + "\n" for row in rows)


def _write_snapshot(folder, evaluations, objectives, variables=None):
    """Writes the four trace files the master writes for a snapshot."""
    variables = variables or [[float(i), float(i) + 0.5] for i in range(len(objectives))]
    for prefix in ("", "a"):
        (folder / f"{prefix}VAR_{evaluations}.csv").write_text(_lines(variables))
        (folder / f"{prefix}FUN_{evaluations}.csv").write_text(_lines(objectives))


def _settle(folder, evaluations):
    """Makes the trace files of a snapshot look written two minutes ago."""
    past = time.time() - 120
    for path in folder.glob(f"*_{evaluations}.csv"):
        os.utime(path, (past, past))


def _trace_evaluations(folder):
    return sorted({int(watch_front.ANY_TRACE.fullmatch(p.name).group(1)) for p in folder.iterdir()
                   if watch_front.ANY_TRACE.fullmatch(p.name)})


def _csv(path):
    with path.open(newline="", encoding="utf-8") as file:
        return list(csv.reader(file))


def _saved(traces):
    return [row[0] for row in _csv(traces.parent / "front_stats.csv")[1:]]


@pytest.fixture(autouse=True)
def no_settle_time(monkeypatch):
    """Snapshots written by the tests count as complete at once."""
    monkeypatch.setattr(watch_front, "SETTLE_SECONDS", 0.0)


@pytest.fixture
def traces(tmp_path):
    folder = tmp_path / "traces"
    folder.mkdir()
    return folder


# ── Reading traces ──────────────────────────────────────────────────────────

def test_latest_snapshot_is_the_largest_evaluation_count_of_an_archive_trace(traces):
    for name in ["aFUN_900.csv", "aFUN_1000.csv", "FUN_2000.csv", "aVAR_3000.csv", "notes.txt"]:
        (traces / name).write_text("")

    assert watch_front.latest_snapshot(traces) == 1000


def test_latest_snapshot_of_a_folder_without_archive_traces_is_none(traces):
    assert watch_front.latest_snapshot(traces) is None


def test_trace_without_final_newline_is_still_being_written(traces):
    path = traces / "aFUN_100.csv"
    path.write_text("1.0,2.0\n3.0,4")

    with pytest.raises(ValueError, match="still being written"):
        watch_front.read_rows(path)


def test_trace_with_rows_of_different_lengths_is_rejected(traces):
    path = traces / "aFUN_100.csv"
    path.write_text("1.0,2.0\n3.0\n")

    with pytest.raises(ValueError, match="different lengths"):
        watch_front.read_rows(path)


def test_a_value_that_is_not_a_number_is_rejected_with_its_file_and_line(traces):
    path = traces / "aFUN_100.csv"
    path.write_text("1.0,2.0\n3.0,abc\n")

    with pytest.raises(ValueError, match="aFUN_100.csv line 2"):
        watch_front.read_rows(path)


def test_rows_written_with_windows_line_endings_are_read(traces):
    path = traces / "aFUN_100.csv"
    path.write_bytes(b"1.0,2.0\r\n3.0,4.0\r\n")

    assert watch_front.read_rows(path) == [[1.0, 2.0], [3.0, 4.0]]


def test_composite_variable_rows_are_read_up_to_their_objectives(traces):
    path = traces / "aVAR_100.csv"
    path.write_text("3 4 0.5,[1.0  2.0],[]\n5 6 0.25,[2.0  1.0],[-1.0]\n")

    assert watch_front.read_rows(path) == [[3.0, 4.0, 0.5], [5.0, 6.0, 0.25]]
    assert watch_front.read_points(path) == [[3.0, 4.0, 0.5], [5.0, 6.0, 0.25]]


def test_variables_written_with_digits_only_are_read_as_text(traces):
    path = traces / "aVAR_100.csv"
    path.write_text("001001,100,0\n000000,111,1\n")

    assert watch_front.read_variable_rows(path) == [["001001", "100", "0"], ["000000", "111", "1"]]
    assert watch_front.read_rows(path) == [[1001.0, 100.0, 0.0], [0.0, 111.0, 1.0]]  # how 1.2.1 read them


def test_real_variables_are_read_as_floats_next_to_integers_and_bit_strings(traces):
    path = traces / "aVAR_100.csv"
    path.write_text("3 -7 0.25 1.0E-5 00110,[1.0  2.0],[]\n+4 0 -0.0 2.0 11111,[2.0  1.0],[-1.0]\n")

    assert watch_front.read_variable_rows(path) == [["3", "-7", 0.25, 1e-05, "00110"], ["+4", "0", -0.0, 2.0, "11111"]]


def test_a_variable_that_is_neither_digits_nor_a_number_is_rejected_with_its_file_and_line(traces):
    path = traces / "aVAR_100.csv"
    path.write_text("101,0.5\n10b,0.5\n")

    with pytest.raises(ValueError, match="aVAR_100.csv line 2"):
        watch_front.read_variable_rows(path)


def test_read_points_accepts_commas_and_spaces(traces):
    path = traces / "front.csv"
    path.write_text("0 4\n2,2\n4, 0\n")

    assert watch_front.read_points(path) == [[0.0, 4.0], [2.0, 2.0], [4.0, 0.0]]


# ── Fronts, extremes and the best compromise ────────────────────────────────

def test_non_dominated_drops_dominated_rows_and_keeps_identical_ones():
    rows = [[1.0, 2.0], [2.0, 1.0], [2.0, 2.0], [1.0, 2.0]]

    assert watch_front.non_dominated(rows) == [0, 1, 3]


def test_extreme_of_each_objective_breaks_ties_with_the_other_objectives_and_counts_them():
    rows = [[0.0, 5.0, 1.0], [0.0, 3.0, 2.0], [4.0, 0.0, 9.0], [9.0, 9.0, 0.0]]

    assert watch_front.extremes(rows, [0, 1, 2, 3]) == [(1, 2), (2, 1), (3, 1)]


def test_summary_averages_only_the_non_dominated_solutions_and_reads_the_extremes_variables(traces):
    objectives = [[1.0, 4.0], [3.0, 2.0], [5.0, 5.0]]  # the last one is dominated
    _write_snapshot(traces, 200, objectives, variables=[[10.0], [20.0], [30.0]])

    summary = watch_front.summarize(traces, 200)

    assert summary.size == 2
    assert summary.means == [2.0, 3.0]
    assert [(e.row, e.objectives, e.variables) for e in summary.extremes] == [
        (1, [1.0, 4.0], [10.0]), (2, [3.0, 2.0], [20.0])]


def test_best_compromise_is_the_solution_closest_to_the_ideal_point_of_the_normalized_front():
    rows = [[0.0, 10.0], [10.0, 0.0], [4.0, 4.0], [5.0, 5.0]]

    # Normalized to [0, 1], (4, 4) is at sqrt(0.32) from the ideal point and the extremes at 1.
    assert watch_front.compromise(rows, [0, 1, 2]) == (2, 1)


def test_best_compromise_uses_the_given_bounds_instead_of_those_of_the_front():
    rows = [[2.0, 8.0], [5.0, 3.0], [8.0, 1.0]]

    # Between the front's own bounds (5, 3) is the most balanced; with a first objective ranging
    # over 100 and a second over 10, (8, 1) is the closest to the ideal point.
    assert watch_front.compromise(rows, [0, 1, 2]) == (1, 1)
    assert watch_front.compromise(rows, [0, 1, 2], [0.0, 0.0], [100.0, 10.0]) == (2, 1)


def test_a_solution_better_than_the_ideal_point_is_not_pushed_away_from_it():
    rows = [[-1.0, 0.6], [0.5, 0.5]]

    # (-1, 0.6) beats the ideal point in the first objective: its distance is 0.6, not sqrt(1.36).
    assert watch_front.compromise(rows, [0, 1], [0.0, 0.0], [1.0, 1.0]) == (0, 1)


def test_an_objective_that_is_constant_in_the_front_does_not_count_for_the_compromise():
    assert watch_front.compromise([[1.0, 3.0], [1.0, 2.0]], [0, 1]) == (1, 1)


def test_the_best_compromise_follows_the_bounds_of_the_reference(traces, capsys):
    _write_snapshot(traces, 100, [[2.0, 8.0], [5.0, 3.0], [8.0, 1.0]])

    watch_front.main([str(traces), "--once", "--ideal-point", "0", "0", "--reference-point", "100", "10"])

    compromise_line = next(line for line in capsys.readouterr().out.splitlines()
                           if line.split()[:1] == ["compromise"])
    assert compromise_line.split()[1:5] == ["8", "1", "row", "3"]
    assert _csv(traces.parent / "front_extremes.csv")[-1][:4] == ["100", "compromise", "3", "1"]


def test_summary_rejects_variables_and_objectives_of_different_lengths(traces):
    _write_snapshot(traces, 200, [[1.0, 4.0], [3.0, 2.0]], variables=[[10.0]])

    with pytest.raises(ValueError, match="different lengths"):
        watch_front.summarize(traces, 200)


def test_empty_snapshot_has_no_extremes_and_prints_only_its_heading(traces):
    _write_snapshot(traces, 100, [])

    summary = watch_front.summarize(traces, 100)

    assert (summary.size, summary.extremes, summary.means) == (0, [], [])
    assert watch_front.format_summary(summary, ["f1"], None).startswith("== 100 evaluations: 0 non-dominated")


def test_format_shows_labels_normalizes_negative_zero_and_the_change_since_the_previous_snapshot(traces):
    _write_snapshot(traces, 100, [[2.0, 6.0], [6.0, 2.0]])
    _write_snapshot(traces, 200, [[-0.0, 5.0], [5.0, 1.0]])
    previous = watch_front.summarize(traces, 100)
    current = watch_front.summarize(traces, 200)

    lines = watch_front.format_summary(current, ["cost", "time"], previous).splitlines()

    assert lines[0].startswith("== 200 evaluations: 2 non-dominated solutions (aFUN_200.csv, written ")
    assert lines[1].split() == ["cost", "time"]
    assert lines[2].split() == ["minimum", "of", "cost", "0", "5", "row", "1"]
    assert lines[3].split() == ["minimum", "of", "time", "5", "1", "row", "2"]
    # Normalized by the front, both solutions are as close to the ideal point: the first one is shown.
    assert lines[4].split() == ["compromise", "0", "5", "row", "1", "(2", "as", "close", "to", "the", "ideal",
                                "point)"]
    assert lines[5].split() == ["mean", "2.5", "3"]
    assert lines[6].split() == ["change", "of", "the", "mean", "-1.5", "-1", "since", "100", "evaluations"]


# ── Saving, restarting and deleting ─────────────────────────────────────────

def test_every_snapshot_is_saved_in_order_but_only_the_latest_is_printed(traces, capsys):
    _write_snapshot(traces, 100, [[2.0, 6.0], [6.0, 2.0]])
    _write_snapshot(traces, 200, [[1.0, 5.0], [5.0, 1.0]], variables=[[7.0, 8.0], [9.0, 10.0]])

    status = watch_front.main([str(traces), "--once", "--labels", "cost", "time"])

    output = capsys.readouterr().out
    assert status == 0
    assert "== 200 evaluations" in output and "== 100 evaluations" not in output
    assert "(saved 2 snapshots to front_stats.csv)" in output
    stats = _csv(traces.parent / "front_stats.csv")
    assert stats[0] == ["evaluations", "written", "solutions", "mean_cost", "mean_time"]
    assert [row[0] for row in stats[1:]] == ["100", "200"]
    assert [row[2:] for row in stats[1:]] == [["2", "4.0", "4.0"], ["2", "3.0", "3.0"]]
    extremes = _csv(traces.parent / "front_extremes.csv")
    assert extremes[0] == ["evaluations", "extreme_of", "row", "ties", "cost", "time", "x1", "x2"]
    assert extremes[4:] == [["200", "cost", "1", "1", "1.0", "5.0", "7.0", "8.0"],
                            ["200", "time", "2", "1", "5.0", "1.0", "9.0", "10.0"],
                            ["200", "compromise", "1", "2", "1.0", "5.0", "7.0", "8.0"]]


def test_a_snapshot_of_composite_solutions_is_saved_with_its_variables(traces):
    (traces / "aFUN_100.csv").write_text("1.0,2.0\n2.0,1.0\n")
    (traces / "aVAR_100.csv").write_text("3 4 0.5,[1.0  2.0],[]\n5 6 0.25,[2.0  1.0],[]\n")

    assert watch_front.main([str(traces), "--once"]) == 0

    extremes = _csv(traces.parent / "front_extremes.csv")
    assert extremes[0][-3:] == ["x1", "x2", "x3"]
    assert extremes[1][-3:] == ["3", "4", "0.5"], "integers as written, reals as before"


def test_real_variables_are_shown_with_six_digits_and_saved_in_full_as_before(traces, capsys):
    (traces / "aFUN_100.csv").write_text("1.0,2.0\n2.0,1.0\n")
    (traces / "aVAR_100.csv").write_text("3 0.123456789 1.0 00110,[1.0  2.0],[]\n5 -2.5E-7 2.0 11111,[2.0  1.0],[]\n")

    assert watch_front.main([str(traces), "--once", "--variables"]) == 0

    first, second = ["3", "0.123456789", "1.0", "00110"], ["5", "-2.5e-07", "2.0", "11111"]
    extremes = _csv(traces.parent / "front_extremes.csv")
    assert [row[-4:] for row in extremes[1:]] == [first, second, first], "reals saved in full, as 1.2.1 saved them"
    shown = [line.split("variables: ")[1] for line in capsys.readouterr().out.splitlines() if "variables: " in line]
    first, second = "3, 0.123457, 1, 00110", "5, -2.5e-07, 2, 11111"
    assert shown == [first, second, first], "reals shown with 6 significant digits, like the objectives, as 1.2.1 did"


@pytest.mark.parametrize("shape", sorted(GOLDEN_FRONTS))
def test_the_traces_of_every_encoding_are_saved_and_shown_with_their_variables(traces, capsys, shape):
    for path in (GOLDEN / shape).iterdir():
        shutil.copy(path, traces)

    assert watch_front.main([str(traces), "--once", "--variables"]) == 0

    first, second = GOLDEN_FRONTS[shape]
    width = len(first)
    extremes = _csv(traces.parent / "front_extremes.csv")
    assert extremes[0] == ["evaluations", "extreme_of", "row", "ties", "f1", "f2", *(f"x{i + 1}" for i in range(width))]
    assert extremes[1:] == [["100", "f1", "1", "1", "0.0001", "4.0", *first],
                            ["100", "f2", "2", "1", "2.0", "1.0", *second],
                            ["100", "compromise", "1", "2", "0.0001", "4.0", *first]]
    shown = [line.split("variables: ")[1] for line in capsys.readouterr().out.splitlines() if "variables: " in line]
    assert shown == [", ".join(first), ", ".join(second), ", ".join(first)]


def test_golden_traces_have_a_front_for_every_shape():
    assert sorted(path.name for path in GOLDEN.iterdir()) == sorted(GOLDEN_FRONTS)


def test_restarting_on_the_same_folder_does_not_repeat_saved_snapshots(traces):
    _write_snapshot(traces, 100, [[2.0, 6.0]])
    watch_front.main([str(traces), "--once"])
    _write_snapshot(traces, 200, [[1.0, 5.0]])

    watch_front.main([str(traces), "--once"])

    assert _saved(traces) == ["100", "200"]
    assert [row[0] for row in _csv(traces.parent / "front_extremes.csv")[1:]] == ["100"] * 3 + ["200"] * 3


def test_a_save_interrupted_before_its_statistics_does_not_repeat_rows_on_a_restart(traces, monkeypatch):
    _write_snapshot(traces, 100, [[1.0, 2.0], [2.0, 1.0]])
    append = watch_front.Recorder._append

    def full_disk(self, path, header, rows, evaluations=None):
        if path.name == watch_front.STATS_FILE:
            raise OSError("no space left on device")
        append(self, path, header, rows, evaluations)

    monkeypatch.setattr(watch_front.Recorder, "_append", full_disk)
    with pytest.raises(OSError):
        watch_front.main([str(traces), "--once"])
    monkeypatch.setattr(watch_front.Recorder, "_append", append)

    watch_front.main([str(traces), "--once"])

    assert _saved(traces) == ["100"]
    assert [row[1] for row in _csv(traces.parent / "front_extremes.csv")[1:]] == ["f1", "f2", "compromise"]


def test_labels_other_than_those_of_the_saved_files_stop_the_script(traces):
    _write_snapshot(traces, 100, [[2.0, 6.0]])
    watch_front.main([str(traces), "--once"])
    _write_snapshot(traces, 200, [[1.0, 5.0]])

    with pytest.raises(SystemExit, match="has other columns"):
        watch_front.main([str(traces), "--once", "--labels", "cost", "time"])


def test_labels_are_saved_as_utf8_and_accepted_again_on_a_restart(traces):
    labels = ["Δcost", "time_µs"]
    _write_snapshot(traces, 100, [[2.0, 6.0]])
    watch_front.main([str(traces), "--once", "--labels", *labels])
    _write_snapshot(traces, 200, [[1.0, 5.0]])

    watch_front.main([str(traces), "--once", "--labels", *labels])

    header = (traces.parent / "front_stats.csv").read_bytes().decode("utf-8").splitlines()[0]
    assert header == "evaluations,written,solutions,mean_Δcost,mean_time_µs"
    assert _saved(traces) == ["100", "200"]


def test_labels_the_output_encoding_cannot_show_are_printed_with_escapes(traces):
    _write_snapshot(traces, 100, [[2.0, 6.0]])
    _settle(traces, 100)

    # A redirected stdout on Windows uses the locale encoding, such as cp1252, which has no Δ.
    run = subprocess.run([sys.executable, watch_front.__file__, str(traces), "--once", "--labels", "Δcost", "time"],
                         capture_output=True, timeout=60, env={**os.environ, "PYTHONIOENCODING": "cp1252"})

    assert run.returncode == 0, run.stderr.decode("cp1252", "replace")
    assert b"minimum of \\u0394cost" in run.stdout


def test_delete_keeps_the_latest_snapshot_and_the_multiples_of_keep_every(traces):
    for evaluations in (100, 200, 300, 400, 500):
        _write_snapshot(traces, evaluations, [[float(evaluations), 1.0]])

    watch_front.main([str(traces), "--once", "--delete-traces", "--keep-every", "200"])

    assert _trace_evaluations(traces) == [200, 400, 500]
    assert sorted(p.name for p in traces.iterdir() if "_500" in p.name) == [
        "FUN_500.csv", "VAR_500.csv", "aFUN_500.csv", "aVAR_500.csv"]
    assert _saved(traces) == ["100", "200", "300", "400", "500"]


def test_without_delete_traces_no_trace_is_deleted(traces):
    for evaluations in (100, 200, 300):
        _write_snapshot(traces, evaluations, [[float(evaluations), 1.0]])

    watch_front.main([str(traces), "--once"])

    assert _trace_evaluations(traces) == [100, 200, 300]


def test_a_new_run_in_a_saved_traces_folder_stops_the_script_before_anything_is_skipped_or_deleted(traces):
    for evaluations in (100, 200):
        _write_snapshot(traces, evaluations, [[5.0, 5.0]])
        _settle(traces, evaluations)
    watch_front.main([str(traces), "--once", "--delete-traces"])
    # The master runs again in the same folder, which it never cleans.
    for evaluations in (100, 200):
        _write_snapshot(traces, evaluations, [[1.0, 1.0]])

    with pytest.raises(SystemExit, match="another --output-dir"):
        watch_front.main([str(traces), "--once", "--delete-traces"])

    assert _trace_evaluations(traces) == [100, 200]
    assert [row[3] for row in _csv(traces.parent / "front_stats.csv")[1:]] == ["5.0", "5.0"]


def test_a_new_run_written_while_the_script_follows_the_folder_stops_it(traces):
    _write_snapshot(traces, 100, [[5.0, 5.0]])
    _settle(traces, 100)

    def sleep(seconds):
        _write_snapshot(traces, 100, [[1.0, 1.0]])  # the master restarted in the same folder

    with pytest.raises(SystemExit, match="another --output-dir"):
        watch_front.watch(traces, None, False, False, 15.0, traces.parent, delete=True, sleep=sleep)

    assert _trace_evaluations(traces) == [100]


def test_traces_written_after_their_snapshot_was_saved_are_never_deleted(traces):
    for evaluations in (100, 200):
        _write_snapshot(traces, evaluations, [[1.0, 1.0]])
    long_ago = datetime.now().replace(microsecond=0) - timedelta(minutes=2)

    watch_front.delete_saved_traces(traces, {100: long_ago, 200: long_ago}, keep=1, keep_every=None)

    assert _trace_evaluations(traces) == [100, 200]


def test_an_unreadable_snapshot_is_reported_once_and_never_deleted(traces, capsys):
    (traces / "aFUN_100.csv").write_text("1.0,2.0\n3.0\n")
    _write_snapshot(traces, 200, [[2.0, 6.0]])
    _write_snapshot(traces, 300, [[1.0, 5.0]])

    watch_front.main([str(traces), "--once", "--delete-traces"])

    assert capsys.readouterr().err.count("Cannot read the snapshot of 100 evaluations") == 1
    assert _trace_evaluations(traces) == [100, 300]


@pytest.mark.parametrize("objectives, variables", [
    ("1.0,2.0\n2.0,1.0\n", "0.0,0.5\n1.0,1"),  # aVAR still being written
    ("1.0,2.0\n2.0,1.0\n", "0.0,0.5\n"),  # aVAR cut at the end of a row
    ("1.0,2.0\n2.0,1.0\n", None),  # aVAR not created yet
    ("1.0,2.0\n2.0,1", None),  # aFUN still being written although it looks settled (a server clock behind)
])
def test_a_snapshot_read_while_it_is_being_written_is_saved_on_a_later_pass(traces, capsys, objectives, variables):
    (traces / "aFUN_100.csv").write_text(objectives)
    if variables is not None:
        (traces / "aVAR_100.csv").write_text(variables)
    passes = []

    def sleep(seconds):
        passes.append(seconds)
        (traces / "aFUN_100.csv").write_text(_lines([[1.0, 2.0], [2.0, 1.0]]))
        (traces / "aVAR_100.csv").write_text(_lines([[0.0, 0.5], [1.0, 1.5]]))

    status = watch_front.watch(traces, None, False, True, 15.0, traces.parent, sleep=sleep)

    assert status == 0
    assert len(passes) == 1
    assert _saved(traces) == ["100"]
    assert capsys.readouterr().err == ""


def test_once_gives_up_on_a_snapshot_whose_files_stay_unreadable(traces, capsys):
    (traces / "aFUN_100.csv").write_text("1.0,x\n")
    (traces / "aVAR_100.csv").write_text("0.0\n")
    passes = []

    status = watch_front.watch(traces, None, False, True, 15.0, traces.parent, sleep=passes.append)

    assert status == 1
    assert len(passes) == watch_front.UNREADABLE_PASSES - 1
    assert capsys.readouterr().err.count("Cannot read the snapshot of 100 evaluations: aFUN_100.csv line 1") == 1


def test_a_snapshot_deleted_before_it_is_read_is_skipped(traces, monkeypatch):
    _write_snapshot(traces, 100, [[1.0, 2.0]])
    listed = watch_front.snapshots
    monkeypatch.setattr(watch_front, "snapshots", lambda folder: [50] + listed(folder))

    assert watch_front.main([str(traces), "--once"]) == 0

    assert _saved(traces) == ["100"]


def test_a_snapshot_that_changes_while_it_is_read_is_saved_with_the_time_of_the_rows_read(traces, monkeypatch):
    _write_snapshot(traces, 100, [[1.0, 2.0]])
    _settle(traces, 100)
    read_rows = watch_front.read_rows
    changed = []

    def read_and_change(path):
        rows = read_rows(path)
        if path.name == "aFUN_100.csv" and not changed:
            changed.append(path)  # the last write of the master lands just after the read
            later = time.time() - 30
            os.utime(path, (later, later))
        return rows

    monkeypatch.setattr(watch_front, "read_rows", read_and_change)
    assert watch_front.main([str(traces), "--once"]) == 0
    monkeypatch.setattr(watch_front, "read_rows", read_rows)

    written = datetime.fromisoformat(_csv(traces.parent / "front_stats.csv")[1][1])
    assert written == datetime.fromtimestamp((traces / "aFUN_100.csv").stat().st_mtime).replace(microsecond=0)
    assert watch_front.main([str(traces), "--once"]) == 0, "a restart does not take the snapshot for another run"


def test_watch_saves_and_deletes_as_new_snapshots_appear_and_prints_each_once(traces, capsys):
    _write_snapshot(traces, 100, [[2.0, 6.0], [6.0, 2.0]])
    passes = []

    def sleep(seconds):
        passes.append(seconds)
        if len(passes) == 2:
            _write_snapshot(traces, 200, [[1.0, 5.0], [5.0, 1.0]])
        if len(passes) == 4:
            raise KeyboardInterrupt

    with pytest.raises(KeyboardInterrupt):
        watch_front.watch(traces, None, False, False, 7.0, traces.parent, delete=True, sleep=sleep)

    output = capsys.readouterr().out
    assert output.count("== 100 evaluations") == 1
    assert output.count("== 200 evaluations") == 1
    assert "change of the mean" in output.split("== 200 evaluations")[1]
    assert passes == [7.0] * 4
    assert _trace_evaluations(traces) == [200]
    assert _saved(traces) == ["100", "200"]


def _snapshot_every_pass(traces, monkeypatch, passes, stop):
    """
    Starts with a settled snapshot of 100 evaluations and a newer one still being written. Each
    pass settles the newest and writes another, as a master that writes a snapshot every second.
    The sleep after the given number of passes raises ``stop``.
    """
    monkeypatch.setattr(watch_front, "SETTLE_SECONDS", 60.0)
    _write_snapshot(traces, 100, [[2.0, 6.0]])
    _settle(traces, 100)
    _write_snapshot(traces, 200, [[1.0, 5.0]])
    done = []

    def sleep(seconds):
        done.append(seconds)
        if len(done) == passes:
            raise stop
        newest = 100 * (len(done) + 1)
        _settle(traces, newest)
        _write_snapshot(traces, newest + 100, [[1.0, 5.0 - len(done)]])

    return sleep


def test_watch_prints_the_latest_saved_snapshot_while_the_latest_file_is_still_being_written(
        traces, capsys, monkeypatch):
    sleep = _snapshot_every_pass(traces, monkeypatch, passes=2, stop=KeyboardInterrupt)

    with pytest.raises(KeyboardInterrupt):
        watch_front.watch(traces, None, False, False, 15.0, traces.parent, delete=True, sleep=sleep)

    output = capsys.readouterr().out
    assert output.count("== 100 evaluations") == 1
    assert output.count("== 200 evaluations") == 1
    assert "== 300 evaluations" not in output
    assert _saved(traces) == ["100", "200"]
    assert _trace_evaluations(traces) == [200, 300]


def test_once_ends_after_the_latest_snapshot_it_found_even_if_newer_ones_keep_coming(traces, capsys, monkeypatch):
    sleep = _snapshot_every_pass(traces, monkeypatch, passes=3, stop=AssertionError("--once did not end"))

    status = watch_front.watch(traces, None, False, True, 15.0, traces.parent, sleep=sleep)

    assert status == 0
    assert capsys.readouterr().out.count("== 200 evaluations") == 1


# ── Indicators ──────────────────────────────────────────────────────────────

def _inclusion_exclusion_hypervolume(points, reference):
    """Adds and subtracts the boxes of every subset of points: slow, but obviously right."""
    points = [p for p in points if all(v < r for v, r in zip(p, reference))]
    total = 0.0
    for size in range(1, len(points) + 1):
        for subset in itertools.combinations(points, size):
            total += (-1) ** (size + 1) * math.prod(r - max(c) for c, r in zip(zip(*subset), reference))
    return total


def test_hypervolume_of_two_points_is_the_area_of_the_union_of_their_boxes():
    assert watch_front.hypervolume([[1, 2], [2, 1]], [3, 3]) == 3.0


def test_points_not_better_than_the_reference_point_in_every_objective_add_no_hypervolume():
    assert watch_front.hypervolume([[1, 1], [3, 0.5], [0.5, 4]], [3, 3]) == 4.0


@pytest.mark.parametrize("dimensions", [2, 3, 4, 5])
def test_hypervolume_matches_inclusion_exclusion(dimensions):
    rng = random.Random(dimensions)
    for _ in range(20):
        points = [[rng.random() for _ in range(dimensions)] for _ in range(rng.randint(1, 8))]
        reference = [1.0] * dimensions

        assert watch_front.hypervolume(points, reference) == pytest.approx(
            _inclusion_exclusion_hypervolume(points, reference), abs=1e-12)


@pytest.mark.parametrize("point, expected", [([-1.0, -1.0], 1.0), ([-0.5, 0.5], 0.5), ([0.5, 0.5], 0.25)])
def test_the_hypervolume_counts_a_solution_beyond_the_lower_bound_as_on_it_like_jmetal(point, expected):
    reference = watch_front.make_reference(None, [0.0, 0.0], [1.0, 1.0], "")

    assert reference.indicators([point]).hypervolume == pytest.approx(expected)


def test_igd_is_jmetals_root_of_the_sum_of_squares_and_igd_plus_the_mean_dominance_distance():
    front, reference = [[0.0, 1.0]], [[0.0, 1.0], [1.0, 0.0]]

    assert watch_front.igd(front, reference) == pytest.approx(math.sqrt(0 + 2) / 2)
    assert watch_front.igd_plus(front, reference) == pytest.approx((0 + 1) / 2)


@pytest.fixture
def reference_front(traces):
    """Bounds (0, 0) and (4, 4); against it the front [(2, 2)] has HV 0.25, IGD 1/3 and IGD+ 1/3."""
    path = traces.parent / "reference.csv"
    path.write_text("0 4\n2 2\n4 0\n")
    return path


def test_indicators_against_a_reference_front_are_saved_and_shown(traces, reference_front, capsys):
    _write_snapshot(traces, 100, [[2.0, 2.0]])

    status = watch_front.main([str(traces), "--once", "--reference-front", str(reference_front)])

    output = capsys.readouterr().out
    assert status == 0
    assert _csv(traces.parent / "front_indicators.csv") == [
        ["evaluations", "hypervolume", "igd", "igd_plus"], ["100", "0.25", repr(1 / 3), repr(1 / 3)]]
    assert "lower_bound = 0.0, 0.0" in (traces.parent / "front_indicators_reference.txt").read_text()
    assert [line.split()[:2] for line in output.splitlines() if line.split()[0] in ("hypervolume", "IGD", "IGD+")] == [
        ["hypervolume", "0.25"], ["IGD", "0.333333"], ["IGD+", "0.333333"]]


def test_ideal_and_reference_points_without_a_front_give_only_the_hypervolume(traces, capsys):
    _write_snapshot(traces, 100, [[2.0, 2.0]])

    watch_front.main([str(traces), "--once", "--ideal-point", "0", "0", "--reference-point", "4", "4"])

    output = capsys.readouterr().out
    assert _csv(traces.parent / "front_indicators.csv")[1] == ["100", "0.25", "", ""]
    assert "hypervolume" in output.split("==")[-1] and "IGD+" not in output.split("==")[-1]


def test_the_change_of_each_indicator_is_shown_with_the_next_snapshot(traces, reference_front, capsys):
    _write_snapshot(traces, 100, [[2.0, 2.0]])
    reference = watch_front.load_reference(reference_front, None, None)
    passes = []

    def sleep(seconds):
        passes.append(seconds)
        if len(passes) == 1:
            _write_snapshot(traces, 200, [[1.0, 1.0]])
        if len(passes) == 2:
            raise KeyboardInterrupt

    with pytest.raises(KeyboardInterrupt):
        watch_front.watch(traces, None, False, False, 7.0, traces.parent, sleep=sleep, reference=reference)

    second = capsys.readouterr().out.split("== 200 evaluations")[1]
    assert "change +0.3125" in second  # hypervolume from 0.25 to 0.75 ** 2
    assert "(higher is better)" in second
    assert [row[0] for row in _csv(traces.parent / "front_indicators.csv")[1:]] == ["100", "200"]


def test_another_reference_on_the_same_output_folder_stops_the_script(traces, reference_front):
    _write_snapshot(traces, 100, [[2.0, 2.0]])
    watch_front.main([str(traces), "--once", "--reference-front", str(reference_front)])

    with pytest.raises(SystemExit, match="computed with another reference"):
        watch_front.main([str(traces), "--once", "--ideal-point", "0", "0", "--reference-point", "8", "8"])


def test_a_reference_with_another_number_of_objectives_stops_the_script(traces):
    _write_snapshot(traces, 100, [[2.0, 2.0]])

    with pytest.raises(SystemExit, match="the reference has 3 objectives but the snapshots have 2"):
        watch_front.main([str(traces), "--once", "--ideal-point", "0", "0", "0", "--reference-point", "4", "4", "4"])


def test_the_ideal_point_without_the_reference_point_or_a_front_is_an_error(traces):
    with pytest.raises(SystemExit):
        watch_front.main([str(traces), "--once", "--ideal-point", "0", "0"])


# ── Aggregated front ────────────────────────────────────────────────────────

def test_without_a_reference_no_indicators_are_computed_but_the_aggregated_front_is_saved(traces, capsys):
    _write_snapshot(traces, 100, [[2.0, 2.0]])
    _write_snapshot(traces, 200, [[1.0, 3.0], [3.0, 1.0]])

    watch_front.main([str(traces), "--once"])

    assert "hypervolume" not in capsys.readouterr().out
    assert not (traces.parent / "front_indicators.csv").exists()
    assert sorted(watch_front.read_points(traces.parent / "front_reference.csv")) == [[1, 3], [2, 2], [3, 1]]


def test_a_solution_that_is_dominated_later_leaves_the_aggregated_front(traces):
    _write_snapshot(traces, 100, [[2.0, 2.0]])
    _write_snapshot(traces, 200, [[1.0, 1.0]])

    watch_front.main([str(traces), "--once"])

    assert watch_front.read_points(traces.parent / "front_reference.csv") == [[1, 1]]


def test_a_restart_goes_on_with_the_saved_aggregated_front(traces):
    # (2, 2) is not an extreme, so only the saved aggregated front remembers it.
    _write_snapshot(traces, 100, [[1.0, 4.0], [2.0, 2.0], [4.0, 1.0]])
    watch_front.main([str(traces), "--once", "--delete-traces"])
    _write_snapshot(traces, 200, [[3.0, 0.5]])

    watch_front.main([str(traces), "--once"])

    assert sorted(watch_front.read_points(traces.parent / "front_reference.csv")) == [[1, 4], [2, 2], [3, 0.5]]


def test_without_a_saved_aggregated_front_a_restart_starts_from_the_saved_extremes(traces):
    _write_snapshot(traces, 100, [[1.0, 3.0], [3.0, 1.0]])
    watch_front.main([str(traces), "--once", "--delete-traces"])
    (traces.parent / "front_reference.csv").unlink()  # as after a run of an older version of the script
    _write_snapshot(traces, 200, [[2.0, 2.0]])

    watch_front.main([str(traces), "--once"])

    assert sorted(watch_front.read_points(traces.parent / "front_reference.csv")) == [[1, 3], [2, 2], [3, 1]]


def test_the_saved_extremes_are_found_even_with_labels_named_like_variables(traces):
    _write_snapshot(traces, 100, [[1.0, 3.0], [3.0, 1.0]])
    watch_front.main([str(traces), "--once", "--labels", "x1", "x2"])
    (traces.parent / "front_reference.csv").unlink()
    _write_snapshot(traces, 200, [[2.0, 2.0]])

    watch_front.main([str(traces), "--once", "--labels", "x1", "x2"])

    assert sorted(watch_front.read_points(traces.parent / "front_reference.csv")) == [[1, 3], [2, 2], [3, 1]]


def test_an_empty_point_never_joins_the_aggregated_front(tmp_path):
    front = watch_front.AggregatedFront(tmp_path / "front_reference.csv", tmp_path / "front_extremes.csv")

    front.add([[]])
    front.add([[1.0, 2.0]])

    # An empty point would be at least as good as any other, which would then never join.
    assert front.points == [[1.0, 2.0]]


# ── Recompute ───────────────────────────────────────────────────────────────

def test_recompute_measures_every_snapshot_against_the_aggregated_front_of_them_all(traces, capsys):
    _write_snapshot(traces, 100, [[3.0, 3.0]])
    _write_snapshot(traces, 200, [[1.0, 3.0], [3.0, 1.0]])

    status = watch_front.main([str(traces), "--recompute"])

    # The aggregated front is (1, 3) and (3, 1), normalized to (0, 1) and (1, 0); (3, 3) becomes (1, 1).
    rows = _csv(traces.parent / "front_indicators_recomputed.csv")
    assert status == 0
    assert rows[0] == ["evaluations", "hypervolume", "igd", "igd_plus"]
    assert [row[0] for row in rows[1:]] == ["100", "200"]
    assert float(rows[1][2]) == pytest.approx(math.sqrt(1 + 1) / 2)
    assert float(rows[1][3]) == pytest.approx(1.0)
    assert rows[2][2:] == ["0.0", "0.0"]
    assert "Saved to" in capsys.readouterr().out
    assert "2 points" in (traces.parent / "front_indicators_recomputed_reference.txt").read_text()


def test_recompute_uses_the_aggregated_front_of_snapshots_already_deleted(traces):
    # (1, 1) of the first snapshot only survives in front_reference.csv.
    _write_snapshot(traces, 100, [[1.0, 1.0]])
    _write_snapshot(traces, 200, [[2.0, 2.0]])
    watch_front.main([str(traces), "--once", "--delete-traces"])
    assert _trace_evaluations(traces) == [200]

    watch_front.main([str(traces), "--recompute", "--ideal-point", "0", "0", "--reference-point", "4", "4"])

    # Against (1, 1), scaled to (0.25, 0.25), the snapshot at (0.5, 0.5) is 0.25 * sqrt(2) away.
    row = _csv(traces.parent / "front_indicators_recomputed.csv")[1]
    assert row[0] == "200"
    assert float(row[1]) == pytest.approx(0.25)
    assert float(row[2]) == pytest.approx(0.25 * math.sqrt(2))


def test_recompute_does_not_change_the_aggregated_front(traces):
    _write_snapshot(traces, 100, [[2.0, 2.0]])
    watch_front.main([str(traces), "--once"])
    before = (traces.parent / "front_reference.csv").read_text()
    _write_snapshot(traces, 200, [[1.0, 1.0]])

    watch_front.main([str(traces), "--recompute", "--ideal-point", "0", "0", "--reference-point", "4", "4"])

    assert (traces.parent / "front_reference.csv").read_text() == before


def test_recompute_skips_only_the_newest_snapshot_while_it_may_still_be_being_written(traces, monkeypatch):
    monkeypatch.setattr(watch_front, "SETTLE_SECONDS", 60.0)
    _write_snapshot(traces, 100, [[1.0, 3.0]])  # just written, like a copy of an old snapshot
    _write_snapshot(traces, 200, [[3.0, 1.0]])

    watch_front.main([str(traces), "--recompute", "--ideal-point", "0", "0", "--reference-point", "4", "4"])

    assert [row[0] for row in _csv(traces.parent / "front_indicators_recomputed.csv")[1:]] == ["100"]


def test_recompute_reads_only_the_objectives_of_composite_runs(traces):
    (traces / "aFUN_100.csv").write_text("1.0,2.0\n2.0,1.0\n")
    (traces / "aVAR_100.csv").write_text("3 4 0.5,[1.0  2.0],[]\n5 6 0.25,[2.0  1.0],[]\n")

    assert watch_front.main([str(traces), "--recompute"]) == 0

    assert [row[0] for row in _csv(traces.parent / "front_indicators_recomputed.csv")[1:]] == ["100"]


def test_recompute_with_delete_traces_is_an_error(traces):
    with pytest.raises(SystemExit):
        watch_front.main([str(traces), "--recompute", "--delete-traces"])


# ── Command line ────────────────────────────────────────────────────────────

def test_once_without_snapshots_fails(traces, capsys):
    assert watch_front.main([str(traces), "--once"]) == 1
    assert "No aFUN_<n>.csv in" in capsys.readouterr().out


def test_wrong_number_of_labels_stops_the_script(traces):
    _write_snapshot(traces, 100, [[2.0, 6.0]])

    with pytest.raises(SystemExit, match="1 labels given for 2 objectives"):
        watch_front.main([str(traces), "--once", "--labels", "cost"])


@pytest.mark.parametrize("option", [["--keep", "0"], ["--keep-every", "0"]])
def test_keep_options_must_be_positive(traces, option):
    with pytest.raises(SystemExit):
        watch_front.main([str(traces), "--once", *option])


@pytest.mark.parametrize("interval", ["-1", "0", "1", "nan", "inf"])
def test_interval_must_be_at_least_the_settle_time(traces, interval):
    with pytest.raises(SystemExit) as stopped:
        watch_front.main([str(traces), "--once", "--interval", interval])  # --once: no endless loop
    assert stopped.value.code == 2


def test_missing_folder_fails(tmp_path):
    assert watch_front.main([str(tmp_path / "missing")]) == 1
