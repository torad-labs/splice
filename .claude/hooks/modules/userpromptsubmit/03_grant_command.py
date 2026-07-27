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

SIGNED SINCE 2026-07-27 (review round 2). The structural property above protects ISSUING. It said
nothing about the file the gate TRUSTS, and .claude/state/ was not a wall path — so an assistant
could write its own walls-grant.json and open every wall in one Write, never touching this module.
Grants are now HMAC-signed with a key outside the repo (lib/walls_grant.py, which is the single
implementation both this module and orchestrator.py call). Read that file's header before changing
anything here; it also records, honestly, what signing does NOT close.

USAGE
    /grant                              show grant state, what it unlocks, and what it cannot
    /grant <minutes> <reason>           issue a bounded grant; reason is MANDATORY and recorded
    /grant <minutes> --paths P,Q <reason>   ...scoped to those wall paths only
    /grant revoke                       end it now
"""
from __future__ import annotations

import json
import pathlib
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent.parent))
from orchestrator.result import HookResult  # noqa: E402  # pyright: ignore[reportMissingImports]
from lib import walls_grant  # noqa: E402  # pyright: ignore[reportMissingImports, reportAttributeAccessIssue]

# Walls work is focused and short. A long grant is a grant someone forgot to revoke, so the
# ceiling is deliberately tight — re-issuing is one line, an all-day open gate is a liability.
MAX_MINUTES = 120
DEFAULT_MINUTES = 30
GRANT_REL = walls_grant.GRANT_REL

WALL_PATHS = (".rules", ".claude/hooks", ".claude/settings.json", "sgconfig.yml")
WALL_PATHS_DOC = ".rules/, .claude/hooks/, .claude/settings.json, sgconfig.yml"

# The / menu files are installed OUTSIDE version control (.claude/commands/ is gitignored) and
# outside WALL_PATHS, so the file an operator's `/grant` keystroke actually executes can drift
# from the reviewed canonical copy with no git trace and no gate (review round 2). This does not
# enlarge what an agent can DO — Bash already covers direct action — it closes SILENT divergence
# between what was reviewed and what runs. Reported here because /grant status is the one screen
# an operator reads before trusting this channel.
MENU_FILES = (
    (".claude/commands/grant.md", "dev/walls-grant/grant.command.md"),
    (".claude/commands/install-walls.md", "dev/walls-grant/install-walls.command.md"),
)


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
    return walls_grant.load(_root())


def active_grant() -> dict | None:
    """The single source of truth for 'is a grant live right now'.

    orchestrator.py calls the SAME function (lib/walls_grant.active) so the write-time gate and
    this status report can never disagree — a second copy of an expiry rule is how a grant ends
    up reported active while the gate still blocks. Signature and expiry both live there.
    """
    return walls_grant.active(_root())


def _menu_drift() -> list[str]:
    """Installed / menu files that no longer match their tracked canonical copy."""
    root = _root()
    drifted = []
    for installed, canonical in MENU_FILES:
        try:
            if (root / installed).read_bytes() != (root / canonical).read_bytes():
                drifted.append(installed)
        except OSError:
            drifted.append(f"{installed} (missing or unreadable)")
    return drifted


def _integrity_tail() -> str:
    """Everything /grant status should say about whether this channel is intact."""
    lines = []
    if not walls_grant.key_path().exists():
        lines.append(
            f"\n! NO SIGNING KEY at {walls_grant.key_path()} — grants cannot be issued and the gate\n"
            "  refuses unsigned records. Run: bash dev/walls-grant/install.sh"
        )
    drift = _menu_drift()
    if drift:
        lines.append(
            "\n! MENU DRIFT — these installed command files differ from their tracked canonical\n"
            "  copies, so what /grant runs is not what was reviewed:\n"
            + "".join(f"    {d}\n" for d in drift)
            + "  Reconcile with: bash dev/walls-grant/install.sh"
        )
    stale = _read()
    if stale and not walls_grant.verify(stale):
        # Two very different situations, and calling both an attack is how a security warning
        # gets tuned out. A record with NO `sig` at all is almost always a leftover written by
        # the pre-2026-07-27 module, which did not sign; a record that HAS a signature and still
        # fails is either tampering or a key that no longer matches. Say which.
        if "sig" not in stale:
            lines.append(
                "\n· An UNSIGNED grant record is present and has no effect (the gate verifies the\n"
                "  signature before it reads `until`). This is the expected leftover from a grant\n"
                "  issued before signing landed. Clear it with: /grant revoke"
            )
        else:
            lines.append(
                "\n! A SIGNED grant record is present whose signature does NOT verify. It has no\n"
                "  effect. Either the payload was edited after issue, or the signing key changed\n"
                f"  ({walls_grant.key_path()}). If you did not edit this file, something tried to\n"
                "  mint or extend a grant — that is worth looking at before you clear it."
            )
    return "".join(lines)


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
            f"Scoped:     /grant 45 --paths .rules land the tier-2 rules\n"
            "Example:    /grant 45 land the ast-grep walls audit tier-2 rules" + tail
            + _integrity_tail()
        )
    scope = g.get("paths")
    return (
        f"§grant — ACTIVE, {_fmt_remaining(float(g['until']))} remaining.\n"
        f"Reason: {g.get('reason', '(none recorded)')}\n"
        f"Issued: {g.get('issued_at', '?')}  session: {g.get('session_id', '(unrecorded)')}\n\n"
        f"Unlocks EDITS to: {', '.join(scope) if scope else WALL_PATHS_DOC}\n"
        "Does NOT skip any gate — gate:rules and test:hooks must still pass, and --no-verify\n"
        "stays forbidden. End early with: /grant revoke"
        + _integrity_tail()
    )


def _issue(minutes: int, reason: str, paths: list[str], session_id: str) -> str:
    minutes = max(1, min(minutes, MAX_MINUTES))
    until = time.time() + minutes * 60
    payload = {
        "until": until,
        "minutes": minutes,
        "reason": reason,
        "issued_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        # Inside the signed bytes on purpose: the audit record must answer WHO opened the wall and
        # from where, not only when it expires, and it must not be editable after the fact.
        "session_id": session_id,
    }
    if paths:
        payload["paths"] = paths
    try:
        payload = walls_grant.sign(payload)
    except RuntimeError as e:
        # Loud, never a silently-unsigned grant: that would report ACTIVE while the gate blocks.
        return f"§grant — REFUSED: {e}"
    f = _grant_file()
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    return (
        f"§grant — ISSUED for {minutes}m (signed).\n"
        f"Reason: {reason}\n\n"
        f"Wall edits are now permitted: {', '.join(paths) if paths else WALL_PATHS_DOC}\n"
        "Every change still needs its gate green (npm run gate:rules && npm run test:hooks),\n"
        "red-then-green. Revoke early with /grant revoke."
        + _integrity_tail()
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
    #
    # The None arm is deliberate, and it is a COMPROMISE, not an oversight (review round 2 asked).
    # Traced: runner.py passes the raw stdin payload through without injecting a lifecycle, so this
    # module sees exactly what Claude Code sent. Requiring the literal "UserPromptSubmit" would
    # make the tripwire louder, but it breaks /grant outright on any CLI payload shape that omits
    # the field, and which shapes have shipped is not knowable from inside this repo. Guessing
    # wrong here disables the operator's only legitimate channel. The defence-in-depth that makes
    # the tolerance affordable: this module's placement under modules/userpromptsubmit/, the
    # `prompt`-is-a-string gate two lines below (every non-prompt lifecycle fails it), and — since
    # 2026-07-27 — the fact that a forged or hand-rolled invocation still cannot produce a grant
    # the gate will honour without the signing key.
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
        rest = args[1:]
    except ValueError:
        minutes = DEFAULT_MINUTES
        rest = args

    # Optional scope, immediately after the minutes: `/grant 30 --paths .rules <reason>`. A flag
    # rather than a trailing positional, because a free-text reason and a bare path are not
    # distinguishable. Unknown names are REFUSED, never dropped — a typo'd path that silently
    # widened the grant to everything is the failure this feature exists to prevent.
    paths: list[str] = []
    if len(rest) >= 2 and rest[0] == "--paths":
        requested = [p.strip() for p in rest[1].split(",") if p.strip()]
        unknown = [p for p in requested if p not in WALL_PATHS]
        if unknown:
            return HookResult(
                kind="inject",
                payload=(
                    f"§grant — REFUSED: not wall paths: {', '.join(unknown)}\n"
                    f"Valid: {', '.join(WALL_PATHS)}"
                ),
                module_name="grant",
            )
        paths = requested
        rest = rest[2:]
    reason = " ".join(rest).strip()

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
    session_id = str(data.get("session_id") or "(unrecorded)")
    return HookResult(
        kind="inject",
        payload=_issue(minutes, reason, paths, session_id),
        module_name="grant",
    )


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
