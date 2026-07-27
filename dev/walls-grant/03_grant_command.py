#!/usr/bin/env python3
"""§grant — operator-only wall grants, issued deterministically from the prompt line.

PORT-OF: torad-fleet .claude/hooks/modules/userpromptsubmit/03_grant_command.py, adapted to
splice's single wall gate (orchestrator.py `_is_wall_path`). The security reasoning is the
fleet's and is reproduced here because it is the whole point of the module.

WHY THIS EXISTS

Walls are grant-gated behind SPLICE_WALLS_OK=1, which orchestrator.py reads from the hook
process environment. Hook processes inherit the Claude Code CLI's environment, so nothing
inside a session can set it: the only way to grant was to quit and relaunch the CLI with the
variable exported. The legitimate path was unusable, which is a bad way to uphold an invariant.

THE STRUCTURAL PROPERTY THIS RELIES ON — read before changing anything here:

    A UserPromptSubmit hook fires ONLY on text a human typed into the prompt box.
    An assistant emits tool calls and assistant messages; it cannot emit a user prompt.

So a grant issued through this module is operator-only BY CONSTRUCTION, not by policy — there
is no string an agent can produce that reaches this code path. That is concept #924 applied to
the grant channel itself: the violation is unrepresentable, not merely forbidden.

It is also why this module must NEVER be reachable from PreToolUse, PostToolUse, SessionStart,
a skill, or a subagent. Moving it, or having any other lifecycle call `_issue()`, silently
converts the whole guard into a suggestion. The lifecycle assertion in `applies()` is there to
make that mistake loud rather than silent.

WHAT A GRANT DOES AND DELIBERATELY DOES NOT DO

A grant authorizes editing wall infrastructure (.rules/, .claude/hooks/, .claude/settings.json,
sgconfig.yml) THROUGH the normal gates. It never authorizes SKIPPING a gate:

  - `npm run gate:rules` and `npm run test:hooks` still have to pass. A grant does not make a
    red gate green; it lets you change the rule that is red and then prove it.
  - git --no-verify stays forbidden with or without a grant (CLAUDE.md §10).
  - The Stop-lifecycle tree scan still runs. A grant does not silence it.

If you find yourself wanting a grant to make a failing check go away, the honest move is to fix
the check.

USAGE
    /grant                      show grant state, what it unlocks, and what it cannot
    /grant <minutes> <reason>   issue a bounded grant; reason is MANDATORY and recorded
    /grant revoke               end it now
"""
from __future__ import annotations

import json
import pathlib
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent.parent))
from orchestrator.result import HookResult  # noqa: E402  # pyright: ignore[reportMissingImports]

# Walls work is focused and short. A long grant is a grant someone forgot to revoke, so the
# ceiling is deliberately tight — re-issuing is one line, an all-day open gate is a liability.
MAX_MINUTES = 120
DEFAULT_MINUTES = 30
GRANT_REL = ".claude/state/walls-grant.json"

WALL_PATHS_DOC = ".rules/, .claude/hooks/, .claude/settings.json, sgconfig.yml"


def _root() -> pathlib.Path:
    # This file installs to <repo>/.claude/hooks/modules/userpromptsubmit/, so the repo root is
    # FOUR parents up: [0]=userpromptsubmit [1]=modules [2]=hooks [3]=.claude [4]=<repo>.
    # Off-by-one here is silent and nasty: the module writes the grant to <repo>/.claude/.claude/...
    # while orchestrator.py reads <repo>/.claude/state/..., so /grant reports ACTIVE and the gate
    # keeps blocking. Caught 2026-07-26 by asserting the wall actually OPENS after an issue.
    return pathlib.Path(__file__).resolve().parents[4]


def _grant_file() -> pathlib.Path:
    return _root() / GRANT_REL


