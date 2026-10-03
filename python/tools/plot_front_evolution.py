"""
Draws how the approximation to the Pareto front evolves during a jdisrest run.

The master writes the archive of non-dominated solutions to ``aFUN_<evaluations>.csv`` in its
traces folder (``SteadyStateEvolutionaryAlgorithm.saveTrace``). This script plots the archive every
``--step`` evaluations, coloured by the number of evaluations, and writes three files next to the
traces folder (``--output-dir``, by default its parent):

- ``front_evolution.png``: every front in one figure (a 3D view for three objectives, plus the
  projection on each pair of objectives).
- ``front_evolution_grid.png``: one panel per front, with the earlier fronts in grey.
- ``front_evolution.gif``: the same as an animation, with every view of the first figure.

It reads only the ``aFUN`` files, so it works for composite (mixed-encoding) runs too, and needs at
least two objectives. A file that cannot be read, such as the latest one while the master is still
writing it, is skipped with a warning; an empty snapshot is drawn as an empty panel.

Requires numpy and matplotlib (and Pillow, which matplotlib installs, for the GIF). Example::

    python python/tools/plot_front_evolution.py path/to/run --step 1000 --labels cost time weight

``path/to/run`` is either the traces folder or a folder with a ``traces`` subfolder.
``--exclude-above X`` leaves out the solutions with an objective of X or more, for instance those
that an evaluator penalizes with a large constant, so that they do not flatten the plot.
"""
from __future__ import annotations

import argparse
import math
import re
import sys
from itertools import combinations
from pathlib import Path
from typing import Sequence

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
from matplotlib.animation import FuncAnimation, PillowWriter  # noqa: E402
from matplotlib.cm import ScalarMappable  # noqa: E402
from matplotlib.colors import BoundaryNorm, ListedColormap  # noqa: E402

ARCHIVE_TRACE = re.compile(r"aFUN_(\d+)\.csv")
EARLIER_FRONT_COLOR = "0.8"
FRAME_SECONDS = 0.8
LAST_FRAME_REPEATS = 4
FIGURE_FILE = "front_evolution.png"
GRID_FILE = "front_evolution_grid.png"
ANIMATION_FILE = "front_evolution.gif"


def read_rows(path: Path) -> list[list[float]]:
    """Reads an ``aFUN`` trace. Raises ValueError if it is incomplete or malformed."""
    text = path.read_text(encoding="utf-8")
    if text and not text.endswith("\n"):
        raise ValueError("still being written")
    rows = [[float(value) for value in line.split(",")] for line in text.splitlines() if line.strip()]
    if len({len(row) for row in rows}) > 1:
        raise ValueError("rows of different lengths")
    return rows


def load_fronts(traces: Path, step: int, exclude_above: float | None) -> dict[int, np.ndarray]:
    """
    Reads the archive snapshots taken every ``step`` evaluations, sorted by evaluations, each as an
    array of one row per solution and one column per objective. The number of objectives is that
    of the first snapshot with solutions. Stops with SystemExit when there is nothing to plot.
    """
    snapshots: dict[int, list[list[float]]] = {}
    for path in traces.iterdir():
        match = ARCHIVE_TRACE.fullmatch(path.name)
        if match and int(match.group(1)) % step == 0:
            try:
                snapshots[int(match.group(1))] = read_rows(path)
            except (OSError, ValueError) as error:
                print(f"Skipping {path.name}: {error}", file=sys.stderr)
    if not snapshots:
        raise SystemExit(f"No readable aFUN_<n>.csv with n a multiple of {step} in {traces}")
    snapshots = dict(sorted(snapshots.items()))
    objectives = next((len(rows[0]) for rows in snapshots.values() if rows), None)
    if objectives is None:
        raise SystemExit(f"Every aFUN_<n>.csv with n a multiple of {step} in {traces} is empty")
    if objectives < 2:
        raise SystemExit(f"The fronts have {objectives} objective; plotting them needs at least 2")

    fronts = {}
    for evaluations, rows in snapshots.items():
        if rows and len(rows[0]) != objectives:
            print(f"Skipping aFUN_{evaluations}.csv: {len(rows[0])} objectives instead of {objectives}",
                  file=sys.stderr)
            continue
        front = np.array(rows, dtype=float).reshape(-1, objectives)
        if exclude_above is not None:
            front = front[(front < exclude_above).all(axis=1)]
        fronts[evaluations] = front
    if not any(front.size for front in fronts.values()):
        raise SystemExit(f"Every solution has an objective of {exclude_above:g} or more; "
                         f"raise or drop --exclude-above")
    return fronts


def front_colors(count: int) -> np.ndarray:
    return plt.get_cmap("viridis")(np.linspace(0, 1, count))


