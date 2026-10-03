"""
Follows a jdisrest run from its traces folder and summarizes the latest partial Pareto front.

Every ``populationSize * traceCadence`` evaluations the master writes four traces to its traces
folder (``SteadyStateEvolutionaryAlgorithm.saveTrace``; the local ``NSGAII`` writes the same files):
the archive of non-dominated solutions to ``aFUN_<evaluations>.csv`` (objectives) and
``aVAR_<evaluations>.csv`` (variables), and the population to ``FUN_<evaluations>.csv`` and
``VAR_<evaluations>.csv``. Each time a new archive snapshot appears, this script prints:

1. The extreme non-dominated solutions: for each objective, the solution of the front with the
   lowest value. Ties are broken by the other objectives, in order, and counted. Then the best
   compromise solution: the one closest to the ideal point (Euclidean distance) once each
   objective is normalized, between the bounds of the indicators when there is a reference, or
   else between the minimum and maximum of the front. A value better than the ideal point counts
   as on it.
2. The mean of each objective over every solution of the front, and how much it changed since
   the previous snapshot shown.
3. With a reference, the hypervolume, IGD and IGD+ of the front, and how much they changed.

Every objective is minimized, as in jMetal. The traces dump the whole archive, feasible or not,
and flat traces carry no constraint values, so infeasible solutions are summarized like the
others. The default archive prefers feasible solutions (``DominanceWithConstraintsComparator``),
so with it they only appear in the snapshots taken before the first feasible solution was found.

Trace formats. An ``aFUN`` row holds the objectives separated by commas. An ``aVAR`` row holds one
value per variable, separated by commas (jMetal's ``SolutionListOutput``, flat solutions) or, for
composite solutions, is a ``CompositeSolutionListOutput`` row ``v0 v1 ...,[o0  o1],[c0  c1]`` whose
variables are the space-separated text before the first ``,[``. A binary variable is written as its
bit string, bit 0 first (``00110``). The variables are only shown and saved, never computed with, so
those written with digits only, integers and bit strings, are kept as they are written (``3``,
``00110``); the others, real variables, are read as floats and shown and saved like the objectives
(Java always writes a real with a point or an exponent, so it is never taken for an integer).

It also saves the statistics of every snapshot, in order, to the output folder (``--output-dir``,
by default the parent of the traces folder):

- ``front_stats.csv``: one row per snapshot with its evaluations, when its ``aFUN`` was written
  (local time, to the second), its number of non-dominated solutions and the mean of each objective.
- ``front_extremes.csv``: one row per extreme of each snapshot with its objectives and variables,
  and one more for its best compromise solution, with ``compromise`` as ``extreme_of``.
- ``front_indicators.csv``, with a reference: one row per snapshot with its hypervolume, IGD and
  IGD+. ``front_indicators_reference.txt`` records the reference they were computed with.
- ``front_reference.csv``: the aggregated front, the non-dominated solutions of every snapshot
  saved so far. Without it, a restart starts from the extremes of ``front_extremes.csv``.

The file names and columns are a stable format: a restart refuses a file whose columns differ.

The indicators are computed as in jMetal, on objectives normalized to [0, 1] between a lower and
an upper bound per objective:

- ``--reference-front``: a file with the objectives of a reference front, one point per line,
  separated by commas or spaces (for instance the ``FUN.csv`` of a long run). Its minimum and
  maximum of each objective are the bounds, and IGD and IGD+ need it.
- ``--ideal-point`` and ``--reference-point`` replace the lower and upper bounds. Without a
  reference front, both are needed, and only the hypervolume is computed.

IGD and IGD+ against the aggregated front would be of no use while the run goes on: each archive
snapshot already holds the non-dominated solutions found so far (up to its size), so it is the
aggregated front, or almost. ``--recompute`` measures instead every snapshot still in the traces
folder (the latest one and those kept with ``--keep-every``) against the same reference, the
aggregated front now, with those snapshots, or ``--reference-front``, and writes the results to
``front_indicators_recomputed.csv``: how far each snapshot was from the best solutions known when
the command runs. It does not change ``front_reference.csv``, so it can run while the script
follows the run; run it at the end for the final series.

The hypervolume is exact and uses the upper bound as reference point, (1, ..., 1) once
normalized, like jMetal's ``PISAHypervolume``; it is the fraction of the box between the bounds
that the front dominates. As in jMetal, the normalized objectives are clamped to [0, 1], so a
solution beyond the lower bound (an ``--ideal-point`` that is not one, or a reference front from a
shorter run) counts as on it. IGD is the square root of the sum of the squared distances from each
reference point to the nearest solution, divided by the number of reference points, as jMetal
computes it, and IGD+ the mean of those distances counting only where the solution is worse.
Higher hypervolume and lower IGD and IGD+ are better.

Reading the snapshots. The master writes each trace in place, ``aFUN`` first and ``aVAR`` last,
so a snapshot is read once both files have not changed for ``SETTLE_SECONDS``, and the snapshots
are saved in order. A read that fails (a file still being written, not created yet, changed while
being read, or with fewer rows than the other) is retried on the next passes; the snapshot is
reported unreadable, once, and skipped only after ``UNREADABLE_PASSES`` failed passes during which
neither file changed. Comparing the files between passes, rather than their age alone, also
survives the clock of a network file server running behind.

With ``--delete-traces`` it then deletes the trace files (``aVAR``, ``aFUN``, ``VAR`` and ``FUN``)
of the snapshots already saved, except the latest one, which is the result if the master dies
before finishing, and those whose evaluations are a multiple of ``--keep-every``. A snapshot is
never deleted before its statistics are on disk. Restarted on the same folder, the script goes on
where it stopped without repeating rows.

The master never cleans its traces folder, so a new run writes snapshots with the same numbers as
the previous one. When the ``aFUN`` of a saved snapshot is newer than the time recorded in
``front_stats.csv``, the script stops with an error asking for another ``--output-dir`` instead of
skipping the new snapshots, and it never deletes the traces of such a snapshot. The comparison is
in local time, so restart the script in the same time zone.

Files are read and written as UTF-8. A folder written by an older version of this script in
another encoding (cp1252 on Windows) with non-ASCII labels fails the column check on restart; use
another ``--output-dir`` for it. On a console or log file whose encoding cannot show a label, the
script prints it with backslash escapes instead of stopping.

It only uses the standard library, so it runs with any Python 3.7 or later, for instance on a
machine without the workers' environment. It spends most of the time asleep; stop it with Ctrl+C.
Usage::

    python watch_front.py path/to/traces
    python watch_front.py                       # the current folder holds the traces
    python watch_front.py path/to/traces --labels cost time weight --variables
    python watch_front.py path/to/traces --delete-traces --keep-every 1000
    python watch_front.py path/to/traces --once   # save and show the latest snapshot, then exit
    python watch_front.py path/to/traces --reference-front reference.csv
    python watch_front.py path/to/traces --ideal-point 0 0 --reference-point 1 1
    python watch_front.py path/to/traces --recompute   # every kept snapshot against the aggregated front
"""
from __future__ import annotations

