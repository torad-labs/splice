#!/usr/bin/env bun
// NEW: V4-14 — extend SH-10 to Muse while preserving the original Kimi wall and its controls.
/** SH-10 covers both providers: every credential persist must carry the shared merge result.
 *
 *  The Kimi detector already traces local values and private forwards to the atomic 0600 write.
 *  Reuse that detector rather than fork its parser; retain Kimi's live scan and selftest unchanged.
 *  MuseMintPersistence.kt owns Muse's persist write. The app's HTTP and assembly files are not write
 *  targets.
 *
 *  Denominator (2026-09-15): every .kt under each gateway module's src/main, one directory level
 *  below gateway. (Written without the literal glob, because the glob's own characters close this
 *  block comment — the Python original could spell it inside a triple-quoted docstring.)
 *  That is every Gradle production source that can persist a file. src/test and src/testFixtures
 *  are out of scope because tests fixture the pre-V4-23 LoginMuse shape as a RED oracle and are
 *  not production writers.
 *
 *  A hit is a Muse second writer when all of: the relative path contains muse (LoginMuse,
 *  provider-muse, a colliding MuseMintPersistence.kt elsewhere), the file calls
 *  SecureFile.writeAtomic0600, and the path is not the allowed writer. Matching the basename
 *  alone is not an exemption. Every other live atomic writer needs a dated disposition;
 *  absence is not one.
 *
 *  V4-154: converted to TypeScript (bun), and THIS WALL IS WHY THE PAIR CONVERTS TOGETHER — it
 *  imports sh_10_kimi_merge_never_rewrite, which must survive as a module for this one to run at
 *  all. The kimi wall therefore exports selftest and main, and this file imports it by relative path
 *  exactly as the Python imported the sibling by module name.
 */
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import * as kimi from "./sh_10_kimi_merge_never_rewrite.ts";

const ROOT = resolve(import.meta.dir, "../../../..");
const CORE = resolve(ROOT, "core/src/main/kotlin/splice/core/auth/CredentialJson.kt");
const MUSE = resolve(ROOT, "gateway/provider-muse/src/main/kotlin/splice/provider/muse/MuseMintPersistence.kt");
const ALLOWED_WRITER = "gateway/provider-muse/src/main/kotlin/splice/provider/muse/MuseMintPersistence.kt";
const ATOMIC_WRITE = "SecureFile.writeAtomic0600(";

// 2026-09-15. Pre-existing non-muse atomic writers. Not Muse credential persists.
export const NON_MUSE_ATOMIC_WRITERS: Record<string, string> = {
  "daemon/head/src/main/kotlin/splice/head/usage/EconomicsStore.kt":
    "2026-09-17 V4-75 hourly token-economics rollup persist; never a credential",
  "core/src/main/kotlin/splice/core/teams/TeamStore.kt":
    "2026-09-18 V4-131 teams.json persist and its .bak sibling; team rows and slot text, never a credential",
  "gateway/app/src/main/kotlin/splice/app/LoginIo.kt":
    "2026-09-15 shared login credential write used by every vendor flow",
  "gateway/app/src/main/kotlin/splice/app/auth/OAuthAccountWrites.kt":
    "2026-09-15 labeled OAuth pool writes for every kind",
  "core/src/main/kotlin/splice/core/config/ConfigService.kt":
    "2026-09-15 daemon config.json persist",
  "core/src/main/kotlin/splice/core/config/KeyStore.kt":
    "2026-09-15 api-key store persist",
  "core/src/main/kotlin/splice/core/config/MgmtKey.kt":
    "2026-09-15 management key persist",
  "client/src/main/kotlin/splice/client/ClaudeConfigMaterializer.kt":
    "2026-09-15 Claude Code config and state materializer",
  "client/src/main/kotlin/splice/client/ClaudeLogins.kt":
    "2026-09-20 V4-129 splice-owned Claude logins. THIS ONE DOES WRITE A CREDENTIAL and the " +
      "disposition says so rather than claiming otherwise: it copies Claude Code's own " +
      ".credentials.json WHOLE (readString then writeAtomic0600, lines 67 and 93) between the " +
      "store and a head's config dir, and writes a one-line label file for the selection. A " +
      "whole-file copy is not the hazard this wall guards — that is reading a credential OBJECT, " +
      "mutating part of it and writing the partial back, which is how a rotation gets dropped. " +
      "Nothing here parses or merges the object, so no field can be lost.",
  "client/src/main/kotlin/splice/client/wrap/WrappedHead.kt":
    "2026-09-20 V4-129 wrap-state persist (real binary path, shadowed symlink target, shim path, " +
      "backup paths, wrapped-at millis); never a credential",
  "core/src/main/kotlin/splice/core/alert/AlertStore.kt":
    "2026-09-20 V4-133 alert settings persist and its .bak sibling; thresholds and a webhook URL, " +
      "never a credential",
  "core/src/main/kotlin/splice/core/budget/BudgetStore.kt":
    "2026-09-20 V4-133 budgets.json persist and its .bak sibling; spend ceilings and actions, " +
      "never a credential",
  "client/src/main/kotlin/splice/client/wrap/HeadCommandsDir.kt":
    "2026-09-15 per-head command wrapper persist",
  "client/src/main/kotlin/splice/client/login/LoginOutcomeFile.kt":
    "2026-09-15 login outcome file persist",
  "daemon/head/src/main/kotlin/splice/head/usage/QuotaTracker.kt":
    "2026-09-15 quota snapshot persist",
  "daemon/head/src/main/kotlin/splice/head/usage/RateLimitFile.kt":
    "2026-09-15 rate-limit file persist",
  "daemon/head/src/main/kotlin/splice/head/usage/UsageRingFile.kt":
    "2026-09-15 usage ring persist",
  "providers/codex/src/main/kotlin/splice/provider/codex/CodexAuthProvider.kt":
    "2026-09-15 Codex credential persist",
  "providers/codex/src/main/kotlin/splice/provider/codex/CodexCodeModeStore.kt":
    "2026-09-15 Codex code-mode state persist",
  "gateway/provider-grok/src/main/kotlin/splice/provider/grok/GrokAuthProvider.kt":
    "2026-09-15 Grok credential persist",
  "gateway/provider-kimi/src/main/kotlin/splice/provider/kimi/KimiAuthProvider.kt":
    "2026-09-15 Kimi credential persist",
  "gateway/provider-kimi/src/main/kotlin/splice/provider/kimi/KimiDeviceIdentity.kt":
    "2026-09-15 Kimi device identity persist",
};