def objective_limits(fronts: dict[int, np.ndarray]) -> tuple[np.ndarray, np.ndarray]:
    """Common axis limits, with a 5% margin, so that every panel and frame has the same scale."""
    points = np.vstack([front for front in fronts.values() if front.size])
    margin = 0.05 * np.maximum(points.max(axis=0) - points.min(axis=0), 1e-9)
    return points.min(axis=0) - margin, points.max(axis=0) + margin


def add_evaluations_colorbar(figure, evaluations: list[int], colors: np.ndarray):
    """Discrete colour bar: one colour per front, labelled with its evaluations."""
    bounds = [evaluations[0] - 0.5] + [(a + b) / 2 for a, b in zip(evaluations, evaluations[1:])] + \
             [evaluations[-1] + 0.5]
    mappable = ScalarMappable(norm=BoundaryNorm(bounds, len(evaluations)), cmap=ListedColormap(colors))
    bar = figure.colorbar(mappable, ax=figure.axes, ticks=evaluations, fraction=0.03, pad=0.04)
    bar.set_label("Evaluations")


def point_style(color, highlighted: bool) -> dict:
    """Scatter style of the front being shown or, with ``highlighted`` false, of an earlier one."""
    return dict(s=28 if highlighted else 12, color=color, edgecolors="black" if highlighted else "none",
                linewidths=0.3, alpha=0.9, zorder=2 if highlighted else 1)


class FrontPlot:
    """Figure with a 3D view (for three objectives) and one panel per pair of objectives."""

    def __init__(self, fronts: dict[int, np.ndarray], labels: Sequence[str]):
        self.fronts = fronts
        self.labels = labels
        self.pairs = list(combinations(range(len(labels)), 2))
        panels = len(self.pairs) + (1 if len(labels) == 3 else 0)
        columns = 1 if panels == 1 else 2 if panels <= 4 else 3
        rows = math.ceil(panels / columns)
        self.figure = plt.figure(figsize=(6 * columns + 1.5, 5 * rows))

        self.axes_3d = None
        position = 1
        if len(labels) == 3:
            self.axes_3d = self.figure.add_subplot(rows, columns, position, projection="3d")
            position += 1
        self.axes_2d = [self.figure.add_subplot(rows, columns, position + i) for i in range(len(self.pairs))]

        self.colors = front_colors(len(fronts))
        self.lower, self.upper = objective_limits(fronts)
        self.figure.subplots_adjust(top=0.92, hspace=0.25)
        add_evaluations_colorbar(self.figure, list(fronts), self.colors)

    def draw(self, current: int | None = None):
        """Draws every front in its colour or, with ``current``, that front over the earlier ones in grey."""
        for axes in self.figure.axes[:-1]:
            axes.clear()
        for index, (evaluations, front) in enumerate(self.fronts.items()):
            if current is not None and evaluations > current:
                break
            highlighted = current is None or evaluations == current
            color = self.colors[index] if highlighted else EARLIER_FRONT_COLOR
            style = point_style(color, highlighted)
            if self.axes_3d is not None and front.size:
                self.axes_3d.scatter(front[:, 0], front[:, 1], front[:, 2], depthshade=False, **style)
            for axes, (i, j) in zip(self.axes_2d, self.pairs):
                if front.size:
                    axes.scatter(front[:, i], front[:, j], **style)
        self._decorate()
        if current is None:
            self.figure.suptitle("Evolution of the Pareto front approximation")
        else:
            size = len(self.fronts[current])
            self.figure.suptitle(f"Pareto front approximation after {current} evaluations ({size} solutions)")

    def _decorate(self):
        if self.axes_3d is not None:
            self.axes_3d.set_xlabel(self.labels[0])
            self.axes_3d.set_ylabel(self.labels[1])
            self.axes_3d.set_zlabel(self.labels[2])
            self.axes_3d.set_xlim(self.lower[0], self.upper[0])
            self.axes_3d.set_ylim(self.lower[1], self.upper[1])
            self.axes_3d.set_zlim(self.lower[2], self.upper[2])
            self.axes_3d.view_init(elev=25, azim=45)
            self.axes_3d.set_box_aspect(None, zoom=0.85)  # room for the axis labels
        for axes, (i, j) in zip(self.axes_2d, self.pairs):
            axes.set_xlabel(self.labels[i])
            axes.set_ylabel(self.labels[j])
            axes.set_xlim(self.lower[i], self.upper[i])
            axes.set_ylim(self.lower[j], self.upper[j])
            axes.grid(alpha=0.3)


def save_figure(fronts: dict[int, np.ndarray], labels: Sequence[str], target: Path):
    plot = FrontPlot(fronts, labels)
    plot.draw()
    plot.figure.savefig(target, dpi=150, bbox_inches="tight")
    plt.close(plot.figure)


