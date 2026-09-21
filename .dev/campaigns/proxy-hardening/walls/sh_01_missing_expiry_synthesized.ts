#!/usr/bin/env bun
/** WALL for SH-01 — ONE missing-expiry policy: no provider may treat a credential as never-expiring.
 *
 *  GAP (RED at authoring, 2026-08-07): G18 fixed the class on grok (mtime+4h synthesis); codex has
 *  the identical hole (a non-JWT / exp-less access token yields expiresAtMs=null => cached forever,
 *  no proactive refresh, first signal is a mid-turn 401) and kimi's `?: 0L` produces a refresh PER
 *  CALL below the hard floor instead of one.
 *
 *  GREEN requires ALL of:
 *    1. a shared core helper (synthesizedExpiryMs) exists in splice.core.auth;
 *    2. codex synthesizes: its exp-derived expiry expression falls back to synthesizedExpiryMs;
 *    3. kimi synthesizes: no `expires_at") ?: 0L` remains;
 *    4. grok consumes the SHARED helper (its private SYNTHETIC_EXPIRY_TTL_MS copy is gone) — one
 *       rule, not three implementations that drift.
 *
 *  EXIT 0 = one policy everywhere. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
 *
 *  V4-154: converted to TypeScript (bun). Four code views plus two RAW views, the raw pair feeding
 *  the two BANs; both HD-26 comment-satisfiability controls are in the corpus.
 */
import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";

/** Python repr() of a string, and of a list of strings. An f-string that interpolates a LIST
 *  renders THIS -- `['a', 'b']` -- not JSON, so a port that used JSON.stringify produced a
 *  DIFFERENT failure message than the original on every red path while agreeing on every green
 *  one. Caught by driving the mutants as a CLI rather than feeding detect() a fixed corpus. */
/** Python escapes a character when str.isprintable() is False: categories Cc Cf Cs Co Cn Zl Zp,
 *  and Zs except the plain space. Only \n \r \t get short spellings; the rest render \xNN below
 *  0x100, \uNNNN below 0x10000, \UNNNNNNNN above. */
const NON_PRINTABLE = /[\p{Cc}\p{Cf}\p{Cs}\p{Co}\p{Cn}\p{Zl}\p{Zp}\p{Zs}]/u;
function pyReprStr(s: string): string {
  const useDouble = s.includes("'") && !s.includes('"');
  const q = useDouble ? '"' : "'";
  let body = "";
  for (const ch of s) {
    const cp = ch.codePointAt(0) as number;
    if (ch === "\\") body += "\\\\";
    else if (ch === "\n") body += "\\n";
    else if (ch === "\r") body += "\\r";
    else if (ch === "\t") body += "\\t";
    else if (ch === q) body += "\\" + q;
    else if (NON_PRINTABLE.test(ch) && ch !== " ") {
      body +=
        cp < 0x100 ? "\\x" + cp.toString(16).padStart(2, "0")
        : cp < 0x10000 ? "\\u" + cp.toString(16).padStart(4, "0")
        : "\\U" + cp.toString(16).padStart(8, "0");
    } else body += ch;
  }
  return q + body + q;
}
function pyRepr(items: string[]): string {
  return "[" + items.map(pyReprStr).join(", ") + "]";
}

const ROOT = resolve(import.meta.dir, "../../../..");
const CORE = resolve(ROOT, "core/src/main/kotlin/splice/core/auth/SynthesizedExpiry.kt");
const CODEX = resolve(ROOT, "providers/codex/src/main/kotlin/splice/provider/codex/CodexAuthProvider.kt");
const KIMI = resolve(ROOT, "gateway/provider-kimi/src/main/kotlin/splice/provider/kimi/KimiAuthProvider.kt");
const GROK = resolve(ROOT, "gateway/provider-grok/src/main/kotlin/splice/provider/grok/GrokAuthProvider.kt");