import argparse
import bisect
import csv
import hashlib
import os
import re
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import Callable, Iterable, List, Mapping, Sequence, TypeVar, Union

ARCHIVE_TRACE = re.compile(r"aFUN_(\d+)\.csv")
ANY_TRACE = re.compile(r"a?(?:VAR|FUN)_(\d+)\.csv")
STATS_FILE = "front_stats.csv"
EXTREMES_FILE = "front_extremes.csv"
INDICATORS_FILE = "front_indicators.csv"
INDICATORS_REFERENCE_FILE = "front_indicators_reference.txt"
REFERENCE_FILE = "front_reference.csv"
COMPROMISE = "compromise"  # extreme_of of the best compromise solution in front_extremes.csv
RECOMPUTED_FILE = "front_indicators_recomputed.csv"
RECOMPUTED_REFERENCE_FILE = "front_indicators_recomputed_reference.txt"
MAX_RECOMPUTED_ROWS = 20  # rows of the recomputed indicators printed, evenly spaced
# The master writes each trace in place, so a snapshot is read once its files have not changed for a while.
SETTLE_SECONDS = 2.0
# Failed passes, with both files unchanged, before a snapshot counts as unreadable for good.
UNREADABLE_PASSES = 3
# front_stats.csv records when each aFUN was written to the second: a saved snapshot whose aFUN is
# newer than that by more than this was written by another run in the same traces folder.
REUSE_TOLERANCE = timedelta(seconds=1)
DEFAULT_INTERVAL_SECONDS = 15.0
# Shortest --interval: the passes during which an unreadable snapshot may still be settling span
# (UNREADABLE_PASSES - 1) intervals, so a shorter one would give up on files still being written.
MIN_INTERVAL_SECONDS = SETTLE_SECONDS
ENCODING = "utf-8"

Row = List[float]
# A variable of an aVAR trace: the text of an integer or a bit string, or the value of a real.
Variable = Union[str, float]
# What read_variable_rows keeps as text: digits only, with an optional sign.
DIGITS = re.compile(r"[+-]?[0-9]+")
T = TypeVar("T")


@dataclass
class Extreme:
    """The solution with the lowest value of one objective."""

    objectives: Row
    row: int
    ties: int
    variables: list[Variable]


@dataclass
class Indicators:
    """Quality indicators of a front. IGD and IGD+ need a reference front."""

    hypervolume: float
    igd: float | None
    igd_plus: float | None


class Reference:
    """What the indicators are computed against: the bounds and, for IGD and IGD+, a reference front."""

    def __init__(self, front: list[Row] | None, lower: Row, upper: Row, description: str):
        self.lower = lower
        self.upper = upper
        self.front = [self.normalize(point) for point in front] if front else None  # normalized
        self.description = description  # recorded next to the indicators, to detect a change of reference

    def normalize(self, point: Sequence[float]) -> Row:
        return [(value - low) / (high - low) for value, low, high in zip(point, self.lower, self.upper)]

    def indicators(self, front: Sequence[Row]) -> Indicators:
        """
        The indicators of the (non-dominated) front, in the original objectives. The hypervolume
        clamps the normalized objectives to [0, 1], like jMetal's ``PISAHypervolume``; IGD and IGD+
        use them as they are.
        """
        if len(front[0]) != len(self.lower):
            raise SystemExit(f"the reference has {len(self.lower)} objectives but the snapshots have {len(front[0])}")
        points = [self.normalize(point) for point in front]
        clamped = [[min(max(value, 0.0), 1.0) for value in point] for point in points]
        return Indicators(hypervolume(clamped, [1.0] * len(self.lower)),
                          igd(points, self.front) if self.front else None,
                          igd_plus(points, self.front) if self.front else None)


class AggregatedFront:
    """
    The non-dominated solutions of the saved snapshots, kept in ``front_reference.csv`` so that a
    restart goes on with them. Without that file it starts from the extremes in
    ``front_extremes.csv``, the non-dominated solutions left of the snapshots saved before; the
    number of objectives of those rows comes from the ``mean_*`` columns of ``stats_file``.
    """

    def __init__(self, path: Path, extremes_file: Path, stats_file: Path | None = None):
        self.path = path
        self.points: list[Row] = []
        self._previous: set[tuple[float, ...]] = set()
        self._changed = False
        if path.exists():
            self.add(read_points(path))
        elif extremes_file.exists():
            self.add(_extremes_objectives(extremes_file, _objective_count(stats_file)))
        self._changed = False

    def add(self, points: Sequence[Row]) -> None:
        """
        Adds the points that no point of the front is at least as good as, dropping those they
        dominate. Empty points are ignored: one would be at least as good as every other point.
        """
        # Consecutive snapshots share most of their solutions, which need no check.
        current = {tuple(point) for point in points if len(point) > 0}
        for point in current - self._previous:
            if not any(all(a <= b for a, b in zip(other, point)) for other in self.points):
                self.points = [other for other in self.points if not all(a <= b for a, b in zip(point, other))]
                self.points.append(list(point))
                self._changed = True
        self._previous = current

    def save(self) -> None:
        """Writes the front, if it changed, replacing the file in one step."""
        if self._changed:
            temporary = self.path.with_name(self.path.name + ".tmp")
            with temporary.open("w", encoding=ENCODING) as file:
                file.writelines(",".join(map(_value, point)) + "\n" for point in self.points)
                file.flush()
                os.fsync(file.fileno())
            os.replace(temporary, self.path)
            self._changed = False


