"""tools/plot_front_evolution.py, which plots the archive snapshots of a run (skipped without numpy or matplotlib)."""
import importlib.util
import sys
from pathlib import Path

import pytest

pytest.importorskip("numpy")
pytest.importorskip("matplotlib")

# tools/ is not a package: load the script by path, so no installed "tools" package can shadow it.
_SPEC = importlib.util.spec_from_file_location("plot_front_evolution",
                                               Path(__file__).parents[1] / "tools" / "plot_front_evolution.py")
plot_front_evolution = importlib.util.module_from_spec(_SPEC)
sys.modules[_SPEC.name] = plot_front_evolution
_SPEC.loader.exec_module(plot_front_evolution)

OUTPUTS = ["front_evolution.gif", "front_evolution.png", "front_evolution_grid.png"]


# ── Fixtures ────────────────────────────────────────────────────────────────

def _write_front(folder, evaluations, rows):
    (folder / f"aFUN_{evaluations}.csv").write_text("".join(",".join(map(str, row)) + "\n" for row in rows))


def _outputs(folder):
    return sorted(p.name for p in folder.iterdir() if p.name.startswith("front_evolution"))


@pytest.fixture
def run(tmp_path):
    """A run folder with a traces subfolder."""
    (tmp_path / "traces").mkdir()
    return tmp_path


# ── Tests ───────────────────────────────────────────────────────────────────

def test_two_snapshots_give_the_figure_the_grid_and_the_animation(run, capsys):
    _write_front(run / "traces", 1000, [[3.0, 1.0], [1.0, 3.0]])
    _write_front(run / "traces", 2000, [[2.0, 0.5], [0.5, 2.0], [1.0, 1.0]])

    assert plot_front_evolution.main([str(run), "--labels", "cost", "time"]) == 0

    assert _outputs(run) == OUTPUTS
    output = capsys.readouterr().out
    assert "    1000 evaluations: 2 solutions" in output
    assert "    2000 evaluations: 3 solutions" in output


def test_three_objectives_and_a_traces_folder_given_directly_write_next_to_it(run):
    _write_front(run / "traces", 1000, [[3.0, 1.0, 2.0], [1.0, 3.0, 2.0]])

    plot_front_evolution.main([str(run / "traces")])

    assert _outputs(run) == OUTPUTS


def test_only_snapshots_at_multiples_of_the_step_are_plotted(run):
    for evaluations in (500, 1000, 1500, 2000):
        _write_front(run / "traces", evaluations, [[float(evaluations), 1.0]])

    fronts = plot_front_evolution.load_fronts(run / "traces", 1000, None)

    assert list(fronts) == [1000, 2000]


def test_an_empty_first_snapshot_is_an_empty_panel(run):
    _write_front(run / "traces", 1000, [])
    _write_front(run / "traces", 2000, [[2.0, 0.5], [0.5, 2.0]])

    fronts = plot_front_evolution.load_fronts(run / "traces", 1000, None)
    plot_front_evolution.main([str(run)])

    assert fronts[1000].shape == (0, 2)
    assert _outputs(run) == OUTPUTS


def test_excluded_solutions_are_left_out(run):
    _write_front(run / "traces", 1000, [[1.0, 2.0], [2.0, 1000.0]])

    fronts = plot_front_evolution.load_fronts(run / "traces", 1000, 1000.0)

    assert fronts[1000].tolist() == [[1.0, 2.0]]


def test_excluding_every_solution_stops_with_a_clear_message(run):
    _write_front(run / "traces", 1000, [[1000.0, 1.0]])
    _write_front(run / "traces", 2000, [[1.0, 2000.0]])

    with pytest.raises(SystemExit, match="Every solution has an objective of 1000 or more"):
        plot_front_evolution.main([str(run), "--exclude-above", "1000"])


def test_a_single_objective_stops_with_a_clear_message(run):
    _write_front(run / "traces", 1000, [[1.0], [2.0]])

    with pytest.raises(SystemExit, match="needs at least 2"):
        plot_front_evolution.main([str(run)])


def test_only_empty_snapshots_stop_with_a_clear_message(run):
    _write_front(run / "traces", 1000, [])

    with pytest.raises(SystemExit, match="is empty"):
        plot_front_evolution.main([str(run)])


def test_a_latest_snapshot_still_being_written_is_skipped_with_a_warning(run, capsys):
    _write_front(run / "traces", 1000, [[3.0, 1.0], [1.0, 3.0]])
    (run / "traces" / "aFUN_2000.csv").write_text("1.0,2.0\n3.0")

    plot_front_evolution.main([str(run)])

    assert "Skipping aFUN_2000.csv: still being written" in capsys.readouterr().err
    assert _outputs(run) == OUTPUTS


def test_a_snapshot_with_another_number_of_objectives_is_skipped_with_a_warning(run, capsys):
    _write_front(run / "traces", 1000, [[3.0, 1.0], [1.0, 3.0]])
    _write_front(run / "traces", 2000, [[3.0, 1.0, 2.0]])

    fronts = plot_front_evolution.load_fronts(run / "traces", 1000, None)

    assert list(fronts) == [1000]
    assert "Skipping aFUN_2000.csv: 3 objectives instead of 2" in capsys.readouterr().err


def test_no_snapshot_at_a_multiple_of_the_step_stops_with_a_clear_message(run):
    _write_front(run / "traces", 500, [[1.0, 2.0]])

    with pytest.raises(SystemExit, match="multiple of 1000"):
        plot_front_evolution.main([str(run)])


def test_wrong_number_of_labels_stops_the_script(run):
    _write_front(run / "traces", 1000, [[1.0, 2.0]])

    with pytest.raises(SystemExit, match="1 labels given for 2 objectives"):
        plot_front_evolution.main([str(run), "--labels", "cost"])


@pytest.mark.parametrize("step", ["0", "-1000", "ten"])
def test_the_step_must_be_a_positive_number_of_evaluations(run, step, capsys):
    _write_front(run / "traces", 1000, [[1.0, 2.0]])

    with pytest.raises(SystemExit):
        plot_front_evolution.main([str(run), "--step", step])

    assert "--step" in capsys.readouterr().err
    assert _outputs(run) == []
