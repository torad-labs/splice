#!/usr/bin/env python3
"""Redact and curate a Muse live capture into checks/e2e/receipts/muse-YYYYMMDD/.

The live head-through-splice turn needs Marcos's login and is recorded later.
This script only curates an already-redacted-or-raw capture: secrets never survive
in the output. Self-test: muse-curate.py --selftest
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any

SECRET_KEYS = {
    "access_token",
    "api_key",
    "authorization",
    "cookie",
    "set-cookie",
    "user_email",
    "user_id",
    "user_full_name",
    "email",
    "refresh_token",
    "x-api-key",
}
SECRET_KEY_RE = re.compile(r"(token|secret|password|authorization|api[_-]?key|cookie|email)", re.I)
REDACTED = "REDACTED"


def redact(value: Any, key: str | None = None) -> Any:
    if key is not None and (key.lower() in SECRET_KEYS or SECRET_KEY_RE.search(key)):
        if isinstance(value, str) and not value:
            return value
        return REDACTED
    if isinstance(value, dict):
        return {k: redact(v, k) for k, v in value.items()}
    if isinstance(value, list):
        return [redact(item) for item in value]
    return value


def load_payload(path: Path) -> Any:
    text = path.read_text(encoding="utf-8")
    if path.suffix == ".jsonl":
        return [json.loads(line) for line in text.splitlines() if line.strip()]
    return json.loads(text)


def curate(src: Path, dest_dir: Path) -> Path:
    dest_dir.mkdir(parents=True, exist_ok=True)
    out = dest_dir / "mint-and-usage.json"
    out.write_text(json.dumps(redact(load_payload(src)), indent=2) + "\n", encoding="utf-8")
    return out


def selftest() -> None:
    sample = {
        "api_key": "real-secret-key",
        "access_token": "real-account-token",
        "user_email": "operator@example.test",
        "subs_usage": {"window": {"used_percent": 1, "window_duration_mins": 300}},
        "headers": {"Authorization": "Bearer real-secret-key"},
    }
    cleaned = redact(sample)
    assert cleaned["api_key"] == REDACTED
    assert cleaned["access_token"] == REDACTED
    assert cleaned["user_email"] == REDACTED
    assert cleaned["headers"]["Authorization"] == REDACTED
    assert cleaned["subs_usage"]["window"]["used_percent"] == 1
    print("muse-curate selftest: PASS")


def main(argv: list[str]) -> int:
    if argv == ["--selftest"]:
        selftest()
        return 0
    if len(argv) != 2:
        print("usage: muse-curate.py <capture.json|jsonl> <dest-dir>", file=sys.stderr)
        print("       muse-curate.py --selftest", file=sys.stderr)
        return 2
    src = Path(argv[0])
    dest = Path(argv[1])
    written = curate(src, dest)
    print(f"wrote {written}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
