#!/usr/bin/env bun
/** WALL for SH-06 — CredentialLock must never park a thread unboundedly on a live peer.
 *
 *  GAP (RED at authoring, 2026-08-07): withFileLock calls channel.lock() with no timeout. The
 *  dead-peer justification in the header is true but incomplete: a LIVE slow peer holds the lock for
 *  up to ~96s (refresh HTTP inside the lock x 3 attempts + backoff), and every credentials() call
 *  that reaches the blocking tier parks on it.
 *
 *  GREEN requires ALL of:
 *    1. no bare blocking channel.lock() in CredentialLock.kt;
 *    2. a tryLock()-based bounded wait exists (CREDENTIAL_LOCK_WAIT_MS budget);
 *    3. the expiry path DEGRADES to running unlocked with the honest log line ("proceeding
 *       unlocked") — G1's other layers own the residual race — rather than failing the refresh.
 *
 *  EXIT 0 = bounded. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (gate check C6).
 *
 *  V4-154: converted to TypeScript (bun). Two views of one file again: the required tokens and the
 *  shape guard read the comment-stripped text, the blocking-lock BAN reads the raw text. Both
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
const LOCK = resolve(ROOT, "upstream/src/main/kotlin/splice/upstream/credentials/CredentialLock.kt");

/** Pure detection. No I/O — the selftest feeds it directly.
 *
 *  `text` is the CODE view (comments and imports stripped) and carries the REQUIRED tokens plus the
 *  shape-change guard; `raw` is the untouched file text and carries the blocking-lock BAN. The two
 *  directions want opposite treatment — see codeOnly. The shape guard reads the CODE view on
 *  purpose: a `tryLock` that survives only in a comment is a changed shape, which is a RED. */
export function detect(text: string | null, raw: string | null): string[] {
  if (text === null) {
    return ["CredentialLock.kt missing — refusing to pass vacuously"];
  }
  if (!text.includes("tryLock") && !text.includes("channel.lock()")) {
    return ["neither tryLock nor channel.lock() found (shape changed?) — refusing to pass vacuously"];
  }
  const problems: string[] = [];
  if ((raw ?? "").includes("channel.lock()")) {
    problems.push(
      "bare channel.lock() still present — a live slow peer parks a real thread " +
        "for its whole ~96s refresh window, no budget",
    );
  }
  if (!text.includes("CREDENTIAL_LOCK_WAIT_MS")) {
    problems.push("no CREDENTIAL_LOCK_WAIT_MS budget — the wait is not bounded by a named knob");
  }
  if (!text.includes("proceeding unlocked")) {
    problems.push(
      "no unlocked-degrade path — on budget expiry the refresh must run unlocked " +
        "(G1's other layers own the residual race), never hang or fail",
    );
  }
  return problems;
}

const BLOCK_COMMENT = /\/\*[\s\S]*?\*\//g;
const LINE_COMMENT = /\/\/.*?$/gm;
const IMPORT_LINE = /^import .*$/gm;

/** A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy a
 *  REQUIRED token after the real call site is deleted. Same stripper cx_02/cx_09/cx_18 carry.
 *  Proven against this wall's own source: with the CREDENTIAL_LOCK_WAIT_MS constant and the
 *  "proceeding unlocked" degrade log deleted and their literal text left in TODOs, the raw-matching
 *  wall printed WALL GREEN while the bounded wait was gone. CredentialLock.kt's SH-06 header is an
 *  8-line comment naming both tokens, so this file was one deletion away from a free pass.
 *
 *  Applied to read() (the required tokens and the shape guard) and deliberately NOT to readRaw(),
 *  which feeds the blocking-lock BAN. The two directions want opposite treatment: stripping makes a
 *  required token harder to satisfy, but would make a banned string easier to hide. Both stay
 *  strict — a `channel.lock()` moved into a comment is still RED, now via the shape guard too. */
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

