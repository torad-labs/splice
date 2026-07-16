#!/usr/bin/env python3
"""Inject campaign laws through the shared SessionStart module runner."""
from __future__ import annotations

import pathlib
import subprocess
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent.parent))
from orchestrator.result import HookResult  # noqa: E402  # pyright: ignore[reportMissingImports]
from lib.astgrep_gate import find_project_root  # noqa: E402  # pyright: ignore[reportMissingImports]

MODULE_NAME = "10_laws"


def applies(data: dict) -> bool:
    return True


def run(data: dict) -> HookResult | None:
    project_root = find_project_root(data.get("cwd") or data.get("project_dir") or ".")
    manifest = project_root / "dev" / "campaigns" / "manifest.py"
    if not manifest.exists():
        return None

    result = subprocess.run(
        ["python3", str(manifest), "laws"],
        cwd=project_root,
        capture_output=True,
        text=True,
        check=False,
    )
    payload = result.stdout.strip()
    if result.returncode != 0 or not payload:
        return None
    return HookResult(kind="inject", payload=payload, module_name=MODULE_NAME)
