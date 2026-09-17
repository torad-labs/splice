#!/usr/bin/env python3
# NEW: V4-14 — extend SH-10 to Muse while preserving the original Kimi wall and its controls.
"""SH-10 covers both providers: every credential persist must carry the shared merge result.

The Kimi detector already traces local values and private forwards to the atomic 0600 write.
Reuse that detector rather than fork its parser; retain Kimi's live scan and selftest unchanged.
MuseMintPersistence.kt owns Muse's persist write. The app's HTTP and assembly files are not write targets.

Denominator (2026-09-15): every *.kt under gateway/<module>/src/main, glob gateway/*/src/main.
That is every Gradle production source that can persist a file. src/test and src/testFixtures
are out of scope because tests fixture the pre-V4-23 LoginMuse shape as a RED oracle and are
not production writers.

A hit is a Muse second writer when all of: the relative path contains muse (LoginMuse,
provider-muse, a colliding MuseMintPersistence.kt elsewhere), the file calls
SecureFile.writeAtomic0600, and the path is not the allowed writer. Matching the basename
alone is not an exemption. Every other live atomic writer needs a dated disposition;
absence is not one.
"""
from __future__ import annotations

import pathlib
import sys

import sh_10_kimi_merge_never_rewrite as kimi

ROOT = pathlib.Path(__file__).resolve().parents[4]
CORE = ROOT / "gateway/core/src/main/kotlin/splice/core/auth/CredentialJson.kt"
MUSE = ROOT / "gateway/provider-muse/src/main/kotlin/splice/provider/muse/MuseMintPersistence.kt"
ALLOWED_WRITER = "gateway/provider-muse/src/main/kotlin/splice/provider/muse/MuseMintPersistence.kt"
ATOMIC_WRITE = "SecureFile.writeAtomic0600("

# 2026-09-15. Pre-existing non-muse atomic writers. Not Muse credential persists.
NON_MUSE_ATOMIC_WRITERS = {
    "gateway/gateway/src/main/kotlin/splice/gateway/usage/EconomicsStore.kt":
        "2026-09-17 V4-75 hourly token-economics rollup persist; never a credential",
    "gateway/app/src/main/kotlin/splice/app/LoginIo.kt":
        "2026-09-15 shared login credential write used by every vendor flow",
    "gateway/app/src/main/kotlin/splice/app/auth/OAuthAccountWrites.kt":
        "2026-09-15 labeled OAuth pool writes for every kind",
    "gateway/core/src/main/kotlin/splice/core/config/ConfigService.kt":
        "2026-09-15 daemon config.json persist",
    "gateway/core/src/main/kotlin/splice/core/config/KeyStore.kt":
        "2026-09-15 api-key store persist",
    "gateway/core/src/main/kotlin/splice/core/config/MgmtKey.kt":
        "2026-09-15 management key persist",
    "gateway/core/src/main/kotlin/splice/core/launch/ClaudeConfigMaterializer.kt":
        "2026-09-15 Claude Code config and state materializer",
    "gateway/core/src/main/kotlin/splice/core/launch/HeadCommandsDir.kt":
        "2026-09-15 per-head command wrapper persist",
    "gateway/core/src/main/kotlin/splice/core/launch/LoginOutcomeFile.kt":
        "2026-09-15 login outcome file persist",
    "gateway/gateway/src/main/kotlin/splice/gateway/usage/QuotaTracker.kt":
        "2026-09-15 quota snapshot persist",
    "gateway/gateway/src/main/kotlin/splice/gateway/usage/RateLimitFile.kt":
        "2026-09-15 rate-limit file persist",
    "gateway/gateway/src/main/kotlin/splice/gateway/usage/UsageRingFile.kt":
        "2026-09-15 usage ring persist",
    "gateway/provider-codex/src/main/kotlin/splice/provider/codex/CodexAuthProvider.kt":
        "2026-09-15 Codex credential persist",
    "gateway/provider-codex/src/main/kotlin/splice/provider/codex/CodexCodeModeStore.kt":
        "2026-09-15 Codex code-mode state persist",
    "gateway/provider-grok/src/main/kotlin/splice/provider/grok/GrokAuthProvider.kt":
        "2026-09-15 Grok credential persist",
    "gateway/provider-kimi/src/main/kotlin/splice/provider/kimi/KimiAuthProvider.kt":
        "2026-09-15 Kimi credential persist",
    "gateway/provider-kimi/src/main/kotlin/splice/provider/kimi/KimiDeviceIdentity.kt":
        "2026-09-15 Kimi device identity persist",
}