// Pre-V4-23 LoginMuse wrote the minted key itself (denylist merge + atomic 0600). The
// second-writer arm must stay RED against that shape so a revert of the shared writer
// cannot go green.
export const PRE_V4_23_LOGIN_MUSE = `
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
    `;

// Current LoginMuse delegates persist to MuseMintPersistence; no atomic write of its own.
export const LOGIN_MUSE_DELEGATED = `
        class LoginMuse {
            private fun persistMinted(target: Path, access: String, attempt: MuseMintAttempt.Granted) {
                mintPersistence.persistGranted(target, access, attempt.key, log)
            }
        }
    `;

export const LOGIN_MUSE_PATH = "gateway/app/src/main/kotlin/splice/app/cli/LoginMuse.kt";
export const CORE_FAKE_MUSE = "core/src/main/kotlin/splice/core/MuseMintPersistence.kt";
export const LOGIN_CODEX_PATH = "gateway/app/src/main/kotlin/splice/app/cli/LoginCodex.kt";
export const TEST_MUSE_WRITER = "gateway/provider-muse/src/test/kotlin/muse/MuseWriterFixture.kt";
export const TEST_CORE_WRITER = "core/src/test/kotlin/NewWriterTest.kt";

/** Run the same dataflow check on Muse; comments are not credential writes or merges. */
export function detect(core: string | null, muse: string | null): string[] {
  if (muse === null) {
    return ["MuseMintPersistence.kt missing — refusing to pass vacuously"];
  }
  return kimi
    .detect(kimi.codeOnly(core), kimi.codeOnly(muse))
    .map((problem) => problem.replaceAll("Kimi", "Muse").replaceAll("kimi", "muse"));
}

function rel(name: string): string {
  return name.split("\\").join("/");
}

/** Production Kotlin only. Tests fixture the RED oracle and are not writers. */
export function inLiveScope(r: string): boolean {
  const n = rel(r);
  return n.includes("/src/main/") && !n.includes("/src/test/");
}

export function isMuseOwning(r: string): boolean {
  return rel(r).toLowerCase().includes("muse");
}

/** Atomic 0600 writes in Muse-owning production files outside the allowed relative path. */
export function extraWriters(files: Record<string, string>): string[] {
  const hits: string[] = [];
  for (const name of Object.keys(files)) {
    const r = rel(name);
    if (!inLiveScope(r) || r === ALLOWED_WRITER) {
      continue;
    }
    if (!isMuseOwning(r)) {
      continue;
    }
    const code = kimi.codeOnly(files[name]) ?? "";
    if (code.includes(ATOMIC_WRITE)) {
      hits.push(r);
    }
  }
  return hits;
}

/** Atomic 0600 writes that are not the Muse persist and have no dated disposition. */
export function undisposedWriters(files: Record<string, string>): string[] {
  const hits: string[] = [];
  for (const name of Object.keys(files)) {
    const r = rel(name);
    if (!inLiveScope(r) || r === ALLOWED_WRITER || isMuseOwning(r)) {
      continue;
    }
    const code = kimi.codeOnly(files[name]) ?? "";
    if (code.includes(ATOMIC_WRITE) && !(r in NON_MUSE_ATOMIC_WRITERS)) {
      hits.push(r);
    }
  }
  return hits;
}

