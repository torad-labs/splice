"""Generic hook dispatcher for torad-fleet.

Stolen from kandi-main / qgre-agent .claude/hooks/orchestrator/runner.py, with
TWO deliberate divergences documented in checks/HARNESS-REVIEW.md:

  1. FAIL-CLOSED security modules (KMP-MIGRATION-PLAN.md §2.6, R1). The inherited
     runner swallows every module exception (fail-open) — correct for STYLE rules,
     wrong for a SECURITY gate: a crashing scan-clean module would silently let a
     child_process / eval( edit through. A module that declares `FAIL_CLOSED = True`
     and raises now emits a block instead of being swallowed.

  2. Lifecycle-aware block emit (§2.5 Fact 4). PreToolUse blocks emit exit-2 +
     stderr (the reliable deny per the Claude Code docs — JSON is only parsed on
     exit 0, so the legacy {"decision":"block"} would be ignored under exit 2).
     Stop / SubagentStop emit the {"decision":"block"} JSON (exit 0) which forces
     the agent to continue. PostToolUse cannot block; inject becomes additionalContext.

Each lifecycle entry script calls dispatch("<lifecycle>"). The dispatcher globs
modules/<lifecycle>/*.py (skipping `_`-prefixed = disabled, and __init__.py),
runs each module's applies()/run(), and applies the state machine: block stops the
chain and emits; inject accumulates and emits at end; warn writes to stderr.
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import os
import sys
import time
import traceback
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

_HOOKS_ROOT = Path(__file__).resolve().parent.parent
_MODULES_ROOT = _HOOKS_ROOT / "modules"

if str(_HOOKS_ROOT) not in sys.path:
    sys.path.insert(0, str(_HOOKS_ROOT))

from orchestrator.result import HookResult  # noqa: E402  # pyright: ignore[reportMissingImports]
from lib.tool_input import file_path_of, is_path_exempt  # noqa: E402  # pyright: ignore[reportMissingImports]

# Renamed from kandi's KANDIBUDDY_HOOKS — the per-command kill switch.
KILL_SWITCH_ENV = "OC_WORKSPACES_HOOKS"
KILL_SWITCH_OFF = "off"
# Operator on/off switch (.claude/hooks/enforcement_toggle.py) — a FILE flag alongside the launch-time
# env var above, so all write-time hooks can be toggled live from a python one-liner. Its mere
# existence disables every write-time hook; deleting it re-enables them. Fail-safe: a stat error
# falls through to ENFORCING (never silently off).
KILL_SWITCH_FILE = _HOOKS_ROOT.parent / "state" / "enforcement-disabled"


def _hooks_disabled() -> bool:
    if os.environ.get(KILL_SWITCH_ENV) == KILL_SWITCH_OFF:
        return True
    try:
        return KILL_SWITCH_FILE.exists()
    except OSError:
        return False
CHAIN_BUDGET_SECONDS = 9.0
DISABLED_PREFIX = "_"

# The only lifecycle whose block must use exit-2 (the reliable PreToolUse deny).
_PRETOOL_LIFECYCLE = "pretooluse"

# HARDEN-1 (qgre runner lineage): durable module-crash / stdout-pollution record.
# Lives in gitignored runtime state beside deploy-gate.json; logging is fail-open —
# a log-write failure must never break the dispatch it observes.
_ERROR_LOG = _HOOKS_ROOT.parent / "state" / "hook-module-errors.log"


def _append_error_log(entry: str) -> None:
    try:
        _ERROR_LOG.parent.mkdir(parents=True, exist_ok=True)
        with _ERROR_LOG.open("a", encoding="utf-8") as f:
            f.write(entry)
            f.flush()
            os.fsync(f.fileno())
    except Exception:
        pass  # never let observability break the runner


def _log_module_error(module_path: Path, lifecycle: str, exc: BaseException) -> None:
    trace = "".join(traceback.format_exception(type(exc), exc, exc.__traceback__))
    stamp = datetime.now(timezone.utc).isoformat(timespec="seconds")
    _append_error_log(
        f"[{stamp}] lifecycle={lifecycle} phase=module-crash module={module_path.stem} "
        f"path={module_path}\n{trace}" + "-" * 80 + "\n"
    )


def _log_stdout_pollution(module_path: Path, lifecycle: str, text: str) -> None:
    """A module (or a subprocess it leaked) wrote to stdout — the harness JSON
    channel. The redirect in _run_module already silenced it structurally; this
    records the offender so the stray print gets fixed instead of resurfacing
    as an invalid-hook-output parse error."""
    stamp = datetime.now(timezone.utc).isoformat(timespec="seconds")
    _append_error_log(
        f"[{stamp}] lifecycle={lifecycle} phase=stdout-pollution module={module_path.stem} "
        f"({len(text)} bytes silenced):\n{text[:2000]}\n" + "-" * 80 + "\n"
    )


def dispatch(lifecycle: str) -> None:
    if _hooks_disabled():
        return
    try:
        data = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        return
    if not isinstance(data, dict):
        return

    path_exempt = is_path_exempt(file_path_of(data))

    modules_dir = _MODULES_ROOT / lifecycle
    if not modules_dir.is_dir():
        return

    chain_start = time.monotonic()
    inject_payloads: list[str] = []

    for module_path in _sorted_module_files(modules_dir):
        if (time.monotonic() - chain_start) > CHAIN_BUDGET_SECONDS:
            continue

        result = _run_module(module_path, data, path_exempt=path_exempt, lifecycle=lifecycle)
        if result is None:
            continue

        if result.kind == "block":
            _emit_block(lifecycle, result.payload)
            return
        if result.kind == "inject":
            inject_payloads.append(result.payload)
            continue
        if result.kind == "warn":
            sys.stderr.write(result.payload.rstrip() + "\n")
            continue

    if inject_payloads:
        _emit_inject(lifecycle, "\n\n---\n\n".join(inject_payloads))


def _emit_block(lifecycle: str, reason: str) -> None:
    if lifecycle == _PRETOOL_LIFECYCLE:
        # exit 2 feeds stderr back to Claude and reliably denies the tool call.
        sys.stderr.write(reason.rstrip() + "\n")
        sys.exit(2)
    # Stop / SubagentStop (forces continue) / PostToolUse (surfaces feedback).
    sys.stdout.write(json.dumps({"decision": "block", "reason": reason}))


def _emit_inject(lifecycle: str, text: str) -> None:
    if lifecycle == "posttooluse":
        sys.stdout.write(
            json.dumps(
                {
                    "hookSpecificOutput": {
                        "hookEventName": "PostToolUse",
                        "additionalContext": text,
                    }
                }
            )
        )
        return
    sys.stdout.write(text)


def _sorted_module_files(modules_dir: Path) -> list[Path]:
    candidates = sorted(modules_dir.glob("*.py"))
    return [p for p in candidates if not p.name.startswith(DISABLED_PREFIX) and p.name != "__init__.py"]


def _run_module(
    path: Path, data: dict, path_exempt: bool = False, lifecycle: str = ""
) -> Optional[HookResult]:
    module = None
    # HARDEN-1: module stdout is captured off the harness JSON channel for the
    # whole load/applies/run span; anything found in the buffer is logged, never emitted.
    captured = io.StringIO()
    try:
        with contextlib.redirect_stdout(captured):
            spec = importlib.util.spec_from_file_location(path.stem, path)
            if spec is None or spec.loader is None:
                return None
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)

            # On exempt paths (generated/vendored), only run modules that explicitly
            # opt in via BYPASS_EXEMPTION = True (e.g. the Bash-command guards).
            if path_exempt and not getattr(module, "BYPASS_EXEMPTION", False):
                return None

            if hasattr(module, "applies") and not module.applies(data):
                return None
            if not hasattr(module, "run"):
                return None

            result = module.run(data)
        if result is not None and not isinstance(result, HookResult):
            return None
        return result
    except Exception as exc:
        _log_module_error(path, lifecycle, exc)
        # FAIL-CLOSED (R1): a security module that crashes blocks; style modules
        # (no FAIL_CLOSED flag) stay fail-open exactly as the inherited runner did.
        if module is not None and getattr(module, "FAIL_CLOSED", False):
            return HookResult(
                kind="block",
                payload=(
                    f"§fail-closed — security hook {path.stem} crashed; blocking the "
                    f"tool call rather than letting it through.\n\n{traceback.format_exc()}"
                    f"\ntraceback_log: {_ERROR_LOG}"
                ),
                module_name=path.stem,
            )
        return None
    finally:
        polluted = captured.getvalue()
        if polluted:
            _log_stdout_pollution(path, lifecycle, polluted)