def _objective_count(stats_file: Path | None) -> int | None:
    """The number of objectives of the saved snapshots (the ``mean_*`` columns), or None if unknown."""
    header = _read_header(stats_file) if stats_file is not None else None
    return len(header) - 3 if header is not None and len(header) > 3 else None


def _extremes_objectives(path: Path, count: int | None) -> list[Row]:
    """
    The objectives of the extremes in ``front_extremes.csv``: the ``count`` columns after ``ties``.
    Without ``count`` (no ``front_stats.csv`` was ever written), the columns up to the first one
    named like a variable, ``x1``.
    """
    with _open_csv(path) as file:
        rows = list(csv.reader(file))
    if not rows:
        return []
    header = rows[0]
    first = header.index("ties") + 1
    if count is not None:
        last = first + count
    else:
        last = next((i for i in range(first, len(header)) if re.fullmatch(r"x\d+", header[i])), len(header))
    return [[float(value) for value in row[first:last]] for row in rows[1:] if row]


@dataclass
class Summary:
    """The front of one archive snapshot."""

    evaluations: int
    written: float
    size: int
    extremes: list[Extreme]
    means: Row
    indicators: Indicators | None = None
    compromise: Extreme | None = None


def snapshots(traces: Path) -> list[int]:
    """Evaluations of the archive snapshots in the folder, in increasing order."""
    return sorted(int(match.group(1)) for match in (ARCHIVE_TRACE.fullmatch(path.name) for path in traces.iterdir())
                  if match)


def latest_snapshot(traces: Path) -> int | None:
    """Evaluations of the latest archive snapshot in the folder, or None if there is none."""
    available = snapshots(traces)
    return available[-1] if available else None


def read_rows(path: Path) -> list[Row]:
    """
    Reads a trace: values separated by commas or, in a composite VAR row
    ``v0 v1 ...,[o0  o1],[c0  c1]``, the variables separated by spaces before the first ``,[``.
    Raises ValueError if it is incomplete or malformed; the message names the file and the line.
    """
    return _read_trace(path, float)


def read_variable_rows(path: Path) -> list[list[Variable]]:
    """
    Reads an ``aVAR`` trace like ``read_rows``, but keeps as text the values written with digits
    only, an optional sign first: integers and the bit strings of binary variables, which as numbers
    would lose their leading zeros (``001001`` would be read as 1001). The other values, real
    variables, are read as floats, and anything else is malformed.
    """
    return _read_trace(path, _variable)


def _variable(text: str) -> Variable:
    text = text.strip()
    return text if DIGITS.fullmatch(text) else float(text)


def _read_trace(path: Path, value: Callable[[str], T]) -> list[list[T]]:
    """The rows of a trace with each field read by ``value``; see ``read_rows``."""
    text = path.read_text(encoding=ENCODING)
    if text and not text.endswith("\n"):
        raise ValueError(f"{path.name} is still being written")
    rows = []
    for line_number, line in enumerate(text.splitlines(), 1):
        if line.strip():
            try:
                rows.append([value(field) for field in _fields(line)])
            except ValueError as error:
                raise ValueError(f"{path.name} line {line_number}: {error}") from None
    if len({len(row) for row in rows}) > 1:
        raise ValueError(f"{path.name} has rows of different lengths")
    return rows


def _fields(line: str) -> list[str]:
    """The values of a trace row: the text before ``,[`` split on spaces (composite VAR rows), else split on commas."""
    if ",[" in line:
        return line.split(",[", 1)[0].split()
    return line.split(",")


def dominates(a: Sequence[float], b: Sequence[float]) -> bool:
    """Whether ``a`` is no worse than ``b`` in every objective and better in one (minimization)."""
    return all(x <= y for x, y in zip(a, b)) and any(x < y for x, y in zip(a, b))


def non_dominated(rows: Sequence[Row]) -> list[int]:
    """Indices of the rows that no other row dominates. Identical rows are all kept."""
    return [i for i, row in enumerate(rows) if not any(dominates(other, row) for other in rows)]


def extremes(rows: Sequence[Row], indices: Sequence[int]) -> list[tuple[int, int]]:
    """For each objective, the index of the row with the lowest value and how many rows share it."""
    result = []
    for k in range(len(rows[indices[0]])):
        best = min(indices, key=lambda i: (rows[i][k], *(rows[i][j] for j in range(len(rows[i])) if j != k)))
        ties = sum(1 for i in indices if rows[i][k] == rows[best][k])
        result.append((best, ties))
    return result


def means(rows: Sequence[Row], indices: Sequence[int]) -> Row:
    """The mean of each objective over the given rows."""
    return [sum(rows[i][k] for i in indices) / len(indices) for k in range(len(rows[indices[0]]))]


def compromise(rows: Sequence[Row], indices: Sequence[int], lower: Sequence[float] | None = None,
               upper: Sequence[float] | None = None) -> tuple[int, int]:
    """
    The best compromise solution: the row closest to the ideal point by Euclidean distance once each
    objective is normalized between ``lower`` (the ideal point) and ``upper``, by default the
    minimum and maximum of the rows. A value better than the ideal point counts as on it, so beating
    the ideal point in one objective never pushes a solution away from it, and an objective with
    equal bounds does not count. Returns its index and how many rows are that close.
    """
    count = len(rows[indices[0]])
    lower = lower or [min(rows[i][k] for i in indices) for k in range(count)]
    upper = upper or [max(rows[i][k] for i in indices) for k in range(count)]
    scales = [high - low if high > low else None for low, high in zip(lower, upper)]  # None: constant objective

    def distance(i: int) -> float:
        return sum(max((value - low) / scale, 0.0) ** 2 for value, low, scale in zip(rows[i], lower, scales) if scale)

    best = min(indices, key=distance)
    return best, sum(1 for i in indices if distance(i) == distance(best))


