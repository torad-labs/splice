#!/usr/bin/env python3
"""Pin and validate the Claude Code version recorded by the Docker e2e receipt."""

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
VERSIONS = ROOT / "gateway/core/src/main/kotlin/splice/core/Versions.kt"
DOCKERFILE = ROOT / "checks/e2e/docker/Dockerfile"
INSIDE = ROOT / "checks/e2e/docker/inside.sh"
RUN = ROOT / "checks/e2e/docker/run.sh"


def match(path: Path, pattern: str, label: str) -> str:
    found = re.search(pattern, path.read_text(encoding="utf-8"))
    if not found:
        raise AssertionError(f"{label} is missing or malformed in {path.relative_to(ROOT)}")
    return found.group(1)


def source_pin() -> str:
    tested = match(
        VERSIONS,
        r'public const val TESTED_CLAUDE_CODE: String = "([0-9]+(?:\.[0-9]+)+)"',
        "TESTED_CLAUDE_CODE",
    )
    image = match(DOCKERFILE, r"ARG CLAUDE_CODE_VERSION=([0-9]+(?:\.[0-9]+)+)", "Dockerfile pin")
    if image != tested:
        raise AssertionError(f"Dockerfile pins Claude Code {image}, but Versions.kt records {tested}")

    inside = INSIDE.read_text(encoding="utf-8")
    required_inside = (
        'CLAUDE_CODE_ACTUAL="$(claude --version',
        '"claudeCodeVersion": sys.argv[4]',
        '"testedClaudeCodeVersion": sys.argv[5]',
        '[ "$CLAUDE_CODE_ACTUAL" = "$TESTED_CLAUDE_CODE" ]',
    )
    for token in required_inside:
        if token not in inside:
            raise AssertionError(f"inside.sh does not enforce receipt contract token: {token}")

    run = RUN.read_text(encoding="utf-8")
    required_run = (
        '--build-arg "CLAUDE_CODE_VERSION=$TESTED_CLAUDE_CODE"',
        '-e "SPLICE_TESTED_CLAUDE_CODE=$TESTED_CLAUDE_CODE"',
    )
    for token in required_run:
        if token not in run:
            raise AssertionError(f"run.sh does not carry the tested pin: {token}")
    return tested


def validate_receipt(path: Path, tested: str) -> None:
    receipt = json.loads(path.read_text(encoding="utf-8"))
    actual = receipt.get("claudeCodeVersion")
    receipt_tested = receipt.get("testedClaudeCodeVersion")
    if not actual:
        raise AssertionError("receipt has no actual claudeCodeVersion")
    if actual != tested or receipt_tested != tested:
        raise AssertionError(
            f"receipt used Claude Code {actual!r} against {receipt_tested!r}; Versions.kt records {tested!r}",
        )
    steps = receipt.get("steps", [])
    version_steps = [step for step in steps if step.get("step") == "Claude Code version matches the splice tested pin"]
    if len(version_steps) != 1 or version_steps[0].get("verdict") != "PASS":
        raise AssertionError("receipt lacks one passing Claude Code version step")


def main() -> None:
    tested = source_pin()
    if len(sys.argv) > 2:
        raise SystemExit("usage: receipt-selftest.py [receipt.json]")
    if len(sys.argv) == 2:
        validate_receipt(Path(sys.argv[1]), tested)
    print(f"receipt selftest: PASS (Claude Code {tested})")


if __name__ == "__main__":
    main()
