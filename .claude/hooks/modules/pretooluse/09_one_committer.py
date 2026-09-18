"""§one-committer — git add/commit/push is the orchestrator's word, enforced at the Bash seam.

Seat law: one-committer. Builders never `git add`, `git commit`, or `git push`; the
orchestrator stages the receipt's fence and commits locally, then pushes once per milestone.
This module blocks a Bash command that opens with such a git write unless ONE of two grants
holds, both read from the command AS WRITTEN (a variable exported by an earlier Bash call is
invisible to this guard — each tool call is one event with no shell state):

  1. `LEDGER_ORCHESTRATOR=1` as an inline env prefix on the git command, or
  2. the console-handover carve-out: every affected path lies inside splice-design's own fence
     (`webui/`, `.dev/web-console/`, `.dev/campaigns/web-console.toml`, root `package-lock.json`).

Rule 2 is a PATH rule, not an identity rule: the hook cannot see a reliable seat, so it admits
splice-design's WORK by the fence that only splice-design holds. One affected path outside the
set refuses the whole write BY NAME. Fail-closed: an empty or unreadable affected-path set is
refused — a bare `git add`, a `git commit` with nothing staged, and any `git push` (no local
path set) are all refused unless the orchestrator grant is present.

The git-detection regex is the upstream patch's own (grailseeker-scout 0b9a66d, vendored
2026-09-18): `git` at the start of a command or after a shell separator (`; & | ( then do`),
with an optional `-C <repo>`. Run on the unquoted skeleton (tool_input.D16) so prose inside a
`-m "..."` or a script literal never trips it.
"""
from __future__ import annotations

import pathlib
import re
import shlex
import subprocess
import sys
from typing import Optional

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent.parent))
from orchestrator.result import HookResult  # noqa: E402  # pyright: ignore[reportMissingImports]
from lib.tool_input import command_of, is_bash, unquoted_skeleton  # noqa: E402  # pyright: ignore[reportMissingImports]

MODULE_NAME = "09_one_committer"
ORCH_ENV = "LEDGER_ORCHESTRATOR"

# A git add/commit/push that opens a command or follows a shell separator, with an optional
# `-C <repo>`. `\b` after the verb stops `commit-msg`/`pushd`-style false positives.
_GIT_WRITE_RE = re.compile(
    r"(?:^|[;&|(]\s*|\bthen\s+|\bdo\s+)git\s+(?:-C\s+\S+\s+)?(?:add|commit|push)\b"
)

# The ONLY inline sanction: an env-assignment prefix on THIS command, optionally after other
# VAR=val assignments, immediately preceding the `git` token.
_INLINE_PREFIX_RE = re.compile(
    r"^\s*(?:[A-Za-z_][A-Za-z0-9_]*=\S+\s+)*LEDGER_ORCHESTRATOR=1\s+"
)

# Console handover (operator ruling 2026-09-18): splice-design lands its own rows on this
# branch, limited to exactly this path set. A write whose affected paths all live inside it is
# admitted; one outside it refuses the write by name.
_HANDOVER_EXACT = frozenset({"package-lock.json", ".dev/campaigns/web-console.toml"})
_HANDOVER_DIRS = ("webui/", ".dev/web-console/")

_BROAD_ADD_FLAGS = frozenset({".", "-A", "--all", "-u", "--update"})


def applies(data: dict) -> bool:
    return is_bash(data)


def _granted_inline(command: str) -> bool:
    """True iff the command AS WRITTEN opens with `[... VAR=val] LEDGER_ORCHESTRATOR=1 git`.

    The prefix must immediately precede the `git` token: `LEDGER_ORCHESTRATOR=1 && git add`
    (a var set as its own command before a separator) and `git add x LEDGER_ORCHESTRATOR=1`
    (a var passed as an ARGUMENT) both fail this, deliberately — the grant is a prefix, not a
    presence anywhere in the command."""
    stripped = command.lstrip()
    match = _INLINE_PREFIX_RE.match(stripped)
    if match is None:
        return False
    return stripped[match.end():].lstrip().startswith("git")