/** Pure detection. No I/O — the selftest feeds it directly.
 *
 *  `core`/`codex`/`kimi`/`grok` are the CODE views (comments and imports stripped) and carry every
 *  REQUIRED token; `kimiRaw`/`grokRaw` are the untouched texts and carry the two BANs (kimi's
 *  `?: 0L` floor, grok's private TTL copy). The two directions want opposite treatment — see
 *  codeOnly. */
export function detect(
  core: string | null,
  codex: string | null,
  kimi: string | null,
  grok: string | null,
  kimiRaw: string | null,
  grokRaw: string | null,
): string[] {
  for (const [name, text] of [
    ["CodexAuthProvider", codex],
    ["KimiAuthProvider", kimi],
    ["GrokAuthProvider", grok],
  ] as [string, string | null][]) {
    if (text === null) {
      return [`${name}.kt missing — refusing to pass vacuously`];
    }
  }
  const problems: string[] = [];
  if (core === null || !core.includes("fun synthesizedExpiryMs(")) {
    problems.push(
      "no shared splice.core.auth.synthesizedExpiryMs — the missing-expiry rule " + "has no single source",
    );
  }
  if (!(codex ?? "").includes("synthesizedExpiryMs(")) {
    problems.push(
      "codex does not synthesize a missing expiry — a non-JWT/exp-less access " +
        "token is cached FOREVER (no proactive refresh, 401-only recovery)",
    );
  }
  if ((kimiRaw ?? "").includes('expires_at") ?: 0L')) {
    problems.push(
      "kimi still floors a missing expires_at to 0 — one refresh per call below " +
        "the hard floor instead of one per synthesized ceiling",
    );
  } else if (!(kimi ?? "").includes("synthesizedExpiryMs(")) {
    problems.push("kimi does not use the shared synthesis for a missing expires_at");
  }
  if ((grokRaw ?? "").includes("SYNTHETIC_EXPIRY_TTL_MS = ")) {
    problems.push(
      "grok still declares its private SYNTHETIC_EXPIRY_TTL_MS — the rule must " +
        "have ONE source (core), not a copy that can drift",
    );
  } else if (!(grok ?? "").includes("synthesizedExpiryMs(")) {
    problems.push("grok no longer synthesizes at all (G18 regression) — refusing to pass");
  }
  return problems;
}

const BLOCK_COMMENT = /\/\*[\s\S]*?\*\//g;
const LINE_COMMENT = /\/\/.*?$/gm;
const IMPORT_LINE = /^import .*$/gm;

/** A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy a
 *  REQUIRED token after the real call site is deleted. Same stripper cx_02/cx_09/cx_18 carry.
 *  Proven against this wall's own sources: with codex's one `CredentialExpiry.synthesizedExpiryMs(
 *  mtimeMs, clock())` call deleted and its literal text left in a TODO, the raw-matching wall
 *  printed WALL GREEN while a non-JWT codex token was cached forever again. Grok already carries a
 *  review note naming `synthesizedExpiryMs(` in a comment, so its required token was one deletion
 *  away from the same free pass.
 *
 *  Applied to read() (the required tokens) and deliberately NOT to readRaw(), which feeds the two
 *  BANs. The two directions want opposite treatment: stripping makes a required token harder to
 *  satisfy, but would make a banned string easier to hide. Both stay strict this way. */
export function codeOnly(text: string | null): string | null {
  if (text === null) return null;
  let stripped = text.replace(BLOCK_COMMENT, "");
  stripped = stripped.replace(LINE_COMMENT, "");
  return stripped.replace(IMPORT_LINE, "");
}

/** Untouched file text — the view the BANs are matched against (see codeOnly). */
function readRaw(p: string): string | null {
  return existsSync(p) ? readFileSync(p, "utf8") : null;
}

function read(p: string): string | null {
  return codeOnly(readRaw(p));
}

export const CORE_OK = "public fun synthesizedExpiryMs(mtimeMs: Long): Long";
export const GOOD = "synthesizedExpiryMs(mtime)";
export const CODEX_OPEN = "val expiresAtMs = decodeJwtClaims(access).long(FIELD_EXP)?.let { it * MS_PER_S }";
export const KIMI_OPEN = 'expiresAtS = obj.long("expires_at") ?: 0L,';
export const GROK_OPEN = "const val SYNTHETIC_EXPIRY_TTL_MS = 4L\n(mtime + SYNTHETIC_EXPIRY_TTL_MS)";

