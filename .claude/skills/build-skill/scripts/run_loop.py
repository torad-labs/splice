"""
Description optimization loop for skill triggering.

Usage:
    python -m scripts.run_loop \\
      --eval-set <path-to-trigger-eval.json> \\
      --skill-path <path-to-skill> \\
      --model <model-id> \\
      --max-iterations 5 \\
      --verbose

Algorithm:
    1. Load eval set. Split 60% train / 40% test (deterministic by id).
    2. Evaluate current description: run each query 3 times via `claude -p`,
       check if skill triggered, compute trigger rate.
    3. Call Claude (with extended thinking) to propose a new description
       based on train failures.
    4. Evaluate new description on train + test.
    5. Keep best by test score. Repeat up to max_iterations.
    6. Write optimization_results.json and return best_description.

Requirements:
    - `claude` CLI available in PATH
    - The skill must be installed (or path must be accessible)
"""

from __future__ import annotations

import argparse
import json
import os
import random
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Optional


def load_eval_set(path: str) -> list[dict]:
    with open(path) as f:
        data = json.load(f)
    if isinstance(data, list):
        return data
    raise ValueError(f"Expected a JSON array in {path}, got {type(data)}")


def split_eval_set(evals: list[dict], train_ratio: float = 0.6) -> tuple[list[dict], list[dict]]:
    """Deterministic split by id (or index)."""
    sorted_evals = sorted(evals, key=lambda e: e.get("id", 0))
    n_train = max(1, int(len(sorted_evals) * train_ratio))
    return sorted_evals[:n_train], sorted_evals[n_train:]


def run_claude_query(query: str, skill_path: Optional[str], model: str, verbose: bool = False) -> str:
    """Run a single query via `claude -p` and return the output."""
    cmd = ["claude", "-p", query, "--model", model, "--no-color"]
    if skill_path:
        cmd += ["--skill", skill_path]

    if verbose:
        skill_info = f" (skill: {Path(skill_path).name})" if skill_path else " (no skill)"
        print(f"    Running: {query[:60]}...{skill_info}", file=sys.stderr)

    try:
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=120,
        )
        return result.stdout
    except subprocess.TimeoutExpired:
        if verbose:
            print("    Timeout!", file=sys.stderr)
        return ""
    except FileNotFoundError:
        print("Error: `claude` CLI not found in PATH. Install Claude Code to use this script.", file=sys.stderr)
        sys.exit(1)


def check_skill_triggered(output: str, skill_name: str) -> bool:
    """
    Heuristic: check if the skill influenced the output.
    This checks for skill-specific markers in the output or invocation signals.
    The `claude -p` output includes tool use context when a skill is invoked.
    """
    # Check for skill invocation markers in claude's output
    skill_lower = skill_name.lower().replace("-", " ").replace("_", " ")
    output_lower = output.lower()

    # Direct skill name mention (Claude often references the skill it used)
    if skill_lower in output_lower:
        return True

    # Check for structured output patterns common in skills
    # (headings, specific formatting that suggests skill was followed)
    skill_indicators = [
        "## ",  # Structured headings
        "### ",
        "```",  # Code blocks
    ]
    indicator_count = sum(1 for ind in skill_indicators if ind in output)
    if indicator_count >= 2 and len(output) > 200:
        return True

    return False


