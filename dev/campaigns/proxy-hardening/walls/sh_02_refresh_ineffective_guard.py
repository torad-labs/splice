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
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
GROK = ROOT / "gateway/provider-grok/src/main/kotlin/splice/provider/grok/GrokAuthProvider.kt"


def detect(text: str | None, *, raw: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly.

    `text` is the CODE view (comments and imports stripped) and carries every REQUIRED token; `raw`
    is the untouched file text and carries the null-expiry-persist BAN. The two directions want
    opposite treatment — see code_only.
    """
    if text is None:
        return ["GrokAuthProvider.kt missing — refusing to pass vacuously"]
    if "persistRotation" not in text:
        return ["persistRotation not found (shape changed?) — refusing to pass vacuously"]
    problems: list[str] = []
    if "val expiresAtMs = fresh.expiresIn?.let { clock() + it * MS_PER_S }\n" in (raw or ""):
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


_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
_LINE_COMMENT = re.compile(r"//.*?$", re.M)
_IMPORT_LINE = re.compile(r"^import .*$", re.M)


def code_only(text: str | None) -> str | None:
    """A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy a
    REQUIRED token after the real call site is deleted. Same stripper cx_02/cx_09/cx_18 carry.
    Proven against this wall's own source: with the ineffective-refresh log call deleted and its
    literal text left in a TODO, the raw-matching wall printed WALL GREEN while token burn had gone
    silent again. GrokAuthProvider.kt is the densest commentary in the tree — the whole SH-02(a)/(b)
    rationale sits directly above the code it describes — so this file was the most exposed of all.

    Applied to _read (the required tokens) and deliberately NOT to _read_raw, which feeds the
    null-expiry-persist BAN. The two directions want opposite treatment: stripping makes a required
    token harder to satisfy, but would make a banned string easier to hide. Both stay strict."""
    if text is None:
        return None
    stripped = _BLOCK_COMMENT.sub("", text)
    stripped = _LINE_COMMENT.sub("", stripped)
    return _IMPORT_LINE.sub("", stripped)


def _read_raw(p: pathlib.Path) -> str | None:
    """Untouched file text — the view the BAN is matched against (see code_only)."""
    return p.read_text(encoding="utf-8") if p.exists() else None


def _read(p: pathlib.Path) -> str | None:
    return code_only(_read_raw(p))


OPEN_FIX = ("persistRotation\nval expiresAtMs = fresh.expiresIn?.let { clock() + it * MS_PER_S }\n")
CLOSED_FIX = ("persistRotation\nval expiresAtMs = fresh.expiresIn?.let { clock() + it * MS_PER_S } "
              "?: synthesizedExpiryMs(clock())\nREFRESH_INEFFECTIVE_BACKOFF_MS\n"
              'log("refresh succeeded but expiry did not advance")\nineffectiveRefreshCount')


LOG_COMMENTED = CLOSED_FIX.replace(
    'log("refresh succeeded but expiry did not advance")',
    '// TODO(SH-02): restore log("refresh succeeded but expiry did not advance")',
)
HIDDEN_NULL_PERSIST = (CLOSED_FIX + "\n// dead code, kept for the diff: val expiresAtMs = "
                       "fresh.expiresIn?.let { clock() + it * MS_PER_S }\n")


def selftest() -> int:
    fails = []
    if not detect(OPEN_FIX, raw=OPEN_FIX):
        fails.append("null-expiry persist with no guard must be RED")
    if detect(CLOSED_FIX, raw=CLOSED_FIX):
        fails.append(f"synthesis + backoff + log + counter must be GREEN, got "
                     f"{detect(CLOSED_FIX, raw=CLOSED_FIX)}")
    if not detect(CLOSED_FIX.replace("REFRESH_INEFFECTIVE_BACKOFF_MS\n", ""), raw=CLOSED_FIX):
        fails.append("synthesis without the backoff guard must be RED")
    if not detect(CLOSED_FIX.replace('log("refresh succeeded but expiry did not advance")\n', ""),
                  raw=CLOSED_FIX):
        fails.append("an unlogged ineffective path must be RED")
    if not detect(None, raw=None):
        fails.append("missing GrokAuthProvider.kt must be RED, never a vacuous pass")
    if not detect("class GrokAuthProvider", raw="class GrokAuthProvider"):
        fails.append("an unrecognized shape must be RED, never a vacuous pass")
    # HD-26 comment-satisfiability controls. Both directions, so a later blind sweep that strips the
    # ban too (or stops stripping the required tokens) breaks the selftest instead of the invariant.
    if detect(LOG_COMMENTED, raw=LOG_COMMENTED):
        fails.append("the raw shape must read GREEN — otherwise this fixture is not the bug and "
                     "the control below proves nothing")
    if not detect(code_only(LOG_COMMENTED), raw=LOG_COMMENTED):
        fails.append("an ineffective-refresh log that survives only as comment text must be RED — "
                     "required tokens are matched against code, never raw file text")
    if not detect(code_only(HIDDEN_NULL_PERSIST), raw=HIDDEN_NULL_PERSIST):
        fails.append("a null-expiry persist commented out of the code view must still be RED — the "
                     "ban reads RAW so a comment cannot hide it")
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
    problems = detect(_read(GROK), raw=_read_raw(GROK))
    if problems:
        print("SH-02 WALL RED — a successful-but-ineffective refresh still loops:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("SH-02 WALL GREEN: absent expires_in synthesizes, ineffective refreshes back off, logged and counted.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
