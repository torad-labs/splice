#!/usr/bin/env bun
/** WALL for SH-11 — KeyStore mutations must never rebuild the file from a failed read.
 *
 *  GAP (RED at authoring, 2026-08-07): write()/unset() are read-modify-write over entries(), and
 *  entries() collapses ANY read failure to an empty map (getOrDefault(emptyMap())). A transient
 *  EINTR/permissions blip makes write() persist a file containing ONLY the key being written —
 *  silently deleting every other stored API key. No lock exists, so two concurrent `splice key set`
 *  invocations lose one write even on healthy reads.
 *
 *  GREEN requires ALL of:
 *    1. write() AND unset() each read STRICTLY (absent file = legitimately empty and safe; unreadable
 *       file ABORTS with "refusing to write" — existing keys preserved); tolerant display reads do not
 *       earn either mutation site's strict-read leg;
 *    2. mutations run under a cross-process file lock (the G1 lesson, applied to keys.toml);
 *    3. the tolerant getOrDefault(emptyMap()) no longer feeds persist().
 *
 *  EXIT 0 = mutations safe. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
 *
 *  V4-154: converted to TypeScript (bun). This is the densest regex wall in the burn-down, so the
 *  port pins the semantics rather than approximating them: Python's re.S becomes [\s\S] (a JS `.`
 *  stops at a newline), the named group becomes a positional capture, the lookahead that stops the
 *  scan at the NEXT `fun ` is kept verbatim, and re.escape is reimplemented for the name splice.
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
const STORE = resolve(ROOT, "core/src/main/kotlin/splice/core/config/KeyStore.kt");

/** Python re.escape, for the function names this wall interpolates into a pattern. These are
 *  identifiers so nothing is escaped in practice; carried so a renamed target cannot turn into a
 *  pattern injection. */
function reEsc(s: string): string {
  return s.replace(/[^A-Za-z0-9_]/g, (c) => "\\" + c);
}

/** Python re.findall(...).length for a group-less pattern: the number of non-overlapping matches. */
function countMatches(source: string, text: string): number {
  return [...text.matchAll(new RegExp(source, "g"))].length;
}

/** The unique withStoreLock lambda reached directly by this public mutation. */
export function mutationTransaction(text: string, name: string): string | null {
  if (countMatches("\\bfun\\s+" + reEsc(name) + "\\s*\\(", text) !== 1) {
    return null;
  }
  const re = new RegExp(
    "\\bfun\\s+" +
      reEsc(name) +
      // (?:(?!\bfun\s)[\s\S])*? is Python's (?:(?!\bfun\s).)*? under re.S: the lazy scan stops
      // as soon as the next `fun ` begins, so a mutation cannot reach into a sibling function.
      "\\s*\\([^)]*\\)(?:(?!\\bfun\\s)[\\s\\S])*?\\bwithStoreLock\\s*\\{([\\s\\S]*?)\\}",
  );
  const match = re.exec(text);
  return match === null ? null : match[1];
}

export function strictPersist(transaction: string | null): boolean {
  if (transaction === null || transaction.includes("entries()")) {
    return false;
  }
  const strict = /\bval\s+(\w+)\s*=\s*entriesStrict\(\)\.toMutableMap\(\)/.exec(transaction);
  return (
    strict !== null &&
    countMatches("\\bpersist\\s*\\(", transaction) === 1 &&
    new RegExp("\\bpersist\\s*\\(\\s*" + reEsc(strict[1]) + "\\s*\\)").test(transaction)
  );
}

/** Pure detection. No I/O — the selftest feeds it directly. */
export function detect(text: string | null): string[] {
  if (text === null) {
    return ["KeyStore.kt missing — refusing to pass vacuously"];
  }
  const mutations: Record<string, string | null> = {};
  for (const name of ["write", "unset"]) {
    mutations[name] = mutationTransaction(text, name);
  }

  const problems: string[] = [];
  for (const name of Object.keys(mutations)) {
    if (!strictPersist(mutations[name])) {
      problems.push(
        `${name}() does not derive its persisted map directly from ` +
          "entriesStrict().toMutableMap() inside withStoreLock — discarded strict " +
          "reads, tolerant maps, or disconnected locks earn nothing",
      );
    }
  }
  if (!text.includes("refusing to write")) {
    problems.push(
      "an unreadable store does not abort loudly — the operator learns about " +
        "key loss from the next 401, not from the failed command",
    );
  }
  if (!text.includes("tryLock") && !text.includes("FileLock")) {
    problems.push(
      "no cross-process lock on mutations — two concurrent `splice key set` " +
        "invocations lose one write (the G1 lesson, unapplied to keys.toml)",
    );
  }
  return problems;
}

const BLOCK_COMMENT = /\/\*[\s\S]*?\*\//g;
const LINE_COMMENT = /\/\/.*?$/gm;
const IMPORT_LINE = /^import .*$/gm;

/** A mention is not a wiring. Without this the wall is satisfiable by a COMMENT: put the locked
 *  mutation body back to `entries().toMutableMap()`, leave
 *  `// TODO: restore entriesStrict() ... error("... refusing to write") ... channel.tryLock()`
 *  behind, and all four required tokens still match while the blind read-modify-write is live
 *  again. Proven against this file's own source before the stripper landed. Same stripper
 *  cx_01/cx_02/cx_09/cx_18/jw_08 carry.
 *
 *  Every assertion here is a REQUIRED token — this wall carries no banned string — so stripping is
 *  the strict direction throughout: it can only make a requirement harder to satisfy, never hide a
 *  violation (the split jw_08 has to make between its two readers does not arise). */