# Pre-V4-23 LoginMuse wrote the minted key itself (denylist merge + atomic 0600). The
# second-writer arm must stay RED against that shape so a revert of the shared writer
# cannot go green.
PRE_V4_23_LOGIN_MUSE = """
        class LoginMuse {
            private fun persistMinted(target: Path, access: String, attempt: MuseMintAttempt.Granted) {
                val replacements = buildJsonObject {
                    attempt.key.fields.forEach { (name, value) ->
                        if (name != "splice_auth_kind" && name != "splice_account_label") put(name, value)
                    }
                    put("api_key", JsonPrimitive(attempt.key.apiKey))
                    put("access_token", JsonPrimitive(access))
                }
                val merged = CredentialJson.mergedCredentialJson(current, replacements)
                SecureFile.writeAtomic0600(target, merged.toString())
            }
        }
    """

# Current LoginMuse delegates persist to MuseMintPersistence; no atomic write of its own.
LOGIN_MUSE_DELEGATED = """
        class LoginMuse {
            private fun persistMinted(target: Path, access: String, attempt: MuseMintAttempt.Granted) {
                mintPersistence.persistGranted(target, access, attempt.key, log)
            }
        }
    """

LOGIN_MUSE_PATH = "gateway/app/src/main/kotlin/splice/app/cli/LoginMuse.kt"
CORE_FAKE_MUSE = "gateway/core/src/main/kotlin/splice/core/MuseMintPersistence.kt"
LOGIN_CODEX_PATH = "gateway/app/src/main/kotlin/splice/app/cli/LoginCodex.kt"
TEST_MUSE_WRITER = "gateway/provider-muse/src/test/kotlin/muse/MuseWriterFixture.kt"
TEST_CORE_WRITER = "gateway/core/src/test/kotlin/NewWriterTest.kt"


def detect(core: str | None, muse: str | None) -> list[str]:
    """Run the same dataflow check on Muse; comments are not credential writes or merges."""
    if muse is None:
        return ["MuseMintPersistence.kt missing — refusing to pass vacuously"]
    return [
        problem.replace("Kimi", "Muse").replace("kimi", "muse")
        for problem in kimi.detect(kimi.code_only(core), kimi.code_only(muse))
    ]


def _rel(name: str) -> str:
    return name.replace("\\", "/")


def in_live_scope(rel: str) -> bool:
    """Production Kotlin only. Tests fixture the RED oracle and are not writers."""
    n = _rel(rel)
    return "/src/main/" in n and "/src/test/" not in n


def is_muse_owning(rel: str) -> bool:
    return "muse" in _rel(rel).lower()


def extra_writers(files: dict[str, str]) -> list[str]:
    """Atomic 0600 writes in Muse-owning production files outside the allowed relative path."""
    hits: list[str] = []
    for name, source in files.items():
        rel = _rel(name)
        if not in_live_scope(rel) or rel == ALLOWED_WRITER:
            continue
        if not is_muse_owning(rel):
            continue
        code = kimi.code_only(source) or ""
        if ATOMIC_WRITE in code:
            hits.append(rel)
    return hits


def undisposed_writers(files: dict[str, str]) -> list[str]:
    """Atomic 0600 writes that are not the Muse persist and have no dated disposition."""
    hits: list[str] = []
    for name, source in files.items():
        rel = _rel(name)
        if not in_live_scope(rel) or rel == ALLOWED_WRITER or is_muse_owning(rel):
            continue
        code = kimi.code_only(source) or ""
        if ATOMIC_WRITE in code and rel not in NON_MUSE_ATOMIC_WRITERS:
            hits.append(rel)
    return hits


def stale_dispositions(files: dict[str, str]) -> list[str]:
    """A disposition whose file is gone or no longer calls the atomic writer."""
    hits: list[str] = []
    for rel in NON_MUSE_ATOMIC_WRITERS:
        source = files.get(rel)
        if source is None:
            hits.append(rel + " missing")
            continue
        if ATOMIC_WRITE not in (kimi.code_only(source) or ""):
            hits.append(rel + " no longer writes")
    return hits


