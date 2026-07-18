#!/usr/bin/env python3
"""Stop-hook gradle arm (#924 0c, LIGHT). A turn must not END on a red Kotlin compile.

FAIL-OPEN by construction: this blocks ONLY on a definitive main-source compile failure. No .kt
change in the working tree, a self-timeout (cold daemon), a missing JDK 21, or any gradle-infra
hiccup all PASS silently — a Stop hook that spuriously wedges a turn is worse than none. The full
./gradlew check (detekt + the 1000-stream load test) is impractical per-Stop; that lives in
`bash checks/gate.sh` and the gateway-gradle CI job. This arm catches only the one thing you must
never end a turn on: code that does not compile.
"""
import json
import os
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
GRADLE_DIR = ROOT / "gateway"  # the gradlew wrapper lives here, not at repo root
JDK21_DEFAULT = "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
GRADLE_TIMEOUT_S = 25  # self-limit under the 30s hook budget: cold compile fail-opens, not hard-killed


def passthrough():
    sys.exit(0)


def kt_changed() -> bool:
    try:
        tracked = subprocess.run(
            ["git", "-C", str(ROOT), "diff", "--name-only", "HEAD", "--", "*.kt"],
            capture_output=True, text=True, timeout=5,
        ).stdout.strip()
        untracked = subprocess.run(
            ["git", "-C", str(ROOT), "ls-files", "--others", "--exclude-standard", "--", "*.kt"],
            capture_output=True, text=True, timeout=5,
        ).stdout.strip()
        return bool(tracked or untracked)
    except Exception:
        return False


def main():
    try:
        sys.stdin.read()  # drain the Stop-hook payload (unused)
    except Exception:
        pass

    if not kt_changed():
        passthrough()

    java_home = os.environ.get("JAVA_HOME") or JDK21_DEFAULT
    if not pathlib.Path(java_home, "bin", "java").exists():
        passthrough()  # no JDK 21 -> fail-open, never block
    if not (GRADLE_DIR / "gradlew").exists():
        passthrough()  # no wrapper -> fail-open

    env = {**os.environ, "JAVA_HOME": java_home}
    try:
        proc = subprocess.run(
            ["./gradlew", "-q", "compileKotlin"],
            cwd=str(GRADLE_DIR), env=env, capture_output=True, text=True, timeout=GRADLE_TIMEOUT_S,
        )
    except Exception:
        passthrough()  # self-timeout / launch failure -> fail-open (the gate + CI still catch it)

    if proc.returncode == 0:
        passthrough()

    out = (proc.stdout or "") + (proc.stderr or "")
    errs = [ln for ln in out.splitlines() if ln.startswith("e: ")]
    if not errs and "BUILD FAILED" not in out:
        passthrough()  # non-definitive (infra) -> fail-open

    reason = (
        "Kotlin main source does not compile — a turn must not end on a red tree (#924 0c, light "
        "Stop arm). Fix these, then `bash checks/gate.sh` for the full gate:\n" + "\n".join(errs[:6])
    )
    print(json.dumps({"decision": "block", "reason": reason}))
    sys.exit(0)


if __name__ == "__main__":
    main()