export function codeOnly(text: string | null): string | null {
  if (text === null) return null;
  let stripped = text.replace(BLOCK_COMMENT, "");
  stripped = stripped.replace(LINE_COMMENT, "");
  return stripped.replace(IMPORT_LINE, "");
}

function read(p: string): string | null {
  return existsSync(p) ? codeOnly(readFileSync(p, "utf8")) : null;
}

export const OPEN_FIX =
  "fun write() { val next = entries().toMutableMap(); persist(next) }\n" +
  "fun unset() { val next = entries().toMutableMap(); persist(next) }\n" +
  ".getOrDefault(emptyMap())";
const STRICT_SUPPORT =
  'fun entriesStrict() = error("keys.toml unreadable — refusing to write")\n' +
  "fun acquireBounded() { channel.tryLock() }\n";
const STRICT_WRITE = "fun write() { withStoreLock { val next = entriesStrict().toMutableMap(); persist(next) } }\n";
const STRICT_UNSET = "fun unset() { withStoreLock { val next = entriesStrict().toMutableMap(); persist(next) } }\n";
const TOLERANT_WRITE = "fun write() { withStoreLock { val next = entries().toMutableMap(); persist(next) } }\n";
const TOLERANT_UNSET = "fun unset() { withStoreLock { val next = entries().toMutableMap(); persist(next) } }\n";
export const CLOSED_FIX = STRICT_WRITE + STRICT_UNSET + STRICT_SUPPORT;
export const WRITE_ONLY_STRICT = STRICT_WRITE + TOLERANT_UNSET + STRICT_SUPPORT;
export const UNSET_ONLY_STRICT = TOLERANT_WRITE + STRICT_UNSET + STRICT_SUPPORT;
export const DISCARDED_STRICT =
  "fun write() { withStoreLock { entriesStrict(); val next = entries().toMutableMap(); persist(next) } }\n" +
  "fun unset() { withStoreLock { entriesStrict(); val next = entries().toMutableMap(); persist(next) } }\n" +
  STRICT_SUPPORT;
export const UNLOCKED_STRICT =
  "fun write() { val next = entriesStrict().toMutableMap(); persist(next) }\n" +
  "fun unset() { val next = entriesStrict().toMutableMap(); persist(next) }\n" +
  STRICT_SUPPORT;
export const DECOY_MUTATIONS =
  "class Decoy { " + STRICT_WRITE + STRICT_UNSET + "}\n" + TOLERANT_WRITE + TOLERANT_UNSET + STRICT_SUPPORT;

function selftest(): number {
  const fails: string[] = [];
  if (detect(OPEN_FIX).length === 0) {
    fails.push("blind RMW with tolerant read must be RED");
  }
  if (detect(CLOSED_FIX).length > 0) {
    fails.push(`strict read + abort + lock must be GREEN, got ${pyRepr(detect(CLOSED_FIX))}`);
  }
  if (detect(CLOSED_FIX.replaceAll("channel.tryLock()", "")).length === 0) {
    fails.push("strict read without the lock must be RED");
  }
  if (detect(CLOSED_FIX.replaceAll("entriesStrict()", "entries()")).length === 0) {
    fails.push("a lock without the strict read must be RED");
  }
  if (detect(WRITE_ONLY_STRICT).length === 0) {
    fails.push("write() strict while unset() still reads tolerantly must be RED");
  }
  if (detect(UNSET_ONLY_STRICT).length === 0) {
    fails.push("unset() strict while write() still reads tolerantly must be RED");
  }
  if (detect(DISCARDED_STRICT).length === 0) {
    fails.push("discarded strict reads followed by tolerant persisted maps must be RED");
  }
  if (detect(UNLOCKED_STRICT).length === 0) {
    fails.push("strict read-modify-write outside withStoreLock must be RED");
  }
  if (detect(DECOY_MUTATIONS).length === 0) {
    fails.push("same-name decoy mutations must not mask unsafe write()/unset()");
  }
  if (detect(null).length === 0) {
    fails.push("missing KeyStore.kt must be RED, never a vacuous pass");
  }
  if (detect("class KeyStore").length === 0) {
    fails.push("an unrecognized shape must be RED, never a vacuous pass");
  }
  if (fails.length > 0) {
    process.stdout.write("SH-11 SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write(
    "SH-11 SELFTEST OK — red on blind/discarded strict reads, either unsafe mutation, " +
      "disconnected locks, same-name decoys, missing file, and shape change; green only when " +
      "both persisted maps derive from strict reads inside withStoreLock\n",
  );
  return 0;
}

function main(): number {
  if (process.argv.includes("--selftest")) return selftest();
  const problems = detect(read(STORE));
  if (problems.length > 0) {
    process.stdout.write("SH-11 WALL RED — KeyStore mutations can destroy sibling keys:\n");
    for (const p of problems) process.stdout.write(`  · ${p}\n`);
    return 1;
  }
  process.stdout.write(
    "SH-11 WALL GREEN: mutations read strictly, abort loudly on unreadable state, and hold the file lock.\n",
  );
  return 0;
}

if (import.meta.main) {
  process.exit(main());
}