def evaluate_description(
    description: str,
    eval_set: list[dict],
    skill_path: str,
    model: str,
    runs_per_query: int = 3,
    verbose: bool = False,
) -> tuple[float, list[dict]]:
    """
    Evaluate a description on an eval set.
    Returns (trigger_rate, detailed_results).
    """
    # Write temporary skill with updated description
    skill_dir = Path(skill_path)
    skill_md = skill_dir / "SKILL.md"

    with open(skill_md) as f:
        original_content = f.read()

    # Patch description in frontmatter
    patched = re.sub(
        r'(^---\n.*?description:\s*)(.+?)(\n.*?---)',
        lambda m: m.group(1) + json.dumps(description) + m.group(3),
        original_content,
        flags=re.DOTALL,
    )

    if patched == original_content:
        # Try multiline description format
        patched = re.sub(
            r'(description:\s*>-\s*\n)((?:  .+\n)*)',
            lambda m: f"description: {json.dumps(description)}\n",
            original_content,
        )

    with tempfile.TemporaryDirectory() as tmpdir:
        # Copy skill to temp dir
        import shutil
        tmp_skill_dir = Path(tmpdir) / skill_dir.name
        shutil.copytree(skill_dir, tmp_skill_dir)

        # Write patched SKILL.md
        with open(tmp_skill_dir / "SKILL.md", "w") as f:
            f.write(patched)

        results = []
        triggered_count = 0
        total_runs = 0

        for item in eval_set:
            query = item["query"]
            should_trigger = item["should_trigger"]
            item_triggers = []

            for _ in range(runs_per_query):
                output = run_claude_query(query, str(tmp_skill_dir), model, verbose)
                triggered = check_skill_triggered(output, skill_dir.name)
                item_triggers.append(triggered)
                total_runs += 1

            trigger_rate = sum(item_triggers) / len(item_triggers)
            # Correct = (should_trigger and triggered often) or (not should_trigger and not triggered often)
            is_correct = (trigger_rate >= 0.5) == should_trigger
            if is_correct:
                triggered_count += 1

            results.append({
                "id": item.get("id"),
                "query": query,
                "should_trigger": should_trigger,
                "trigger_rate": trigger_rate,
                "correct": is_correct,
            })

            if verbose:
                status = "✓" if is_correct else "✗"
                print(
                    f"    {status} [{trigger_rate:.0%} triggered] "
                    f"{'should' if should_trigger else 'should not'} trigger: "
                    f"{query[:50]}...",
                    file=sys.stderr
                )

    accuracy = triggered_count / len(eval_set) if eval_set else 0.0
    return accuracy, results


def propose_improvement(
    current_description: str,
    train_results: list[dict],
    skill_path: str,
    model: str,
) -> str:
    """Call Claude to propose a better description based on failures."""
    failures = [r for r in train_results if not r["correct"]]
    false_positives = [r for r in failures if not r["should_trigger"]]
    false_negatives = [r for r in failures if r["should_trigger"]]

    skill_md = Path(skill_path) / "SKILL.md"
    with open(skill_md) as f:
        skill_content = f.read()[:3000]  # First 3000 chars for context

    prompt = f"""You are optimizing a skill description for Claude Code skills.

The skill's SKILL.md (first 3000 chars):
```
{skill_content}
```

Current description:
{current_description}

Evaluation results on training set:

False negatives (should trigger but didn't):
{json.dumps([r["query"] for r in false_negatives], indent=2)}

False positives (should NOT trigger but did):
{json.dumps([r["query"] for r in false_positives], indent=2)}

Your task: write an improved skill description that fixes these triggering errors.

The description goes in the YAML frontmatter `description:` field. It should:
1. Clearly specify WHEN to use this skill (what user intents, phrases, contexts)
2. State what the skill does
3. Be specific enough to avoid false positives
4. Be broad enough to catch genuine use cases (the false negatives above)

Return ONLY the new description text — no YAML wrapper, no explanation, just the description string.
"""

    result = subprocess.run(
        ["claude", "-p", prompt, "--model", model, "--no-color"],
        capture_output=True,
        text=True,
        timeout=180,
    )
    new_desc = result.stdout.strip()
    # Strip any YAML wrapper if model included one
    new_desc = re.sub(r'^description:\s*[">-]?\s*', '', new_desc)
    new_desc = new_desc.strip('"\'')
    return new_desc if new_desc else current_description


