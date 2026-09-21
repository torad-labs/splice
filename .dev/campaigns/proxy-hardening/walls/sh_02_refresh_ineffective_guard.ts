#!/usr/bin/env bun
/** WALL for SH-02 — a successful-but-ineffective grok refresh must not loop token burns.
 *
 *  GAP (RED at authoring, 2026-08-07): when the token endpoint returns no expires_in,
 *  persistRotation writes expiresAtMs=null and mergedAuthJson CARRIES OVER the stale on-disk
 *  `expires`; the very next credentials() lands below the stale floor and blocks on another refresh —
 *  per request, each one consuming a ROTATING refresh token (the 2026-07-18 credential-death shape).
 *  Nothing logs "I refreshed and the expiry did not move".
 *
 *  GREEN requires ALL of (CLIProxyAPI's refreshIneffectiveBackoff pattern):
 *    1. persistRotation synthesizes an expiry when expires_in is absent — a just-minted token is not
 *       older than the one it replaced (no bare `fresh.expiresIn?.let {...}` feeding the merge);
 *    2. a REFRESH_INEFFECTIVE_BACKOFF_MS guard exists — a Refreshed outcome that still evaluates
 *       inside the blocking tier suppresses further refreshes and serves the current token;
 *    3. the ineffective case is LOGGED ("did not advance") and COUNTED (ineffectiveRefresh counter)
 *       so the operator learns before the provider kills the credential.
 *
 *  EXIT 0 = guarded. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
 *
 *  V4-154: converted to TypeScript (bun). Two views of one file: the required tokens read the
 *  comment-stripped text, the null-expiry-persist BAN reads the raw text, and both HD-26
 *  comment-satisfiability controls are in the corpus.
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
const GROK = resolve(ROOT, "providers/grok/src/main/kotlin/splice/provider/grok/GrokAuthProvider.kt");

/** Pure detection. No I/O — the selftest feeds it directly.
 *
 *  `text` is the CODE view (comments and imports stripped) and carries every REQUIRED token; `raw`
 *  is the untouched file text and carries the null-expiry-persist BAN. The two directions want
 *  opposite treatment — see codeOnly. */
export function detect(text: string | null, raw: string | null): string[] {
  if (text === null) {
    return ["GrokAuthProvider.kt missing — refusing to pass vacuously"];
  }
  if (!text.includes("persistRotation")) {
    return ["persistRotation not found (shape changed?) — refusing to pass vacuously"];
  }
  const problems: string[] = [];
  if ((raw ?? "").includes("val expiresAtMs = fresh.expiresIn?.let { clock() + it * MS_PER_S }\n")) {
    problems.push(
      "persistRotation still writes a null expiry when expires_in is absent — " +
        "the merge carries the stale on-disk value and the blocking tier re-enters " +
        "per request, burning rotating refresh tokens",
    );
  }
  if (!text.includes("REFRESH_INEFFECTIVE_BACKOFF_MS")) {
    problems.push(
      "no REFRESH_INEFFECTIVE_BACKOFF_MS guard — a successful-but-ineffective " +
        "refresh loops as fast as turns arrive (CLIProxyAPI carries this exact guard)",
    );
  }
  if (!text.includes("did not advance")) {
    problems.push(
      "the ineffective case is not logged — token burn is invisible until the " +
        "provider kills the credential",
    );
  }
  if (!text.includes("ineffectiveRefresh")) {
    problems.push("the ineffective case is not counted — the dashboard cannot show it");
  }
  return problems;
}

const BLOCK_COMMENT = /\/\*[\s\S]*?\*\//g;
const LINE_COMMENT = /\/\/.*?$/gm;
const IMPORT_LINE = /^import .*$/gm;

/** A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy a
 *  REQUIRED token after the real call site is deleted. Same stripper cx_02/cx_09/cx_18 carry.
 *  Proven against this wall's own source: with the ineffective-refresh log call deleted and its
 *  literal text left in a TODO, the raw-matching wall printed WALL GREEN while token burn had gone
 *  silent again. GrokAuthProvider.kt is the densest commentary in the tree — the whole SH-02(a)/(b)
 *  rationale sits directly above the code it describes — so this file was the most exposed of all.
 *
 *  Applied to read() (the required tokens) and deliberately NOT to readRaw(), which feeds the
 *  null-expiry-persist BAN. The two directions want opposite treatment: stripping makes a required
 *  token harder to satisfy, but would make a banned string easier to hide. Both stay strict. */