def _add_to_staircase(xs: list[float], ys: list[float], x: float, y: float, ref_x: float, ref_y: float) -> float:
    """
    Adds (x, y) to the non-dominated 2-D points ``xs``/``ys``, sorted by increasing x (and so
    decreasing y), and returns the area up to (ref_x, ref_y) that it adds to theirs.
    """
    i = bisect.bisect_left(xs, x)
    if (i > 0 and ys[i - 1] <= y) or (i < len(xs) and xs[i] == x and ys[i] <= y):
        return 0.0  # dominated
    j = i
    while j < len(xs) and ys[j] >= y:  # the points it dominates
        j += 1
    added = 0.0
    previous_x, previous_y = x, ys[i - 1] if i > 0 else ref_y
    for k in range(i, j):
        added += (previous_y - y) * (xs[k] - previous_x)
        previous_x, previous_y = xs[k], ys[k]
    added += (previous_y - y) * ((xs[j] if j < len(xs) else ref_x) - previous_x)
    del xs[i:j], ys[i:j]
    xs.insert(i, x)
    ys.insert(i, y)
    return added


def hypervolume(points: Sequence[Sequence[float]], reference: Sequence[float]) -> float:
    """
    Exact volume dominated by the points (minimization) up to the reference point. Points not
    better than it in every objective add nothing. Two objectives use a sweep, three a sweep that
    keeps the 2-D front, and more are sliced along the last objective, which costs about
    O(n^(d-1)): fine for a snapshot, slow when recomputing thousands of many-objective ones.
    """
    points = [tuple(point) for point in points if all(value < limit for value, limit in zip(point, reference))]
    dimensions = len(reference)
    if not points:
        return 0.0
    if dimensions == 1:
        return reference[0] - min(point[0] for point in points)
    points.sort(key=lambda point: point[-1])
    if dimensions == 2:
        xs: list[float] = []
        ys: list[float] = []
        return sum(_add_to_staircase(xs, ys, x, y, reference[0], reference[1]) for x, y in points)
    volume = 0.0
    if dimensions == 3:
        xs, ys, area = [], [], 0.0
        for k, (x, y, z) in enumerate(points):
            area += _add_to_staircase(xs, ys, x, y, reference[0], reference[1])
            volume += area * ((points[k + 1][2] if k + 1 < len(points) else reference[2]) - z)
        return volume
    for k, point in enumerate(points):
        depth = (points[k + 1][-1] if k + 1 < len(points) else reference[-1]) - point[-1]
        if depth > 0:
            volume += depth * hypervolume([other[:-1] for other in points[:k + 1]], reference[:-1])
    return volume


def _nearest_squared(reference: Sequence[float], front: Sequence[Row], dominance: bool) -> float:
    """
    Squared distance from a reference point to the nearest solution, counting with ``dominance``
    only where the solution is worse. A partial sum that already exceeds the nearest so far stops.
    """
    best = float("inf")
    for solution in front:
        total = 0.0
        for r, s in zip(reference, solution):
            difference = max(s - r, 0.0) if dominance else s - r
            total += difference * difference
            if total >= best:
                break
        else:
            best = total
    return best


def igd(front: Sequence[Row], reference_front: Sequence[Row]) -> float:
    """jMetal's IGD: sqrt(sum of squared distances to the nearest solution) / reference points."""
    total = sum(_nearest_squared(r, front, dominance=False) for r in reference_front)
    return total ** 0.5 / len(reference_front)


def igd_plus(front: Sequence[Row], reference_front: Sequence[Row]) -> float:
    """IGD+: mean over the reference points of the dominance distance to the nearest solution."""
    return sum(_nearest_squared(r, front, dominance=True) ** 0.5 for r in reference_front) / len(reference_front)


def read_points(path: Path) -> list[Row]:
    """
    Reads a front with one point per line, separated by commas or spaces. In a composite VAR row
    ``v0 v1 ...,[o0  o1],[c0  c1]`` the point is the text before the first ``,[``.
    """
    points = [[float(value) for value in re.split(r"[,\s]+", line.split(",[", 1)[0].strip())]
              for line in path.read_text(encoding=ENCODING).splitlines() if line.strip()]
    if not points or len({len(point) for point in points}) > 1:
        raise SystemExit(f"{path} must have one point per line, all with the same number of objectives")
    return points


def make_reference(front: list[Row] | None, ideal_point: Sequence[float] | None,
                   reference_point: Sequence[float] | None, source: str) -> Reference:
    """
    A reference with the given front, if any, and the given bounds or, for those missing, the
    minimum and maximum of the front. ``source`` names the front in the description.
    """
    lower = list(ideal_point) if ideal_point else [min(column) for column in zip(*front)]
    upper = list(reference_point) if reference_point else [max(column) for column in zip(*front)]
    sizes = {len(lower), len(upper)} | ({len(front[0])} if front else set())
    if len(sizes) > 1:
        raise SystemExit("the reference front, --ideal-point and --reference-point have different numbers of objectives")
    if any(high <= low for low, high in zip(lower, upper)):
        hint = "; an objective that is constant in the reference front needs --ideal-point or --reference-point"
        raise SystemExit(f"the upper bound {upper} must be greater than the lower bound {lower} in every objective"
                         + (hint if front else ""))
    lines = [f"reference_front = {source}"] if front else []
    lines += [f"lower_bound = {', '.join(map(_value, lower))}", f"upper_bound = {', '.join(map(_value, upper))}"]
    return Reference(front, lower, upper, "\n".join(lines) + "\n")


def load_reference(front_file: Path | None, ideal_point: Sequence[float] | None,
                   reference_point: Sequence[float] | None) -> Reference | None:
    """The reference given on the command line, or None if there is none."""
    if front_file is None and (ideal_point is None or reference_point is None):
        return None
    front = read_points(front_file) if front_file else None
    source = ""
    if front_file:
        digest = hashlib.sha256(front_file.read_bytes()).hexdigest()[:16]
        source = f"{front_file.resolve()} ({len(front)} points, sha256 {digest})"
    return make_reference(front, ideal_point, reference_point, source)