def _in_handover_set(rel: str) -> bool:
    normalized = rel.replace("\\", "/").lstrip("/")
    if normalized in _HANDOVER_EXACT:
        return True
    return normalized.startswith(_HANDOVER_DIRS)


def _staged_paths(cwd: str) -> Optional[list[str]]:
    try:
        proc = subprocess.run(
            ["git", "-C", cwd or ".", "diff", "--cached", "--name-only", "-z"],
            capture_output=True,
            text=True,
            timeout=5,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    if proc.returncode != 0:
        return None
    return [p for p in proc.stdout.split("\0") if p]


def _git_write_parts(command: str):
    """(verb, path_tokens) for a `git [-C repo] add|commit|push` invocation, or None. The
    caller has already confirmed a git write via the skeleton regex; this extracts the verb and
    its trailing tokens from the RAW command so quoted paths resolve correctly."""
    try:
        tokens = shlex.split(command)
    except ValueError:
        tokens = command.split()
    for index, token in enumerate(tokens):
        if token != "git":
            continue
        j = index + 1
        if j < len(tokens) and tokens[j] == "-C" and j + 1 < len(tokens):
            j += 2
        if j < len(tokens) and tokens[j] in ("add", "commit", "push"):
            return tokens[j], tokens[j + 1:]
        return None
    return None


def _affected_paths(data: dict, command: str) -> tuple[list[str], bool]:
    """(paths, broad). `broad` is True when the affected set is empty, unreadable, or so wide
    (`.`/`-A`/bare add) that the handover carve-out must refuse it fail-closed."""
    parts = _git_write_parts(command)
    if parts is None:
        return [], True
    verb, path_tokens = parts
    if verb == "push":
        return [], True
    if verb == "commit":
        staged = _staged_paths(str(data.get("cwd") or ""))
        if not staged:
            return [], True
        return staged, False
    # add
    if "--" in path_tokens:
        split = path_tokens.index("--")
        flags = path_tokens[:split]
        explicit = path_tokens[split + 1:]
    else:
        flags = [t for t in path_tokens if t.startswith("-")]
        explicit = [t for t in path_tokens if not t.startswith("-")]
    broad = any(t in _BROAD_ADD_FLAGS for t in flags) or any(t == "." for t in explicit)
    return explicit, broad


def run(data: dict) -> Optional[HookResult]:
    command = command_of(data)
    if not command.strip():
        return None
    skeleton = unquoted_skeleton(command)
    if not _GIT_WRITE_RE.search(skeleton):
        return None
    if _granted_inline(command):
        return None
    paths, broad = _affected_paths(data, command)
    offending = [p for p in paths if not _in_handover_set(p)]
    if not broad and paths and not offending:
        return None  # console handover: every affected path is inside splice-design's fence
    if offending:
        reason = (
            "§one-committer — git add/commit/push is the orchestrator's word\n\n"
            f"  command: {command.strip()}\n"
            f"  paths outside the console-handover fence: {', '.join(offending)}\n\n"
        )
    else:
        reason = (
            "§one-committer — git add/commit/push is the orchestrator's word\n\n"
            f"  command: {command.strip()}\n\n"
        )
    reason += (
        "Builders never git add/commit/push; the orchestrator stages the fence and commits\n"
        "locally, and pushes once per milestone. splice-design's own rows (webui/, "
        ".dev/web-console/,\n"
        ".dev/campaigns/web-console.toml, package-lock.json) are admitted path-by-path. For\n"
        "anything else, run with the grant inline ON THIS command — a variable exported by an\n"
        "earlier Bash call does not satisfy this guard:\n\n"
        f"  {ORCH_ENV}=1 {command.strip()}"
    )
    return HookResult(kind="block", payload=reason, module_name=MODULE_NAME)


# R1: a security gate fails closed — a crash in this module blocks the tool call rather than
# letting a git write through silently.
FAIL_CLOSED = True