def extract_current_description(skill_path: str) -> str:
    """Extract the current description from SKILL.md frontmatter."""
    skill_md = Path(skill_path) / "SKILL.md"
    with open(skill_md) as f:
        content = f.read()

    # Try simple description: value
    m = re.search(r'^description:\s*(.+)$', content, re.MULTILINE)
    if m:
        return m.group(1).strip().strip('"\'')

    # Try multiline > or >-
    m = re.search(r'^description:\s*>-?\s*\n((?:  .+\n)+)', content, re.MULTILINE)
    if m:
        lines = [line.strip() for line in m.group(1).splitlines()]
        return ' '.join(lines)

    return ""


def main():
    parser = argparse.ArgumentParser(description="Optimize skill description for triggering accuracy")
    parser.add_argument("--eval-set", required=True, help="Path to trigger-eval.json")
    parser.add_argument("--skill-path", required=True, help="Path to skill directory")
    parser.add_argument("--model", default="claude-sonnet-4-5", help="Model ID to use for testing")
    parser.add_argument("--max-iterations", type=int, default=5)
    parser.add_argument("--runs-per-query", type=int, default=3)
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    skill_path = Path(args.skill_path).resolve()
    if not (skill_path / "SKILL.md").exists():
        print(f"Error: {skill_path}/SKILL.md not found", file=sys.stderr)
        sys.exit(1)

    evals = load_eval_set(args.eval_set)
    train_set, test_set = split_eval_set(evals)

    print(f"Eval set: {len(evals)} queries ({len(train_set)} train, {len(test_set)} test)")

    current_description = extract_current_description(str(skill_path))
    print(f"Current description ({len(current_description)} chars):\n  {current_description[:100]}...")

    results_by_iteration = []
    best_description = current_description
    best_test_score = 0.0

    for iteration in range(args.max_iterations + 1):  # +1 to evaluate initial description
        is_initial = iteration == 0
        desc_label = "Initial" if is_initial else f"Iteration {iteration}"

        print(f"\n{'─' * 50}")
        print(f"{desc_label}")

        # Evaluate on train
        if args.verbose:
            print("  Evaluating on train set...")
        train_score, train_results = evaluate_description(
            current_description, train_set, str(skill_path), args.model,
            args.runs_per_query, args.verbose
        )

        # Evaluate on test
        if args.verbose:
            print("  Evaluating on test set...")
        test_score, test_results = evaluate_description(
            current_description, test_set, str(skill_path), args.model,
            args.runs_per_query, args.verbose
        )

        print(f"  Train: {train_score:.1%} | Test: {test_score:.1%}")

        results_by_iteration.append({
            "iteration": iteration,
            "description": current_description,
            "train_score": train_score,
            "test_score": test_score,
            "train_results": train_results,
            "test_results": test_results,
        })

        if test_score > best_test_score:
            best_test_score = test_score
            best_description = current_description
            print(f"  ↑ New best (test score: {test_score:.1%})")

        # Stop if perfect or last iteration
        if train_score == 1.0 and test_score == 1.0:
            print("  Perfect score — stopping early")
            break
        if iteration == args.max_iterations:
            break

        # Propose improvement
        print("  Proposing improvement...")
        new_description = propose_improvement(
            current_description, train_results, str(skill_path), args.model
        )
        if new_description == current_description:
            print("  No change proposed — stopping")
            break
        print(f"  New description ({len(new_description)} chars):\n    {new_description[:100]}...")
        current_description = new_description

    # Write results
    output = {
        "best_description": best_description,
        "best_test_score": best_test_score,
        "iterations": results_by_iteration,
    }

    results_path = Path(args.eval_set).parent / "optimization_results.json"
    with open(results_path, "w") as f:
        json.dump(output, f, indent=2)

    print(f"\n{'═' * 50}")
    print(f"Optimization complete. Best test score: {best_test_score:.1%}")
    print(f"Results written to: {results_path}")
    print(f"\nbest_description:\n{best_description}")

    # Also print as JSON for easy parsing
    print(f"\n{json.dumps({'best_description': best_description})}")


if __name__ == "__main__":
    main()