/** A disposition whose file is gone or no longer calls the atomic writer. */
export function staleDispositions(files: Record<string, string>): string[] {
  const hits: string[] = [];
  for (const r of Object.keys(NON_MUSE_ATOMIC_WRITERS)) {
    const source = files[r];
    if (source === undefined) {
      hits.push(r + " missing");
      continue;
    }
    if (!(kimi.codeOnly(source) ?? "").includes(ATOMIC_WRITE)) {
      hits.push(r + " no longer writes");
    }
  }
  return hits;
}

/** Every §2.2 module home's src/main directory — the homes that exist and the ones the next
 *  restructure PR 3 module commits create (a parent that is absent contributes nothing). A
 *  denominator that only walked gateway/ read this wall's own dispositioned atomic writers as
 *  MISSING once :client, then :core, moved out — which reds the wall for a staleness that is really
 *  the scan having stopped looking. So the list is every home, never one directory. */
const MODULE_PARENTS = ["gateway", "dialects", "providers", "daemon", "quality"];
const MODULE_HOMES = ["client", "core", "upstream", "app"];
function mainRoots(root: string): string[] {
  const roots: string[] = [];
  for (const parent of MODULE_PARENTS) {
    const dir = resolve(root, parent);
    if (!existsSync(dir)) continue;
    for (const mod of readdirSync(dir, { withFileTypes: true })) {
      if (!mod.isDirectory()) continue;
      const main = resolve(dir, mod.name, "src/main");
      if (existsSync(main)) roots.push(main);
    }
  }
  for (const home of MODULE_HOMES) {
    const main = resolve(root, home, "src/main");
    if (existsSync(main)) roots.push(main);
  }
  return roots;
}

/** Each module's src/main, recursively, keyed by repo-relative path. */
export function liveMainSources(root: string): Record<string, string> {
  const files: Record<string, string> = {};
  for (const main of mainRoots(root)) {
    const stack = [main];
    const found: string[] = [];
    while (stack.length > 0) {
      const cur = stack.pop() as string;
      for (const e of readdirSync(cur, { withFileTypes: true })) {
        const p = resolve(cur, e.name);
        if (e.isDirectory()) stack.push(p);
        else if (e.name.endsWith(".kt")) found.push(p);
      }
    }
    for (const path of found) {
      const r = rel(path.slice(root.length + 1));
      if (inLiveScope(r)) {
        files[r] = readFileSync(path, "utf8");
      }
    }
  }
  return files;
}