export const CODEX_COMMENTED =
  "val expiresAtMs = null\n" +
  "// TODO(SH-01): restore CredentialExpiry.synthesizedExpiryMs(mtimeMs, clock())";
export const GROK_HIDDEN_FORK =
  "synthesizedExpiryMs(mtime, now)\n// const val SYNTHETIC_EXPIRY_TTL_MS = 4L";

function selftest(): number {
  const fails: string[] = [];
  if (detect(null, CODEX_OPEN, KIMI_OPEN, GROK_OPEN, KIMI_OPEN, GROK_OPEN).length === 0) {
    fails.push("today's tree shape (no helper, codex nullable, kimi 0L, grok private) must be RED");
  }
  if (detect(CORE_OK, GOOD, GOOD, GOOD, GOOD, GOOD).length > 0) {
    fails.push(`one-policy-everywhere must be GREEN, got ${pyRepr(detect(CORE_OK, GOOD, GOOD, GOOD, GOOD, GOOD))}`);
  }
  if (detect(CORE_OK, CODEX_OPEN, GOOD, GOOD, GOOD, GOOD).length === 0) {
    fails.push("codex without synthesis must be RED");
  }
  if (detect(CORE_OK, GOOD, KIMI_OPEN, GOOD, KIMI_OPEN, GOOD).length === 0) {
    fails.push("kimi with ?: 0L must be RED");
  }
  if (detect(CORE_OK, GOOD, GOOD, GROK_OPEN, GOOD, GROK_OPEN).length === 0) {
    fails.push("grok with a private TTL copy must be RED");
  }
  if (detect(CORE_OK, null, GOOD, GOOD, GOOD, GOOD).length === 0) {
    fails.push("a missing provider file must be RED, never a vacuous pass");
  }
  // HD-26 comment-satisfiability controls. Both directions, so a later blind sweep that strips the
  // bans too (or stops stripping the required tokens) breaks the selftest instead of the invariant.
  if (detect(CORE_OK, CODEX_COMMENTED, GOOD, GOOD, GOOD, GOOD).length > 0) {
    fails.push(
      "the raw shape must read GREEN — otherwise this fixture is not the bug and " +
        "the control below proves nothing",
    );
  }
  if (detect(CORE_OK, codeOnly(CODEX_COMMENTED), GOOD, GOOD, GOOD, GOOD).length === 0) {
    fails.push(
      "a codex synthesis that survives only as comment text must be RED — required " +
        "tokens are matched against code, never raw file text",
    );
  }
  if (detect(CORE_OK, GOOD, GOOD, codeOnly(GROK_HIDDEN_FORK), GOOD, GROK_HIDDEN_FORK).length === 0) {
    fails.push(
      "a private grok TTL commented out of the code view must still be RED — the " +
        "bans read RAW so a comment cannot hide one",
    );
  }
  if (fails.length > 0) {
    process.stdout.write("SH-01 SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write(
    "SH-01 SELFTEST OK — red on missing helper, codex-forever, kimi-0L, grok private copy, " +
      "and missing files; green only on one shared policy in all three providers\n",
  );
  return 0;
}

function main(): number {
  if (process.argv.includes("--selftest")) return selftest();
  const problems = detect(
    read(CORE),
    read(CODEX),
    read(KIMI),
    read(GROK),
    readRaw(KIMI),
    readRaw(GROK),
  );
  if (problems.length > 0) {
    process.stdout.write("SH-01 WALL RED — the missing-expiry policy is not one-source-three-providers:\n");
    for (const p of problems) process.stdout.write(`  · ${p}\n`);
    return 1;
  }
  process.stdout.write(
    "SH-01 WALL GREEN: synthesizedExpiryMs is the one missing-expiry policy, all providers on it.\n",
  );
  return 0;
}

if (import.meta.main) {
  process.exit(main());
}