def _read() -> dict | None:
    try:
        raw = json.loads(_grant_file().read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    if not isinstance(raw, dict):
        return None
    return raw


def active_grant() -> dict | None:
    """The single source of truth for 'is a grant live right now'.

    orchestrator.py imports the same logic (see install.sh) so the write-time gate and this
    status report can never disagree — a second copy of an expiry rule is how a grant ends up
    reported active while the gate still blocks.
    """
    g = _read()
    if not g:
        return None
    try:
        until = float(g.get("until", 0))
    except (TypeError, ValueError):
        return None
    return g if until > time.time() else None


def _fmt_remaining(until: float) -> str:
    secs = max(0, int(until - time.time()))
    return f"{secs // 60}m {secs % 60}s"


def _status_text() -> str:
    g = active_grant()
    if not g:
        stale = _read()
        tail = ""
        if stale:
            tail = "\n(an expired grant record is present; it has no effect)"
        return (
            "§grant — NO ACTIVE WALL GRANT.\n"
            f"Wall paths stay blocked: {WALL_PATHS_DOC}\n\n"
            f"Issue one:  /grant <minutes up to {MAX_MINUTES}> <reason>\n"
            "Example:    /grant 45 land the ast-grep walls audit tier-2 rules" + tail
        )
    return (
        f"§grant — ACTIVE, {_fmt_remaining(float(g['until']))} remaining.\n"
        f"Reason: {g.get('reason', '(none recorded)')}\n"
        f"Issued: {g.get('issued_at', '?')}\n\n"
        f"Unlocks EDITS to: {WALL_PATHS_DOC}\n"
        "Does NOT skip any gate — gate:rules and test:hooks must still pass, and --no-verify\n"
        "stays forbidden. End early with: /grant revoke"
    )


def _issue(minutes: int, reason: str) -> str:
    minutes = max(1, min(minutes, MAX_MINUTES))
    until = time.time() + minutes * 60
    payload = {
        "until": until,
        "minutes": minutes,
        "reason": reason,
        "issued_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    }
    f = _grant_file()
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    return (
        f"§grant — ISSUED for {minutes}m.\n"
        f"Reason: {reason}\n\n"
        f"Wall edits are now permitted: {WALL_PATHS_DOC}\n"
        "Every change still needs its gate green (npm run gate:rules && npm run test:hooks),\n"
        "red-then-green. Revoke early with /grant revoke."
    )


def _revoke() -> str:
    f = _grant_file()
    existed = f.exists()
    try:
        f.unlink()
    except OSError:
        pass
    return "§grant — REVOKED. Wall paths are blocked again." if existed else "§grant — nothing to revoke."


def applies(data: dict) -> bool:
    # Defensive: this module is operator-only BECAUSE it is UserPromptSubmit-only. If a future
    # refactor globs it into another lifecycle, refuse rather than silently become grantable
    # by an assistant. The runner passes the lifecycle through when it knows it.
    if data.get("hook_event_name") not in (None, "UserPromptSubmit"):
        return False
    prompt = data.get("prompt")
    return isinstance(prompt, str) and prompt.strip().startswith("/grant")


def run(data: dict) -> HookResult | None:
    parts = data.get("prompt", "").strip().split()
    args = parts[1:]

    if not args:
        return HookResult(kind="inject", payload=_status_text(), module_name="grant")
    if args[0].lower() in ("revoke", "off", "end"):
        return HookResult(kind="inject", payload=_revoke(), module_name="grant")

    try:
        minutes = int(args[0])
        reason = " ".join(args[1:]).strip()
    except ValueError:
        minutes = DEFAULT_MINUTES
        reason = " ".join(args).strip()

    # A grant with no recorded reason is an audit hole: the file is the only record of WHY the
    # wall was opened, and "someone opened it at 3am" is not a reviewable answer.
    if not reason:
        return HookResult(
            kind="inject",
            payload=(
                "§grant — REFUSED: a reason is mandatory.\n"
                f"  /grant {minutes} <why you are opening the wall>\n"
                "The reason is written into the grant file and is the only record of intent."
            ),
            module_name="grant",
        )
    return HookResult(kind="inject", payload=_issue(minutes, reason), module_name="grant")


# --------------------------------------------------------------------------------------------
# CLI MODE — READ-ONLY, BY DESIGN. This is what `.claude/commands/grant.md` executes so the
# command is visible in the / autocomplete menu (a UserPromptSubmit interception alone is not:
# the menu only indexes command/skill FILES).
#
# It can INSPECT a grant. It deliberately CANNOT issue one, and that asymmetry is the whole
# security model — same split as torad-fleet's scripts/fleet-grant-status.py.
#
# Why: a `!`-line in a command file runs a shell command, and any shell command an operator can
# run, an assistant can also run with the Bash tool. `disable-model-invocation` stops an
# assistant invoking the COMMAND, but not from executing this file directly. So if `_issue()`
# were reachable from here, the grant would become assistant-issuable and the wall would be
# policy rather than structure.
#
# Issuing therefore stays exclusively on the UserPromptSubmit path above, which fires only on
# text a human typed. If someone later adds an `issue` mode here "for convenience", the
# recursion terminator this whole layer exists for is gone.
if __name__ == "__main__":
    print(_status_text())
    print()
    print("To ISSUE a grant, type this into the prompt box (it is intercepted by the hook,")
    print("which is the one channel an assistant structurally cannot reach):")
    print(f"    /grant <minutes up to {MAX_MINUTES}> <reason>")
    print("    /grant revoke")