export function codeOnly(text: string | null): string | null {
  if (text === null) return null;
  let stripped = text.replace(BLOCK_COMMENT, "");
  stripped = stripped.replace(LINE_COMMENT, "");
  return stripped.replace(IMPORT_LINE, "");
}

/** Untouched file text — the view the BAN is matched against (see codeOnly). */
function readRaw(p: string): string | null {
  return existsSync(p) ? readFileSync(p, "utf8") : null;
}

function read(p: string): string | null {
  return codeOnly(readRaw(p));
}

export const OPEN_FIX =
  "persistRotation\nval expiresAtMs = fresh.expiresIn?.let { clock() + it * MS_PER_S }\n";
export const CLOSED_FIX =
  "persistRotation\nval expiresAtMs = fresh.expiresIn?.let { clock() + it * MS_PER_S } " +
  "?: synthesizedExpiryMs(clock())\nREFRESH_INEFFECTIVE_BACKOFF_MS\n" +
  'log("refresh succeeded but expiry did not advance")\nineffectiveRefreshCount';

export const LOG_COMMENTED = CLOSED_FIX.replaceAll(
  'log("refresh succeeded but expiry did not advance")',
  '// TODO(SH-02): restore log("refresh succeeded but expiry did not advance")',
);
export const HIDDEN_NULL_PERSIST =
  CLOSED_FIX +
  "\n// dead code, kept for the diff: val expiresAtMs = " +
  "fresh.expiresIn?.let { clock() + it * MS_PER_S }\n";

function selftest(): number {
  const fails: string[] = [];
  if (detect(OPEN_FIX, OPEN_FIX).length === 0) {
    fails.push("null-expiry persist with no guard must be RED");
  }
  if (detect(CLOSED_FIX, CLOSED_FIX).length > 0) {
    fails.push(
      `synthesis + backoff + log + counter must be GREEN, got ${pyRepr(detect(CLOSED_FIX, CLOSED_FIX))}`,
    );
  }
  if (detect(CLOSED_FIX.replaceAll("REFRESH_INEFFECTIVE_BACKOFF_MS\n", ""), CLOSED_FIX).length === 0) {
    fails.push("synthesis without the backoff guard must be RED");
  }
  if (
    detect(CLOSED_FIX.replaceAll('log("refresh succeeded but expiry did not advance")\n', ""), CLOSED_FIX)
      .length === 0
  ) {
    fails.push("an unlogged ineffective path must be RED");
  }
  if (detect(null, null).length === 0) {
    fails.push("missing GrokAuthProvider.kt must be RED, never a vacuous pass");
  }
  if (detect("class GrokAuthProvider", "class GrokAuthProvider").length === 0) {
    fails.push("an unrecognized shape must be RED, never a vacuous pass");
  }
  // HD-26 comment-satisfiability controls. Both directions, so a later blind sweep that strips the
  // ban too (or stops stripping the required tokens) breaks the selftest instead of the invariant.
  if (detect(LOG_COMMENTED, LOG_COMMENTED).length > 0) {
    fails.push(
      "the raw shape must read GREEN — otherwise this fixture is not the bug and " +
        "the control below proves nothing",
    );
  }
  if (detect(codeOnly(LOG_COMMENTED), LOG_COMMENTED).length === 0) {
    fails.push(
      "an ineffective-refresh log that survives only as comment text must be RED — " +
        "required tokens are matched against code, never raw file text",
    );
  }
  if (detect(codeOnly(HIDDEN_NULL_PERSIST), HIDDEN_NULL_PERSIST).length === 0) {
    fails.push(
      "a null-expiry persist commented out of the code view must still be RED — the " +
        "ban reads RAW so a comment cannot hide it",
    );
  }
  if (fails.length > 0) {
    process.stdout.write("SH-02 SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write(
    "SH-02 SELFTEST OK — red on null-expiry persist, missing backoff, missing log/counter, " +
      "missing file, and shape change; green only with synthesis + bounded ineffective guard\n",
  );
  return 0;
}

function main(): number {
  if (process.argv.includes("--selftest")) return selftest();
  const problems = detect(read(GROK), readRaw(GROK));
  if (problems.length > 0) {
    process.stdout.write("SH-02 WALL RED — a successful-but-ineffective refresh still loops:\n");
    for (const p of problems) process.stdout.write(`  · ${p}\n`);
    return 1;
  }
  process.stdout.write(
    "SH-02 WALL GREEN: absent expires_in synthesizes, ineffective refreshes back off, logged and counted.\n",
  );
  return 0;
}

if (import.meta.main) {
  process.exit(main());
}