def summarize(traces: Path, evaluations: int, reference: Reference | None = None,
              aggregated: AggregatedFront | None = None) -> Summary:
    """
    Summarizes a snapshot, whose front also joins the aggregated front. Raises OSError if a file is
    missing and ValueError if they are incomplete or inconsistent, or if the ``aFUN`` changed while
    it was read, before changing anything. Its ``written`` time is therefore that of the rows read,
    which the check for another run in the traces folder relies on.
    """
    path = traces / f"aFUN_{evaluations}.csv"
    before = path.stat()
    rows = read_rows(path)
    after = path.stat()  # after opening it, so that a network file system shows its current state
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
        raise ValueError(f"aFUN_{evaluations}.csv changed while being read")
    written = after.st_mtime
    variables = read_variable_rows(traces / f"aVAR_{evaluations}.csv")
    if len(variables) != len(rows):
        raise ValueError(f"aVAR_{evaluations}.csv and aFUN_{evaluations}.csv have different lengths")
    front = non_dominated(rows)
    chosen = [Extreme(rows[i], i + 1, ties, variables[i]) for i, ties in extremes(rows, front)] if front else []
    front_rows = [rows[i] for i in front]
    indicators = reference.indicators(front_rows) if reference and front else None
    if aggregated is not None:
        aggregated.add(front_rows)
    best = None
    if front:
        # With a reference, the bounds of the indicators, so the choice does not move with the front.
        index, ties = compromise(rows, front, reference.lower if reference else None,
                                 reference.upper if reference else None)
        best = Extreme(rows[index], index + 1, ties, variables[index])
    return Summary(evaluations, written, len(front), chosen, means(rows, front) if front else [], indicators, best)


def objective_names(labels: Sequence[str] | None, count: int) -> list[str]:
    """The labels, checked against the number of objectives, or f1, f2, ..."""
    names = list(labels) if labels else [f"f{k + 1}" for k in range(count)]
    if len(names) != count:
        raise SystemExit(f"{len(names)} labels given for {count} objectives")
    return names


def number(value: float) -> str:
    """A value for the screen, with six significant digits."""
    return format(value + 0.0, ".6g")  # + 0.0 turns -0.0 into 0.0


def variable_text(variable: Variable, format_number: Callable[[float], str]) -> str:
    """A variable read by ``read_variable_rows``: its text as written, or its value formatted."""
    return variable if isinstance(variable, str) else format_number(variable)


def format_summary(summary: Summary, labels: Sequence[str], previous: Summary | None,
                   show_variables: bool = False) -> str:
    """The text printed for a snapshot: its extremes, best compromise, means and indicators."""
    written = datetime.fromtimestamp(summary.written).strftime("%Y-%m-%d %H:%M:%S")
    lines = [f"== {summary.evaluations} evaluations: {summary.size} non-dominated solutions "
             f"(aFUN_{summary.evaluations}.csv, written {written})"]
    if not summary.extremes:
        return "\n".join(lines)
    names = [f"minimum of {label}" for label in labels] + ["compromise", "mean", "change of the mean", "hypervolume"]
    name_width = max(len(name) for name in names)
    widths = [max(len(label), 12) for label in labels]

    def row(name: str, values: Sequence[str], note: str = "") -> str:
        cells = "  ".join(value.ljust(width) for value, width in zip(values, widths))
        return f"  {name.ljust(name_width)}  {cells}  {note}".rstrip()

    lines.append(row("", list(labels)))
    special = [(f"minimum of {label}", extreme, "with the same value") for label, extreme in zip(labels, summary.extremes)]
    if summary.compromise is not None:
        special.append(("compromise", summary.compromise, "as close to the ideal point"))
    for name, extreme, tie_note in special:
        ties = f" ({extreme.ties} {tie_note})" if extreme.ties > 1 else ""
        lines.append(row(name, [number(v) for v in extreme.objectives], f"row {extreme.row}{ties}"))
        if show_variables:
            lines.append(f"  {'':{name_width}}  variables: "
                         f"{', '.join(variable_text(v, number) for v in extreme.variables)}")
    lines.append(row("mean", [number(v) for v in summary.means]))
    if previous is not None and previous.means:
        changes = [f"{current - before:+.4g}" for current, before in zip(summary.means, previous.means)]
        lines.append(row("change of the mean", changes, f"since {previous.evaluations} evaluations"))
    if summary.indicators is not None:
        before = previous.indicators if previous is not None else None
        for name, field, better in (("hypervolume", "hypervolume", "higher"), ("IGD", "igd", "lower"),
                                    ("IGD+", "igd_plus", "lower")):
            value = getattr(summary.indicators, field)
            if value is not None:
                old = getattr(before, field) if before is not None else None
                change = f"change {value - old:+.4g}" if old is not None else ""
                lines.append(row(name, [number(value)], f"{change}  ({better} is better)".strip()))
    return "\n".join(lines)