export const OPEN_LOCK = "val lock = withContext(Dispatchers.IO) { channel.lock() }";
// HD-26: `tryLock` used to sit in a trailing `// tryLock poll` comment here. Once the reader strips
// comments that fixture stopped modelling anything the reader can produce, so the poll call is code.
export const CLOSED_LOCK =
  "val lock = acquireBounded(channel)\n" +
  "channel.tryLock()\n" +
  "const val CREDENTIAL_LOCK_WAIT_MS = 15_000L\n" +
  'log("... proceeding unlocked ...")';
export const BOUNDED_COMMENTED =
  "val lock = acquireBounded(channel)\nchannel.tryLock()\n" +
  "// TODO(SH-06): restore const val CREDENTIAL_LOCK_WAIT_MS = 15_000L and the " +
  'log("... proceeding unlocked ...") degrade';
export const HIDDEN_BLOCKING_LOCK =
  CLOSED_LOCK + "\n// legacy: val lock = withContext(Dispatchers.IO) { channel.lock() }";

function selftest(): number {
  const fails: string[] = [];
  if (detect(OPEN_LOCK, OPEN_LOCK).length === 0) {
    fails.push("blocking channel.lock() must be RED");
  }
  if (detect(CLOSED_LOCK, CLOSED_LOCK).length > 0) {
    fails.push(
      `bounded tryLock + budget + degrade must be GREEN, got ${pyRepr(detect(CLOSED_LOCK, CLOSED_LOCK))}`,
    );
  }
  if (detect(CLOSED_LOCK.replaceAll('log("... proceeding unlocked ...")', ""), CLOSED_LOCK).length === 0) {
    fails.push("a bounded wait WITHOUT the unlocked-degrade path must be RED");
  }
  if (detect(null, null).length === 0) {
    fails.push("missing CredentialLock.kt must be RED, never a vacuous pass");
  }
  if (detect("object CredentialLock {}", "object CredentialLock {}").length === 0) {
    fails.push("an unrecognized shape must be RED, never a vacuous pass");
  }
  // HD-26 comment-satisfiability controls. Both directions, so a later blind sweep that strips the
  // ban too (or stops stripping the required tokens) breaks the selftest instead of the invariant.
  if (detect(BOUNDED_COMMENTED, BOUNDED_COMMENTED).length > 0) {
    fails.push(
      "the raw shape must read GREEN — otherwise this fixture is not the bug and " +
        "the control below proves nothing",
    );
  }
  if (detect(codeOnly(BOUNDED_COMMENTED), BOUNDED_COMMENTED).length === 0) {
    fails.push(
      "a budget and degrade log that survive only as comment text must be RED — " +
        "required tokens are matched against code, never raw file text",
    );
  }
  if (detect(codeOnly(HIDDEN_BLOCKING_LOCK), HIDDEN_BLOCKING_LOCK).length === 0) {
    fails.push(
      "a blocking channel.lock() commented out of the code view must still be RED — " +
        "the ban reads RAW so a comment cannot hide it",
    );
  }
  if (fails.length > 0) {
    process.stdout.write("SH-06 SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write(
    "SH-06 SELFTEST OK — red on blocking lock, missing budget, missing degrade, missing " +
      "file, and shape change; green only on bounded tryLock with an honest unlocked degrade\n",
  );
  return 0;
}

function main(): number {
  if (process.argv.includes("--selftest")) return selftest();
  const problems = detect(read(LOCK), readRaw(LOCK));
  if (problems.length > 0) {
    process.stdout.write("SH-06 WALL RED — CredentialLock can park a thread forever on a live peer:\n");
    for (const p of problems) process.stdout.write(`  · ${p}\n`);
    return 1;
  }
  process.stdout.write(
    "SH-06 WALL GREEN: the credential lock waits a bounded budget, then degrades unlocked, honestly logged.\n",
  );
  return 0;
}

if (import.meta.main) {
  process.exit(main());
}
