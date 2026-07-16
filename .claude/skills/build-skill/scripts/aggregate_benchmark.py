"""
Aggregate evaluation results for one iteration into benchmark.json and benchmark.md.

Usage:
    python -m scripts.aggregate_benchmark <workspace>/iteration-N --skill-name <name>

Reads:
    <iteration_dir>/eval-*/with_skill/grading.json
    <iteration_dir>/eval-*/with_skill/timing.json
    <iteration_dir>/eval-*/without_skill/grading.json   (or old_skill/)
    <iteration_dir>/eval-*/eval_metadata.json

Writes:
    <iteration_dir>/benchmark.json
    <iteration_dir>/benchmark.md
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys
from pathlib import Path
from typing import Optional


def find_eval_dirs(iteration_dir: Path) -> list[Path]:
    """Return sorted list of eval-* directories."""
    dirs = sorted([d for d in iteration_dir.iterdir() if d.is_dir() and d.name.startswith("eval-")])
    return dirs


def load_json(path: Path) -> Optional[dict]:
    if path.exists():
        with open(path) as f:
            return json.load(f)
    return None


def detect_baseline_name(eval_dir: Path) -> Optional[str]:
    """Detect whether baseline is 'without_skill' or 'old_skill'."""
    for name in ("without_skill", "old_skill"):
        if (eval_dir / name).is_dir():
            return name
    return None


def grade_summary(grading: dict | None) -> tuple[float, int, int]:
    """Return (pass_rate, passed, total) from a grading.json dict."""
    if grading is None:
        return (0.0, 0, 0)
    expectations = grading.get("expectations", [])
    if not expectations:
        return (0.0, 0, 0)
    total = len(expectations)
    passed = sum(1 for e in expectations if e.get("passed", False))
    return (passed / total, passed, total)


def stddev(values: list[float]) -> float:
    if len(values) < 2:
        return 0.0
    mean = sum(values) / len(values)
    variance = sum((v - mean) ** 2 for v in values) / len(values)
    return math.sqrt(variance)


def mean(values: list[float]) -> float:
    if not values:
        return 0.0
    return sum(values) / len(values)


def collect_config(eval_dirs: list[Path], config_name: str) -> list[dict]:
    """Collect per-eval data for a given configuration name (e.g. 'with_skill')."""
    results = []
    for eval_dir in eval_dirs:
        config_dir = eval_dir / config_name
        if not config_dir.is_dir():
            continue

        metadata = load_json(eval_dir / "eval_metadata.json") or {}
        grading = load_json(config_dir / "grading.json")
        timing = load_json(config_dir / "timing.json")

        pass_rate, passed, total = grade_summary(grading)

        results.append({
            "eval_id": metadata.get("eval_id", eval_dir.name),
            "eval_name": metadata.get("eval_name", eval_dir.name.removeprefix("eval-")),
            "pass_rate": round(pass_rate, 4),
            "assertions_total": total,
            "assertions_passed": passed,
            "duration_ms": timing.get("duration_ms", 0) if timing else 0,
            "total_tokens": timing.get("total_tokens", 0) if timing else 0,
        })
    return results


def aggregate_stats(evals: list[dict]) -> dict:
    pass_rates = [e["pass_rate"] for e in evals]
    durations = [e["duration_ms"] for e in evals if e["duration_ms"]]
    tokens = [e["total_tokens"] for e in evals if e["total_tokens"]]
    return {
        "pass_rate_mean": round(mean(pass_rates), 4),
        "pass_rate_stddev": round(stddev(pass_rates), 4),
        "duration_ms_mean": round(mean(durations)),
        "duration_ms_stddev": round(stddev(durations)),
        "total_tokens_mean": round(mean(tokens)),
        "total_tokens_stddev": round(stddev(tokens)),
        "evals_count": len(evals),
    }


def build_benchmark(iteration_dir: Path, skill_name: str, analyst_notes: str = "") -> dict:  # type: ignore[type-arg]
    eval_dirs = find_eval_dirs(iteration_dir)
    if not eval_dirs:
        print(f"No eval-* directories found in {iteration_dir}", file=sys.stderr)
        sys.exit(1)

    # Detect baseline name from first eval dir that has one
    baseline_name = None
    for ed in eval_dirs:
        baseline_name = detect_baseline_name(ed)
        if baseline_name:
            break

    # Collect with_skill data
    with_skill_evals = collect_config(eval_dirs, "with_skill")
    with_skill_agg = aggregate_stats(with_skill_evals)

    configs = [
        {
            "name": "with_skill",
            "display_name": "With Skill",
            "evals": with_skill_evals,
            "aggregate": with_skill_agg,
        }
    ]

    # Collect baseline data if present
    delta = {}
    if baseline_name:
        baseline_evals = collect_config(eval_dirs, baseline_name)
        baseline_agg = aggregate_stats(baseline_evals)
        configs.append({
            "name": baseline_name,
            "display_name": "Without Skill" if baseline_name == "without_skill" else "Old Skill",
            "evals": baseline_evals,
            "aggregate": baseline_agg,
        })
        delta = {
            "pass_rate": round(with_skill_agg["pass_rate_mean"] - baseline_agg["pass_rate_mean"], 4),
            "duration_ms": round(with_skill_agg["duration_ms_mean"] - baseline_agg["duration_ms_mean"]),
            "total_tokens": round(with_skill_agg["total_tokens_mean"] - baseline_agg["total_tokens_mean"]),
        }

    iteration_num = int(iteration_dir.name.replace("iteration-", "")) if "iteration-" in iteration_dir.name else 1

    return {
        "skill_name": skill_name,
        "iteration": iteration_num,
        "configs": configs,
        "delta": delta,
        "analyst_notes": analyst_notes,
    }


def format_pct(v: float) -> str:
    return f"{v * 100:.1f}%"


def format_delta(v: float, positive_good: bool = True) -> str:
    if v == 0:
        return "—"
    sign = "+" if v > 0 else ""
    if positive_good:
        indicator = "↑" if v > 0 else "↓"
    else:
        indicator = "↓" if v > 0 else "↑"
    return f"{sign}{v:.4f} {indicator}"


def render_markdown(benchmark: dict) -> str:
    lines = [
        f"# Benchmark — {benchmark['skill_name']} — Iteration {benchmark['iteration']}",
        "",
    ]

    # Aggregate table
    lines += [
        "## Aggregate",
        "",
        "| Config | Pass Rate | ±Stddev | Avg Duration | Avg Tokens |",
        "|--------|-----------|---------|--------------|------------|",
    ]
    for cfg in benchmark["configs"]:
        agg = cfg["aggregate"]
        lines.append(
            f"| {cfg['display_name']} "
            f"| {format_pct(agg['pass_rate_mean'])} "
            f"| ±{format_pct(agg['pass_rate_stddev'])} "
            f"| {agg['duration_ms_mean']:,}ms "
            f"| {agg['total_tokens_mean']:,} |"
        )

    delta = benchmark.get("delta", {})
    if delta:
        lines += [
            "",
            "**Delta (with_skill vs baseline):**",
            f"- Pass rate: {format_delta(delta.get('pass_rate', 0))}",
            f"- Duration: {delta.get('duration_ms', 0):+,}ms",
            f"- Tokens: {delta.get('total_tokens', 0):+,}",
        ]

    # Per-eval table
    lines += [
        "",
        "## Per-Eval Results",
        "",
    ]
    for cfg in benchmark["configs"]:
        lines += [
            f"### {cfg['display_name']}",
            "",
            "| Eval | Pass Rate | Passed/Total | Duration | Tokens |",
            "|------|-----------|--------------|----------|--------|",
        ]
        for e in cfg["evals"]:
            lines.append(
                f"| {e['eval_name']} "
                f"| {format_pct(e['pass_rate'])} "
                f"| {e['assertions_passed']}/{e['assertions_total']} "
                f"| {e['duration_ms']:,}ms "
                f"| {e['total_tokens']:,} |"
            )
        lines.append("")

    # Analyst notes
    if benchmark.get("analyst_notes"):
        lines += [
            "## Analyst Notes",
            "",
            benchmark["analyst_notes"],
        ]

    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description="Aggregate eval results into benchmark.json and benchmark.md")
    parser.add_argument("iteration_dir", help="Path to iteration-N directory")
    parser.add_argument("--skill-name", default="skill", help="Skill name for display")
    parser.add_argument("--analyst-notes", default="", help="Analyst observations to embed")
    args = parser.parse_args()

    iteration_dir = Path(args.iteration_dir).resolve()
    if not iteration_dir.is_dir():
        print(f"Error: {iteration_dir} is not a directory", file=sys.stderr)
        sys.exit(1)

    benchmark = build_benchmark(iteration_dir, args.skill_name, args.analyst_notes)

    # Write benchmark.json
    benchmark_json = iteration_dir / "benchmark.json"
    with open(benchmark_json, "w") as f:
        json.dump(benchmark, f, indent=2)
    print(f"Written: {benchmark_json}")

    # Write benchmark.md
    benchmark_md = iteration_dir / "benchmark.md"
    with open(benchmark_md, "w") as f:
        f.write(render_markdown(benchmark))
    print(f"Written: {benchmark_md}")

    # Print summary
    for cfg in benchmark["configs"]:
        agg = cfg["aggregate"]
        print(f"\n{cfg['display_name']}: {format_pct(agg['pass_rate_mean'])} pass rate "
              f"(±{format_pct(agg['pass_rate_stddev'])}) across {agg['evals_count']} evals")
    if benchmark.get("delta"):
        d = benchmark["delta"]
        print(f"\nDelta: {format_delta(d.get('pass_rate', 0))} pass rate, "
              f"{d.get('duration_ms', 0):+,}ms, {d.get('total_tokens', 0):+,} tokens")


if __name__ == "__main__":
    main()