class Recorder:
    """
    Appends the statistics of each snapshot to ``front_stats.csv``, its extremes, with their
    variables, to ``front_extremes.csv`` and, with a reference, its indicators to
    ``front_indicators.csv``; the aggregated front of the snapshots goes to ``front_reference.csv``.
    A snapshot counts as saved once its row is in ``front_stats.csv``, which is written last and
    synced to disk; the rows an interrupted save left in the other files are not written again.
    """

    def __init__(self, folder: Path, reference: Reference | None = None):
        self.stats = folder / STATS_FILE
        self.extremes = folder / EXTREMES_FILE
        self.indicators = folder / INDICATORS_FILE
        # Evaluations of each saved snapshot -> when its aFUN was written, to the second (None if unknown).
        self.recorded: dict[int, datetime | None] = self._read_recorded()
        # Snapshots whose rows are already in a file although they are not saved: an interrupted save.
        saved = set(self.recorded)
        self._written_before = {path: _evaluations_in(path) - saved for path in (self.extremes, self.indicators)}
        self._checked: set[Path] = set()
        self.aggregated = AggregatedFront(folder / REFERENCE_FILE, self.extremes, self.stats)
        if reference is not None:
            self._check_reference(folder / INDICATORS_REFERENCE_FILE, reference)

    def _check_reference(self, path: Path, reference: Reference) -> None:
        """Indicators computed against another reference must not end up in the same file."""
        if self.indicators.exists() and self.indicators.stat().st_size > 0:
            recorded = path.read_text(encoding=ENCODING, errors="replace") if path.exists() else None
            if recorded != reference.description:
                raise SystemExit(f"{self.indicators} was computed with another reference (see {path}); "
                                 f"use the same --reference-front, --ideal-point and --reference-point "
                                 f"or another --output-dir")
        else:
            path.write_text(reference.description, encoding=ENCODING)

    def _read_recorded(self) -> dict[int, datetime | None]:
        if not self.stats.exists():
            return {}
        with _open_csv(self.stats) as file:
            return {int(row[0]): _parse_time(row[1] if len(row) > 1 else "")
                    for row in list(csv.reader(file))[1:] if row}

    def record(self, summary: Summary, labels: Sequence[str]) -> None:
        """Saves a snapshot; an empty one has nothing to save, but counts as saved like the others."""
        written = _local_time(summary.written)
        if summary.extremes:
            variables = [f"x{i + 1}" for i in range(len(summary.extremes[0].variables))]
            special = list(zip(labels, summary.extremes))
            if summary.compromise is not None:
                special.append((COMPROMISE, summary.compromise))
            self._append(self.extremes, ["evaluations", "extreme_of", "row", "ties", *labels, *variables],
                         [[summary.evaluations, label, extreme.row, extreme.ties,
                           *map(_value, extreme.objectives), *(variable_text(v, _value) for v in extreme.variables)]
                          for label, extreme in special], summary.evaluations)
            self.aggregated.save()
            if summary.indicators is not None:
                values = (summary.indicators.hypervolume, summary.indicators.igd, summary.indicators.igd_plus)
                self._append(self.indicators, ["evaluations", "hypervolume", "igd", "igd_plus"],
                             [[summary.evaluations, *("" if value is None else _value(value) for value in values)]],
                             summary.evaluations)
            self._append(self.stats, ["evaluations", "written", "solutions", *(f"mean_{label}" for label in labels)],
                         [[summary.evaluations, written.isoformat(timespec="seconds"), summary.size,
                           *map(_value, summary.means)]])
        self.recorded[summary.evaluations] = written

    def _append(self, path: Path, header: list[str], rows: list[list], evaluations: int | None = None) -> None:
        if path not in self._checked:
            existing = _read_header(path)
            if existing is not None:
                if existing != header:
                    raise SystemExit(f"{path} has other columns ({','.join(existing)}); "
                                     f"use the same --labels as before or another --output-dir")
            else:
                rows = [header, *rows]
            self._checked.add(path)
        written_before = self._written_before.get(path)
        if written_before and evaluations in written_before:
            written_before.discard(evaluations)
            return
        with path.open("a", newline="", encoding=ENCODING) as file:
            csv.writer(file).writerows(rows)
            file.flush()
            os.fsync(file.fileno())


def _value(value: float) -> str:
    return repr(value + 0.0)  # shortest text that reads back as the same number


def _open_csv(path: Path):
    """Opens one of the script's own CSV files; text in another encoding does not stop the read."""
    return path.open(newline="", encoding=ENCODING, errors="replace")


def _read_header(path: Path) -> list[str] | None:
    """The first row of a CSV file, or None if it does not exist or is empty."""
    if not path.exists() or path.stat().st_size == 0:
        return None
    with _open_csv(path) as file:
        return next(csv.reader(file), [])


def _evaluations_in(path: Path) -> set[int]:
    """The evaluations in the first column of one of the script's CSV files."""
    if not path.exists():
        return set()
    with _open_csv(path) as file:
        return {int(row[0]) for row in list(csv.reader(file))[1:] if row and row[0].isdigit()}


def _local_time(timestamp: float) -> datetime:
    """A modification time as local time to the second, as front_stats.csv records it."""
    return datetime.fromtimestamp(timestamp).replace(microsecond=0)


def _parse_time(text: str) -> datetime | None:
    """A ``written`` value of front_stats.csv, or None if it is not a local time as the script writes them."""
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    return parsed if parsed.tzinfo is None else None


def _rewritten(path: Path, recorded: datetime | None) -> bool:
    """Whether the file was written after the recorded time (beyond the tolerance); False if it is gone."""
    if recorded is None:
        return False
    try:
        modified = _local_time(path.stat().st_mtime)
    except OSError:
        return False
    return modified - recorded > REUSE_TOLERANCE


def reused_snapshot(traces: Path, recorded: Mapping[int, datetime | None], available: Iterable[int]) -> str | None:
    """
    Why the traces folder holds another run than the one saved, or None if it does not: the first
    saved snapshot whose ``aFUN`` is newer than the time recorded for it.
    """
    for evaluations in available:
        if evaluations in recorded:
            path = traces / f"aFUN_{evaluations}.csv"
            if _rewritten(path, recorded[evaluations]):
                return (f"{path} was written after the snapshot of {evaluations} evaluations saved in {STATS_FILE} "
                        f"({recorded[evaluations].isoformat(timespec='seconds')}): the traces folder now holds "
                        f"another run; use another --output-dir for it")
    return None


def delete_saved_traces(traces: Path, recorded: Mapping[int, datetime | None], keep: int,
                        keep_every: int | None) -> None:
    """
    Deletes the trace files of the saved snapshots, except the ``keep`` latest saved ones, those
    whose evaluations are a multiple of ``keep_every`` and those whose ``aFUN`` was written after
    the time recorded for it (another run in the same folder). The ``aFUN`` goes last, so a
    snapshot left half deleted can still be checked.
    """
    files: dict[int, list[Path]] = {}
    for path in traces.iterdir():
        match = ANY_TRACE.fullmatch(path.name)
        if match:
            files.setdefault(int(match.group(1)), []).append(path)
    kept = set(sorted(recorded)[-keep:])
    for evaluations, paths in files.items():
        if evaluations not in recorded or evaluations in kept or (keep_every and evaluations % keep_every == 0):
            continue
        archive = f"aFUN_{evaluations}.csv"
        if _rewritten(traces / archive, recorded[evaluations]):
            continue
        for path in sorted(paths, key=lambda p: p.name == archive):
            try:
                path.unlink()
            except OSError:
                pass  # gone already, or still open by the master on Windows: tried again on the next pass


def settled(path: Path) -> bool:
    """Whether the file has not changed for ``SETTLE_SECONDS``; False if it does not exist."""
    try:
        return time.time() - path.stat().st_mtime >= SETTLE_SECONDS
    except OSError:
        return False


def _stat(path: Path) -> os.stat_result | None:
    try:
        return path.stat()
    except OSError:
        return None


def _report_unreadable(evaluations: int, error: Exception) -> None:
    print(f"Cannot read the snapshot of {evaluations} evaluations: {error}", file=sys.stderr, flush=True)