export function selftest(): number {
  const kimiStatus = kimi.selftest();
  const merged = `
        class MuseMintPersistence {
            private fun persistCredential(replacements: JsonObject) {
                val onDisk = readCredentialJson()
                val merged = CredentialJson.mergedCredentialJson(onDisk, replacements)
                SecureFile.writeAtomic0600(authPath, merged.toString())
            }
        }
    `;
  const fresh = merged.replace("authPath, merged.toString()", "authPath, replacements.toString()");
  const unsafe = merged.replace("SecureFile.writeAtomic0600", "Files.writeString");
  const commentOnly = merged.replace(
    "val merged = CredentialJson.mergedCredentialJson(onDisk, replacements)",
    "// val merged = CredentialJson.mergedCredentialJson(onDisk, replacements)\n" + "val merged = replacements",
  );
  const renamed = merged
    .replace("val merged =", "val credentialFile =")
    .replace("merged.toString()", "credentialFile.toString()");
  const filtered = merged.replace(
    "SecureFile.writeAtomic0600(authPath, merged.toString())",
    'val filtered = merged.filterKeys { it == "api_key" }\n' +
      "                SecureFile.writeAtomic0600(authPath, filtered.toString())",
  );
  const cases: [string, string | null, string | null, boolean][] = [
    ["merged Muse credential reaches the atomic write", kimi.CORE_OK, merged, false],
    ["Muse local rename keeps the merged value", kimi.CORE_OK, renamed, false],
    ["dead Muse merge with fresh replacements persisted", kimi.CORE_OK, fresh, true],
    ["filtered merged Muse object is not the persist value", kimi.CORE_OK, filtered, true],
    ["Muse bypasses the atomic 0600 write", kimi.CORE_OK, unsafe, true],
    ["Muse merge exists only in a comment", kimi.CORE_OK, commentOnly, true],
    ["Muse has no shared merge primitive", null, merged, true],
    ["Muse persist target is missing", kimi.CORE_OK, null, true],
    ["Muse persist target is empty", kimi.CORE_OK, "", true],
  ];
  const failures: string[] = [];
  for (const [label, core, source, expected] of cases) {
    if (detect(core, source).length > 0 !== expected) {
      failures.push(label);
    }
  }
  const missing = detect(kimi.CORE_OK, null);
  if (
    missing.length !== 1 ||
    missing[0] !== "MuseMintPersistence.kt missing — refusing to pass vacuously"
  ) {
    failures.push("missing-target names MuseMintPersistence.kt");
  }
  if (extraWriters({ [LOGIN_MUSE_PATH]: PRE_V4_23_LOGIN_MUSE }).length === 0) {
    failures.push("W3 muse-gate: pre-V4-23 LoginMuse writeAtomic0600 must be RED as a second writer");
  }
  if (extraWriters({ [LOGIN_MUSE_PATH]: LOGIN_MUSE_DELEGATED }).length > 0) {
    failures.push("delegating LoginMuse must be GREEN");
  }
  if (extraWriters({ [ALLOWED_WRITER]: merged }).length > 0) {
    failures.push("W1 path not basename: the allowed relative path itself is GREEN");
  }
  const commented = PRE_V4_23_LOGIN_MUSE.replace(
    "SecureFile.writeAtomic0600(target, merged.toString())",
    "// SecureFile.writeAtomic0600(target, merged.toString())",
  );
  if (extraWriters({ [LOGIN_MUSE_PATH]: commented }).length > 0) {
    failures.push("a commented-out atomic write is not a second writer");
  }
  if (extraWriters({ [CORE_FAKE_MUSE]: PRE_V4_23_LOGIN_MUSE }).length === 0) {
    failures.push("W1 path not basename: core MuseMintPersistence.kt must be RED");
  }
  if (extraWriters({ [LOGIN_CODEX_PATH]: PRE_V4_23_LOGIN_MUSE }).length > 0) {
    failures.push("W3 muse-gate: LoginCodex atomic write is not a Muse second writer");
  }
  if (
    undisposedWriters({
      "core/src/main/kotlin/splice/core/NewWriter.kt": PRE_V4_23_LOGIN_MUSE,
    }).length === 0
  ) {
    failures.push("W2 widen roots: undisposed atomic writer outside provider-muse and app/cli must be RED");
  }
  if (extraWriters({ [TEST_MUSE_WRITER]: PRE_V4_23_LOGIN_MUSE }).length > 0) {
    failures.push("W4 src/test: a muse test fixture calling writeAtomic0600 is out of live scope");
  }
  if (undisposedWriters({ [TEST_CORE_WRITER]: PRE_V4_23_LOGIN_MUSE }).length > 0) {
    failures.push("W4 src/test: a non-muse test atomic write is out of live scope");
  }
  if (failures.length > 0) {
    process.stdout.write("SH-10 MUSE SELFTEST FAIL:\n");
    for (const failure of failures) process.stdout.write("  " + failure + "\n");
    return 1;
  }
  process.stdout.write(
    "SH-10 MUSE SELFTEST OK — merged writes pass; fresh, filtered, unsafe, comment-only " +
      "and missing targets fail; W1 path not basename reds a core MuseMintPersistence.kt; " +
      "W2 widen roots reds an undisposed core writer; W3 muse-gate reds LoginMuse and " +
      "greens LoginCodex; W4 src/test fixtures are out of live scope\n",
  );
  return kimiStatus;
}

export function main(): number {
  if (process.argv.includes("--selftest")) return selftest();
  const kimiStatus = kimi.main();
  const core = existsSync(CORE) ? readFileSync(CORE, "utf8") : null;
  const muse = existsSync(MUSE) ? readFileSync(MUSE, "utf8") : null;
  const problems = detect(core, muse);
  const live = liveMainSources(ROOT);
  const seconds = extraWriters(live);
  if (seconds.length > 0) {
    problems.push(
      "second Muse credential writer: " + seconds.join(", ") +
        " — only " + ALLOWED_WRITER + " may persist a Muse credential with SecureFile.writeAtomic0600",
    );
  }
  const undisposed = undisposedWriters(live);
  if (undisposed.length > 0) {
    problems.push(
      "undisposed atomic writer: " + undisposed.join(", ") +
        " — add a dated disposition; absence is not one",
    );
  }
  const stale = staleDispositions(live);
  if (stale.length > 0) {
    problems.push("stale atomic-writer disposition: " + stale.join(", "));
  }
  if (problems.length > 0) {
    process.stdout.write("SH-10 MUSE WALL RED — the Muse credential persist must merge and write atomically:\n");
    for (const problem of problems) process.stdout.write("  " + problem + "\n");
    return 1;
  }
  process.stdout.write(
    "SH-10 MUSE WALL GREEN: Muse persists the shared merge result through " +
      "SecureFile.writeAtomic0600; no second Muse writer under gateway/*/src/main\n",
  );
  return kimiStatus;
}

if (import.meta.main) {
  process.exit(main());
}