def live_main_sources(root: pathlib.Path) -> dict[str, str]:
    files: dict[str, str] = {}
    for main in root.glob("gateway/*/src/main"):
        for path in main.rglob("*.kt"):
            rel = _rel(str(path.relative_to(root)))
            if in_live_scope(rel):
                files[rel] = path.read_text(encoding="utf-8")
    return files


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
    if not extra_writers({LOGIN_MUSE_PATH: PRE_V4_23_LOGIN_MUSE}):
        failures.append("W3 muse-gate: pre-V4-23 LoginMuse writeAtomic0600 must be RED as a second writer")
    if extra_writers({LOGIN_MUSE_PATH: LOGIN_MUSE_DELEGATED}):
        failures.append("delegating LoginMuse must be GREEN")
    if extra_writers({ALLOWED_WRITER: merged}):
        failures.append("W1 path not basename: the allowed relative path itself is GREEN")
    commented = PRE_V4_23_LOGIN_MUSE.replace(
        "SecureFile.writeAtomic0600(target, merged.toString())",
        "// SecureFile.writeAtomic0600(target, merged.toString())",
    )
    if extra_writers({LOGIN_MUSE_PATH: commented}):
        failures.append("a commented-out atomic write is not a second writer")
    if not extra_writers({CORE_FAKE_MUSE: PRE_V4_23_LOGIN_MUSE}):
        failures.append("W1 path not basename: core MuseMintPersistence.kt must be RED")
    if extra_writers({LOGIN_CODEX_PATH: PRE_V4_23_LOGIN_MUSE}):
        failures.append("W3 muse-gate: LoginCodex atomic write is not a Muse second writer")
    if not undisposed_writers(
        {"gateway/core/src/main/kotlin/splice/core/NewWriter.kt": PRE_V4_23_LOGIN_MUSE},
    ):
        failures.append("W2 widen roots: undisposed atomic writer outside provider-muse and app/cli must be RED")
    if extra_writers({TEST_MUSE_WRITER: PRE_V4_23_LOGIN_MUSE}):
        failures.append("W4 src/test: a muse test fixture calling writeAtomic0600 is out of live scope")
    if undisposed_writers({TEST_CORE_WRITER: PRE_V4_23_LOGIN_MUSE}):
        failures.append("W4 src/test: a non-muse test atomic write is out of live scope")
    if failures:
        print("SH-10 MUSE SELFTEST FAIL:")
        for failure in failures:
            print("  " + failure)
        return 1
    print(
        "SH-10 MUSE SELFTEST OK — merged writes pass; fresh, filtered, unsafe, comment-only "
        "and missing targets fail; W1 path not basename reds a core MuseMintPersistence.kt; "
        "W2 widen roots reds an undisposed core writer; W3 muse-gate reds LoginMuse and "
        "greens LoginCodex; W4 src/test fixtures are out of live scope"
    )
    return kimi_status


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    kimi_status = kimi.main()
    core = CORE.read_text(encoding="utf-8") if CORE.exists() else None
    muse = MUSE.read_text(encoding="utf-8") if MUSE.exists() else None
    problems = detect(core, muse)
    live = live_main_sources(ROOT)
    seconds = extra_writers(live)
    if seconds:
        problems.append(
            "second Muse credential writer: " + ", ".join(seconds) +
            " — only " + ALLOWED_WRITER + " may persist a Muse credential with SecureFile.writeAtomic0600"
        )
    undisposed = undisposed_writers(live)
    if undisposed:
        problems.append(
            "undisposed atomic writer: " + ", ".join(undisposed) +
            " — add a dated disposition; absence is not one"
        )
    stale = stale_dispositions(live)
    if stale:
        problems.append(
            "stale atomic-writer disposition: " + ", ".join(stale)
        )
    if problems:
        print("SH-10 MUSE WALL RED — the Muse credential persist must merge and write atomically:")
        for problem in problems:
            print("  " + problem)
        return 1
    print(
        "SH-10 MUSE WALL GREEN: Muse persists the shared merge result through "
        "SecureFile.writeAtomic0600; no second Muse writer under gateway/*/src/main"
    )
    return kimi_status


if __name__ == "__main__":
    sys.exit(main())