def watch(traces: Path, labels: Sequence[str] | None, show_variables: bool, once: bool, interval: float,
          output: Path, delete: bool = False, keep: int = 1, keep_every: int | None = None,
          sleep: Callable[[float], None] = time.sleep, reference: Reference | None = None) -> int:
    """
    Saves every new snapshot and prints the latest one, until interrupted or, with ``once``, after
    the first pass that shows the latest snapshot seen at the start (1 if it is unreadable, or if
    there is no snapshot). Stops with SystemExit when the traces folder holds another run.
    """
    recorder = Recorder(output, reference)
    shown: Summary | None = None
    unreadable: set[int] = set()
    # Evaluations -> (sizes and times of its aFUN and aVAR at the last failed read, failed passes in a row).
    failures: dict[int, tuple[tuple, int]] = {}
    waiting_reported = False
    target: int | None = None  # with once, the latest snapshot when the script first saw one

    while True:
        available = snapshots(traces)
        if not available:
            if not waiting_reported:
                print(f"No aFUN_<n>.csv in {traces} yet; waiting...", flush=True)
                waiting_reported = True
            if once:
                return 1
        reason = reused_snapshot(traces, recorder.recorded, available)
        if reason is not None:
            raise SystemExit(reason)

        # Save the new snapshots in order: one that may still be being written makes the later ones wait.
        saved: dict[int, Summary] = {}
        for evaluations in available:
            if evaluations in recorder.recorded or evaluations in unreadable:
                continue
            stats = (_stat(traces / f"aFUN_{evaluations}.csv"), _stat(traces / f"aVAR_{evaluations}.csv"))
            if stats[0] is None:
                continue  # deleted since the listing: nothing left to read
            now = time.time()
            if any(stat is not None and now - stat.st_mtime < SETTLE_SECONDS for stat in stats):
                break
            try:
                summary = summarize(traces, evaluations, reference, recorder.aggregated)
            except (OSError, ValueError) as error:
                signature = tuple(None if stat is None else (stat.st_size, stat.st_mtime_ns) for stat in stats)
                previous = failures.get(evaluations)
                passes = previous[1] + 1 if previous is not None and previous[0] == signature else 1
                if passes < UNREADABLE_PASSES:
                    failures[evaluations] = (signature, passes)
                    break  # perhaps still being written: retried on the next pass
                failures.pop(evaluations, None)
                _report_unreadable(evaluations, error)
                unreadable.add(evaluations)
                continue
            failures.pop(evaluations, None)
            recorder.record(summary, objective_names(labels, len(summary.means)) if summary.means else [])
            saved[evaluations] = summary

        if once and (target is None or target not in available):
            target = available[-1] if available else None

        # Show the latest saved snapshot, not the latest file: when the master writes a snapshot
        # every second or so, the latest file is always still settling, so nothing would be shown.
        ready = [evaluations for evaluations in available
                 if evaluations in recorder.recorded and evaluations not in unreadable]
        latest = ready[-1] if ready else None
        if latest is not None and (shown is None or latest != shown.evaluations):
            summary = saved.get(latest)
            if summary is None:
                try:
                    summary = summarize(traces, latest, reference, recorder.aggregated)  # saved by an earlier run
                except (OSError, ValueError) as error:
                    _report_unreadable(latest, error)
                    unreadable.add(latest)
            if summary is not None:
                names = objective_names(labels, len(summary.means)) if summary.means else list(labels or [])
                print(format_summary(summary, names, shown, show_variables), flush=True)
                if len(saved) > 1:
                    print(f"  (saved {len(saved)} snapshots to {recorder.stats.name})", flush=True)
                shown = summary

        if delete:
            delete_saved_traces(traces, recorder.recorded, keep, keep_every)

        if once and target is not None:
            if target in unreadable:
                return 1
            if shown is not None and shown.evaluations >= target:
                return 0
        sleep(SETTLE_SECONDS if once else interval)


def recompute(traces: Path, output: Path, reference_file: Path | None, ideal_point: Sequence[float] | None,
              reference_point: Sequence[float] | None, labels: Sequence[str] | None = None) -> int:
    """
    Computes the indicators of every snapshot still in the traces folder, all against the same
    reference, and writes them to ``front_indicators_recomputed.csv``. The reference front is
    ``reference_file`` or, by default, the aggregated front of ``front_reference.csv`` together with
    those snapshots, so each is measured against the best solutions known now. It does not change
    ``front_reference.csv``, so it can run while the script follows the run. It reads only the
    ``aFUN`` files.
    """
    fronts: dict[int, list[Row]] = {}
    available = snapshots(traces)
    for evaluations in available:
        path = traces / f"aFUN_{evaluations}.csv"
        try:
            # Only the newest snapshot can still be being written.
            if evaluations != available[-1] or settled(path):
                rows = read_rows(path)
                if rows:
                    fronts[evaluations] = [rows[i] for i in non_dominated(rows)]
        except (OSError, ValueError) as error:
            _report_unreadable(evaluations, error)
    if not fronts:
        print(f"No snapshot to read in {traces}", file=sys.stderr)
        return 1

    if reference_file is not None:
        reference = load_reference(reference_file, ideal_point, reference_point)
        against = f"the front {reference_file}"
    else:
        aggregated = AggregatedFront(output / REFERENCE_FILE, output / EXTREMES_FILE, output / STATS_FILE)
        for front in fronts.values():
            aggregated.add(front)
        reference = make_reference(aggregated.points, ideal_point, reference_point,
                                   f"aggregated front of {REFERENCE_FILE} and the {len(fronts)} snapshots in "
                                   f"{traces.resolve()} ({len(aggregated.points)} points)")
        against = f"the aggregated front ({len(aggregated.points)} solutions)"

    results = [(evaluations, reference.indicators(front)) for evaluations, front in fronts.items()]
    table = output / RECOMPUTED_FILE
    temporary = table.with_name(table.name + ".tmp")
    with temporary.open("w", newline="", encoding=ENCODING) as file:
        writer = csv.writer(file)
        writer.writerow(["evaluations", "hypervolume", "igd", "igd_plus"])
        writer.writerows([evaluations, _value(result.hypervolume), _value(result.igd), _value(result.igd_plus)]
                         for evaluations, result in results)
    os.replace(temporary, table)
    (output / RECOMPUTED_REFERENCE_FILE).write_text(reference.description, encoding=ENCODING)

    print(f"Indicators of {len(results)} snapshots against {against}, with the objectives normalized between "
          f"{', '.join(map(number, reference.lower))} and {', '.join(map(number, reference.upper))}"
          + (f" ({', '.join(labels)})" if labels else "") + ":")
    print(f"  {'evaluations':>12}  {'hypervolume':>12}  {'IGD':>12}  {'IGD+':>12}")
    shown = sorted({round(i * (len(results) - 1) / (MAX_RECOMPUTED_ROWS - 1)) for i in range(MAX_RECOMPUTED_ROWS)})
    for index in shown if len(results) > MAX_RECOMPUTED_ROWS else range(len(results)):
        evaluations, result = results[index]
        print(f"  {evaluations:>12}  {number(result.hypervolume):>12}  {number(result.igd):>12}  "
              f"{number(result.igd_plus):>12}")
    print(f"Saved to {table}" + (f" (showing {MAX_RECOMPUTED_ROWS} of {len(results)})"
                                 if len(results) > MAX_RECOMPUTED_ROWS else ""))
    return 0


