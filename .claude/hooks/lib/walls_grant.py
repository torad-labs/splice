"""§walls-grant — the ONE implementation of what a wall grant is and whether it is real.

WHY THIS FILE EXISTS AT ALL (2026-07-27, review round 2)

The grant channel was well engineered on every path it controlled — CLI mode read-only by
design, an installer that asserts its patches, a red-then-green self-test, expiry enforced at
the gate rather than trusted to the writer. It did not control the one thing the gate trusts:
the FILE. The chain, as it stood:

  1. WALL_PATHS did not include .claude/state/, so a plain Write of walls-grant.json passed the
     guard and matched no ast-grep rule.
  2. _walls_grant_active() read that file and trusted `until` with no provenance check.
  3. The next write to a wall path saw an active grant and sailed through. The wall was open.
  4. .gitignore keeps the file out of git, so the forgery left no trace.

An assistant could mint its own grant in one Write. "Operator-only by construction" was true of
the ISSUING path and false of the TRUSTING path, and only the second one matters to the gate.

So a grant is now a SIGNED record: HMAC-SHA256 over the canonical payload, keyed by a secret
that lives OUTSIDE the repo and is created only by dev/walls-grant/install.sh. A hand-written or
hand-extended record fails `verify()` and the wall stays shut. The signature also makes the
audit record meaningful — session_id and reason are inside the signed bytes, so "who opened this
wall, from where, and why" cannot be edited after the fact.

WHAT THIS DOES NOT CLOSE, stated plainly rather than papered over:

  Bash is not intercepted. `echo '...' > .claude/state/walls-grant.json` never reaches
  PreToolUse, and neither does a write to the key file. Signing raises the bar from "one Write"
  to "read a secret outside the repo and forge a MAC", and it makes tampering DETECTABLE, but
  any layer whose trust anchor is a file readable by the same process it is defending is a
  bar, not a seal. Closing it completely needs the secret somewhere the assistant's own process
  cannot read — an agent-inaccessible keyring or an out-of-band issuer. Recorded, not claimed.

WHY A FILE AND NOT THE SYSTEM KEYRING: the keyring needs an unlocked D-Bus session, which the
gate does not have in CI or a headless run — and a gate that cannot verify must fail closed,
which would mean every CI wall write blocking on a missing keyring. The file works everywhere
the gate runs. This is a deliberate trade, not an oversight.

FAIL-CLOSED IS THE ONLY DIRECTION HERE. Every failure mode — missing key, unreadable file,
malformed JSON, bad signature, expired, out of scope — returns "no grant", which keeps the wall
SHUT. There is no path through this module that opens a wall on an error.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import secrets
import time
from pathlib import Path

GRANT_REL = ".claude/state/walls-grant.json"

# Test/CI redirect ONLY, mirroring SPLICE_HOOK_ROOT. Pointing this at a file you control is
# equivalent to setting SPLICE_WALLS_OK: visible, auditable, and caught by the gate re-running
# the same checks on the real tree.
KEY_ENV = "SPLICE_WALLS_GRANT_KEY"


def key_path() -> Path:
    """The signing secret, deliberately OUTSIDE the repo — a key committed next to the lock is
    not a key. XDG state dir, because this is machine-local mutable state, not config."""
    override = os.environ.get(KEY_ENV)
    if override:
        return Path(override)
    base = os.environ.get("XDG_STATE_HOME") or os.path.join(os.path.expanduser("~"), ".local", "state")
    return Path(base) / "splice" / "walls-grant.key"


def create_key() -> Path:
    """Provision the secret. Called by install.sh ONLY — never by the gate and never by the
    grant module. If the gate could mint the key it is defending against, the signature would
    prove nothing: a forger would just write a key and sign with it."""
    path = key_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        path.write_text(secrets.token_hex(32) + "\n", encoding="utf-8")
    path.chmod(0o600)
    return path


def _key() -> bytes | None:
    try:
        return key_path().read_text(encoding="utf-8").strip().encode("utf-8") or None
    except OSError:
        return None


def _canonical(payload: dict) -> bytes:
    """The exact bytes that get signed: every field EXCEPT the signature itself, key-sorted and
    separator-pinned so the module and the gate can never disagree about what was signed."""
    body = {k: v for k, v in payload.items() if k != "sig"}
    return json.dumps(body, sort_keys=True, separators=(",", ":")).encode("utf-8")


def sign(payload: dict) -> dict:
    """Return payload with a `sig`. Raises when the key is missing — issuing MUST be loud, since
    an unsigned grant is one the gate will silently refuse, i.e. a /grant that reports ACTIVE
    while the wall keeps blocking (the exact confusion the parents[4] bug produced in July)."""
    key = _key()
    if key is None:
        raise RuntimeError(
            f"no wall-grant signing key at {key_path()} — run `bash dev/walls-grant/install.sh` once "
            "to provision it. The gate refuses unsigned grants by design."
        )
    signed = {k: v for k, v in payload.items() if k != "sig"}
    signed["sig"] = hmac.new(key, _canonical(signed), hashlib.sha256).hexdigest()
    return signed


def verify(payload: dict) -> bool:
    """Constant-time signature check. False on anything unexpected — never raises."""
    key = _key()
    if key is None or not isinstance(payload, dict):
        return False
    sig = payload.get("sig")
    if not isinstance(sig, str):
        return False
    expected = hmac.new(key, _canonical(payload), hashlib.sha256).hexdigest()
    return hmac.compare_digest(sig, expected)


def load(root: Path) -> dict | None:
    try:
        raw = json.loads((Path(root) / GRANT_REL).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return raw if isinstance(raw, dict) else None


def active(root: Path, now: float | None = None) -> dict | None:
    """The single source of truth for 'is a grant live right now'.

    Signature FIRST, then expiry: an unsigned record must not even be read for its `until`, or a
    forger learns the shape of what would have worked. Both the write-time gate and /grant's
    status report call this one function — a second copy of an expiry rule is how a grant ends up
    reported active while the gate still blocks.
    """
    grant = load(root)
    if not grant or not verify(grant):
        return None
    try:
        until = float(grant.get("until", 0))
    except (TypeError, ValueError):
        return None
    return grant if until > (time.time() if now is None else now) else None


def covers(grant: dict, rel: str, wall_paths: tuple[str, ...]) -> bool:
    """Does this grant authorize writing [rel]?

    A grant with no `paths` opens every wall path — the pre-scope behaviour, kept so an existing
    habit does not silently become a refusal. A scoped grant opens only what it names, which is
    the common case: walls work almost always touches exactly ONE of .rules/, .claude/hooks/,
    .claude/settings.json, sgconfig.yml, and a 90-minute grant for a one-rule fix should not
    leave the other three writable for the rest of the window.

    `paths` is inside the signed bytes, so a scope cannot be widened after issue without
    invalidating the signature.
    """
    scope = grant.get("paths")
    if not scope:
        return True
    if not isinstance(scope, list):
        return False  # malformed scope is not a wildcard
    allowed = [p for p in scope if isinstance(p, str) and p in wall_paths]
    return any(rel == p or rel.startswith(p.rstrip("/") + "/") for p in allowed)
