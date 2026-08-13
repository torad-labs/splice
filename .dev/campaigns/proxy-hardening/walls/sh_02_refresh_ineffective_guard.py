#!/usr/bin/env python3
"""WALL for SH-02 — a successful-but-ineffective grok refresh must not loop token burns.

GAP (RED at authoring, 2026-08-07): when the token endpoint returns no expires_in,
persistRotation writes expiresAtMs=null and mergedAuthJson CARRIES OVER the stale on-disk
`expires`; the very next credentials() lands below the stale floor and blocks on another refresh —
per request, each one consuming a ROTATING refresh token (the 2026-07-18 credential-death shape).
Nothing logs "I refreshed and the expiry did not move".

GREEN requires ALL of (CLIProxyAPI's refreshIneffectiveBackoff pattern):
  1. persistRotation synthesizes an expiry when expires_in is absent — a just-minted token is not
     older than the one it replaced (no bare `fresh.expiresIn?.let {...}` feeding the merge);
  2. a REFRESH_INEFFECTIVE_BACKOFF_MS guard exists — a Refreshed outcome that still evaluates
     inside the blocking tier suppresses further refreshes and serves the current token;
  3. the ineffective case is LOGGED ("did not advance") and COUNTED (ineffectiveRefresh counter)
     so the operator learns before the provider kills the credential.

EXIT 0 = guarded. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
"""
from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
GROK = ROOT / "gateway/provider-grok/src/main/kotlin/splice/provider/grok/GrokAuthProvider.kt"


def detect(text: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly."""
    if text is None:
        return ["GrokAuthProvider.kt missing — refusing to pass vacuously"]
    if "persistRotation" not in text:
        return ["persistRotation not found (shape changed?) — refusing to pass vacuously"]
    problems: list[str] = []
    if "val expiresAtMs = fresh.expiresIn?.let { clock() + it * MS_PER_S }\n" in text:
        problems.append("persistRotation still writes a null expiry when expires_in is absent — "
                        "the merge carries the stale on-disk value and the blocking tier re-enters "
                        "per request, burning rotating refresh tokens")
    if "REFRESH_INEFFECTIVE_BACKOFF_MS" not in text:
        problems.append("no REFRESH_INEFFECTIVE_BACKOFF_MS guard — a successful-but-ineffective "
                        "refresh loops as fast as turns arrive (CLIProxyAPI carries this exact guard)")
    if "did not advance" not in text:
        problems.append("the ineffective case is not logged — token burn is invisible until the "
                        "provider kills the credential")
    if "ineffectiveRefresh" not in text:
        problems.append("the ineffective case is not counted — the dashboard cannot show it")
    return problems


def _read(p: pathlib.Path) -> str | None:
    return p.read_text(encoding="utf-8") if p.exists() else None


OPEN_FIX = ("persistRotation\nval expiresAtMs = fresh.expiresIn?.let { clock() + it * MS_PER_S }\n")
CLOSED_FIX = ("persistRotation\nval expiresAtMs = fresh.expiresIn?.let { clock() + it * MS_PER_S } "
              "?: synthesizedExpiryMs(clock())\nREFRESH_INEFFECTIVE_BACKOFF_MS\n"
              'log("refresh succeeded but expiry did not advance")\nineffectiveRefreshCount')


def selftest() -> int:
    fails = []
    if not detect(OPEN_FIX):
        fails.append("null-expiry persist with no guard must be RED")
    if detect(CLOSED_FIX):
        fails.append(f"synthesis + backoff + log + counter must be GREEN, got {detect(CLOSED_FIX)}")
    if not detect(CLOSED_FIX.replace("REFRESH_INEFFECTIVE_BACKOFF_MS\n", "")):
        fails.append("synthesis without the backoff guard must be RED")
    if not detect(CLOSED_FIX.replace('log("refresh succeeded but expiry did not advance")\n', "")):
        fails.append("an unlogged ineffective path must be RED")
    if not detect(None):
        fails.append("missing GrokAuthProvider.kt must be RED, never a vacuous pass")
    if not detect("class GrokAuthProvider"):
        fails.append("an unrecognized shape must be RED, never a vacuous pass")
    if fails:
        print("SH-02 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("SH-02 SELFTEST OK — red on null-expiry persist, missing backoff, missing log/counter, "
          "missing file, and shape change; green only with synthesis + bounded ineffective guard")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect(_read(GROK))
    if problems:
        print("SH-02 WALL RED — a successful-but-ineffective refresh still loops:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("SH-02 WALL GREEN: absent expires_in synthesizes, ineffective refreshes back off, logged and counted.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