def _parse_args(argv: Sequence[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Saves and summarizes the partial Pareto fronts of a jdisrest run "
                                                 "each time its traces folder gets a new snapshot.")
    parser.add_argument("traces", nargs="?", type=Path, default=Path("."),
                        help="traces folder of the run (default: the current folder)")
    parser.add_argument("--labels", nargs="+", help="objective names (default: f1, f2, ...)")
    parser.add_argument("--variables", action="store_true", help="also print the variables of each extreme")
    parser.add_argument("--once", action="store_true", help="save and show the latest snapshot, then exit")
    parser.add_argument("--interval", type=float, default=DEFAULT_INTERVAL_SECONDS,
                        help=f"seconds between checks of the folder, at least {MIN_INTERVAL_SECONDS:g} "
                             f"(default: {DEFAULT_INTERVAL_SECONDS:g})")
    parser.add_argument("--output-dir", type=Path,
                        help=f"folder for {STATS_FILE} and {EXTREMES_FILE} (default: the parent of the traces folder)")
    parser.add_argument("--delete-traces", action="store_true",
                        help="delete the trace files of the snapshots already saved")
    parser.add_argument("--keep", type=int, default=1,
                        help="latest saved snapshots whose traces are never deleted (default: 1, at least 1)")
    parser.add_argument("--keep-every", type=int,
                        help="do not delete the snapshots whose evaluations are a multiple of this number")
    parser.add_argument("--reference-front", type=Path,
                        help=f"objectives of a reference front, one point per line, for the hypervolume, IGD and "
                             f"IGD+ in {INDICATORS_FILE}; its minimum and maximum bound the normalization")
    parser.add_argument("--ideal-point", nargs="+", type=float,
                        help="lower bound of each objective for the indicators (default: the minimum of the "
                             "reference front)")
    parser.add_argument("--reference-point", nargs="+", type=float,
                        help="upper bound of each objective and reference point of the hypervolume (default: the "
                             "maximum of the reference front)")
    parser.add_argument("--recompute", action="store_true",
                        help=f"compute the indicators of every snapshot still in the traces folder against the "
                             f"aggregated front ({REFERENCE_FILE} and those snapshots) or --reference-front, "
                             f"write them to {RECOMPUTED_FILE} and exit")
    args = parser.parse_args(argv)
    if not MIN_INTERVAL_SECONDS <= args.interval < float("inf"):  # also False for NaN
        parser.error(f"--interval must be a number of seconds of at least {MIN_INTERVAL_SECONDS:g}: a snapshot "
                     f"is read only once its files have not changed for that long")
    if args.keep < 1:
        parser.error("--keep must be at least 1: the latest snapshot is the result if the master dies")
    if args.keep_every is not None and args.keep_every < 1:
        parser.error("--keep-every must be a positive number of evaluations")
    if args.recompute and args.delete_traces:
        parser.error("--recompute reads the traces and never deletes them; drop --delete-traces")
    if (args.reference_front is None and not args.recompute
            and (args.ideal_point is None) != (args.reference_point is None)):
        parser.error("without --reference-front, --ideal-point and --reference-point go together")
    return args


def _tolerant_output() -> None:
    """Makes stdout and stderr print what their encoding cannot show as escapes instead of raising."""
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(errors="backslashreplace")
        except (AttributeError, ValueError):  # not a TextIOWrapper (None under pythonw), or closed
            pass


def main(argv: Sequence[str] | None = None) -> int:
    _tolerant_output()  # a label outside the encoding of a redirected Windows stdout would stop the script
    args = _parse_args(argv)
    if not args.traces.is_dir():
        print(f"The folder {args.traces} does not exist", file=sys.stderr)
        return 1
    output = args.output_dir or args.traces.resolve().parent
    output.mkdir(parents=True, exist_ok=True)
    if args.reference_front is not None and not args.reference_front.is_file():
        print(f"The reference front {args.reference_front} does not exist", file=sys.stderr)
        return 1
    if args.recompute:
        return recompute(args.traces, output, args.reference_front, args.ideal_point, args.reference_point,
                         args.labels)
    reference = load_reference(args.reference_front, args.ideal_point, args.reference_point)
    if not args.once:
        print(f"Following {args.traces.resolve()} every {args.interval:g} s; Ctrl+C to stop", flush=True)
    print(f"Statistics in {output / STATS_FILE} and {output / EXTREMES_FILE}, and the aggregated front in "
          f"{output / REFERENCE_FILE}" + ("; the traces of saved snapshots are deleted" if args.delete_traces else ""),
          flush=True)
    if reference is not None:
        computed = "hypervolume, IGD and IGD+" if reference.front else "hypervolume"
        print(f"Indicators in {output / INDICATORS_FILE}: {computed}, with the objectives normalized between "
              f"{', '.join(map(number, reference.lower))} and {', '.join(map(number, reference.upper))}", flush=True)
    try:
        return watch(args.traces, args.labels, args.variables, args.once, args.interval, output,
                     args.delete_traces, args.keep, args.keep_every, reference=reference)
    except KeyboardInterrupt:
        return 0


if __name__ == "__main__":
    sys.exit(main())
