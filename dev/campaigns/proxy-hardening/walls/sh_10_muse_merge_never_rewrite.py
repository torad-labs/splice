#!/usr/bin/env python3
# NEW: V4-14 — extend SH-10 to Muse while preserving the original Kimi wall and its controls.
"""SH-10 covers both providers: every credential persist must carry the shared merge result.

The Kimi detector already traces local values and private forwards to the atomic 0600 write.
Reuse that detector rather than fork its parser; retain Kimi's live scan and selftest unchanged.
MuseMintPersistence.kt owns Muse's persist write. The app's HTTP and assembly files are not write targets.
"""
from __future__ import annotations

import pathlib
import sys

import sh_10_kimi_merge_never_rewrite as kimi

ROOT = pathlib.Path(__file__).resolve().parents[4]
CORE = ROOT / "gateway/core/src/main/kotlin/splice/core/auth/CredentialJson.kt"
MUSE = ROOT / "gateway/provider-muse/src/main/kotlin/splice/provider/muse/MuseMintPersistence.kt"


def detect(core: str | None, muse: str | None) -> list[str]:
    """Run the same dataflow check on Muse; comments are not credential writes or merges."""
    if muse is None:
        return ["MuseMintPersistence.kt missing — refusing to pass vacuously"]
    return [
        problem.replace("Kimi", "Muse").replace("kimi", "muse")
        for problem in kimi.detect(kimi.code_only(core), kimi.code_only(muse))
    ]


def selftest() -> int:
    kimi_status = kimi.selftest()
    merged = """
        class MuseMintPersistence {
            private fun persistCredential(replacements: JsonObject) {
                val onDisk = readCredentialJson()
                val merged = CredentialJson.mergedCredentialJson(onDisk, replacements)
                SecureFile.writeAtomic0600(authPath, merged.toString())
            }
        }
    """
    fresh = merged.replace("authPath, merged.toString()", "authPath, replacements.toString()")
    unsafe = merged.replace("SecureFile.writeAtomic0600", "Files.writeString")
    comment_only = merged.replace(
        "val merged = CredentialJson.mergedCredentialJson(onDisk, replacements)",
        "// val merged = CredentialJson.mergedCredentialJson(onDisk, replacements)\n"
        "val merged = replacements",
    )
    renamed = merged.replace("val merged =", "val credentialFile =").replace(
        "merged.toString()", "credentialFile.toString()",
    )
    filtered = merged.replace(
        "SecureFile.writeAtomic0600(authPath, merged.toString())",
        "val filtered = merged.filterKeys { it == \"api_key\" }\n"
        "                SecureFile.writeAtomic0600(authPath, filtered.toString())",
    )
    cases = (
        ("merged Muse credential reaches the atomic write", kimi.CORE_OK, merged, False),
        ("Muse local rename keeps the merged value", kimi.CORE_OK, renamed, False),
        ("dead Muse merge with fresh replacements persisted", kimi.CORE_OK, fresh, True),
        ("filtered merged Muse object is not the persist value", kimi.CORE_OK, filtered, True),
        ("Muse bypasses the atomic 0600 write", kimi.CORE_OK, unsafe, True),
        ("Muse merge exists only in a comment", kimi.CORE_OK, comment_only, True),
        ("Muse has no shared merge primitive", None, merged, True),
        ("Muse persist target is missing", kimi.CORE_OK, None, True),
        ("Muse persist target is empty", kimi.CORE_OK, "", True),
    )
    failures = [label for label, core, source, expected in cases if bool(detect(core, source)) != expected]
    missing = detect(kimi.CORE_OK, None)
    if missing != ["MuseMintPersistence.kt missing — refusing to pass vacuously"]:
        failures.append("missing-target names MuseMintPersistence.kt")
    if failures:
        print("SH-10 MUSE SELFTEST FAIL:")
        for failure in failures:
            print("  " + failure)
        return 1
    print("SH-10 MUSE SELFTEST OK — merged writes pass; fresh, filtered, unsafe, comment-only and missing targets fail")
    return kimi_status


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    kimi_status = kimi.main()
    core = CORE.read_text(encoding="utf-8") if CORE.exists() else None
    muse = MUSE.read_text(encoding="utf-8") if MUSE.exists() else None
    problems = detect(core, muse)
    if problems:
        print("SH-10 MUSE WALL RED — the Muse credential persist must merge and write atomically:")
        for problem in problems:
            print("  " + problem)
        return 1
    print("SH-10 MUSE WALL GREEN: Muse persists the shared merge result through SecureFile.writeAtomic0600")
    return kimi_status


if __name__ == "__main__":
    sys.exit(main())