def save_grid(fronts: dict[int, np.ndarray], labels: Sequence[str], step: int, target: Path):
    """One panel per front (3D for three objectives, else the first two objectives)."""
    evaluations = list(fronts)
    colors = front_colors(len(evaluations))
    lower, upper = objective_limits(fronts)
    three_d = len(labels) == 3
    shown = [0, 1, 2] if three_d else [0, 1]
    # Depth shading would fade the far points and break the match with the colour bar.
    shading = dict(depthshade=False) if three_d else {}
    columns = min(5, len(evaluations))
    rows = math.ceil(len(evaluations) / columns)
    figure = plt.figure(figsize=(3.6 * columns + 1, 3.6 * rows + 0.6))
    for index, current in enumerate(evaluations):
        axes = figure.add_subplot(rows, columns, index + 1, projection="3d" if three_d else None)
        for earlier, front in list(fronts.items())[:index + 1]:
            highlighted = earlier == current
            if front.size:
                axes.scatter(*(front[:, k] for k in shown),
                             **point_style(colors[index] if highlighted else EARLIER_FRONT_COLOR, highlighted),
                             **shading)
        axes.set_title(f"{current} evaluations ({len(fronts[current])} sol.)", fontsize=10)
        for k, axis in zip(shown, "xyz"):
            getattr(axes, f"set_{axis}lim")(lower[k], upper[k])
            getattr(axes, f"set_{axis}label")(f"f{k + 1}", labelpad=0)
        axes.tick_params(labelsize=7, pad=0)
        if three_d:
            axes.view_init(elev=25, azim=45)
        else:
            axes.grid(alpha=0.3)
    figure.suptitle(f"Pareto front approximation every {step} evaluations")
    figure.text(0.5, 0.01, "   ".join(f"f{k + 1}: {labels[k]}" for k in shown), ha="center", fontsize=9)
    figure.subplots_adjust(left=0.02, right=0.9, bottom=0.07, top=0.9, wspace=0.15, hspace=0.25)
    add_evaluations_colorbar(figure, evaluations, colors)
    figure.savefig(target, dpi=150, bbox_inches="tight")
    plt.close(figure)


def save_animation(fronts: dict[int, np.ndarray], labels: Sequence[str], target: Path):
    plot = FrontPlot(fronts, labels)
    frames = list(fronts) + [list(fronts)[-1]] * LAST_FRAME_REPEATS
    animation = FuncAnimation(plot.figure, lambda k: plot.draw(frames[k]), frames=len(frames))
    animation.save(target, writer=PillowWriter(fps=1 / FRAME_SECONDS), dpi=90)
    plt.close(plot.figure)


def _positive_int(text: str) -> int:
    try:
        value = int(text)
    except ValueError:
        raise argparse.ArgumentTypeError(f"not an integer: {text!r}") from None
    if value < 1:
        raise argparse.ArgumentTypeError(f"must be at least 1, not {text}")
    return value


def _parse_args(argv: Sequence[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Plots the evolution of the Pareto front approximation "
                                                 "from the traces of a jdisrest run.")
    parser.add_argument("run", type=Path, help="run folder (with a traces/ subfolder) or the traces folder itself")
    parser.add_argument("--step", type=_positive_int, default=1000,
                        help="evaluations between plotted fronts (default: 1000)")
    parser.add_argument("--labels", nargs="+", help="objective names (default: f1, f2, ...)")
    parser.add_argument("--exclude-above", type=float,
                        help="leave out solutions with any objective greater than or equal to this value")
    parser.add_argument("--output-dir", type=Path,
                        help="folder for the figures (default: the parent of the traces folder)")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = _parse_args(argv)
    traces = args.run / "traces" if (args.run / "traces").is_dir() else args.run
    if not traces.is_dir():
        raise SystemExit(f"The folder {traces} does not exist")
    output = args.output_dir or traces.resolve().parent
    fronts = load_fronts(traces, args.step, args.exclude_above)
    objectives = next(front.shape[1] for front in fronts.values())
    labels = args.labels or [f"f{i + 1}" for i in range(objectives)]
    if len(labels) != objectives:
        raise SystemExit(f"{len(labels)} labels given for {objectives} objectives")

    output.mkdir(parents=True, exist_ok=True)
    save_figure(fronts, labels, output / FIGURE_FILE)
    save_grid(fronts, labels, args.step, output / GRID_FILE)
    save_animation(fronts, labels, output / ANIMATION_FILE)
    for evaluations, front in fronts.items():
        print(f"{evaluations:>8} evaluations: {len(front)} solutions")
    print(f"Wrote {FIGURE_FILE}, {GRID_FILE} and {ANIMATION_FILE} to {output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
