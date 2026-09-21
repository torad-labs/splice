#!/usr/bin/env bun
/** WALL for SH-10 — the kimi credential file is MERGED on refresh, never rewritten from scratch.
 *
 *  GAP (RED at authoring, 2026-08-07): KimiAuthProvider writes kimiAuthJson(...) — a fixed six-key
 *  object — over whatever was on disk, dropping every field kimi-cli/kimi-code stores beside ours.
 *  This is the exact shape the 2026-07-18 audit condemned and grok/codex already fixed with merges.
 *
 *  GREEN requires BOTH:
 *    1. a shared merge primitive exists (mergedCredentialJson in splice.core) — "merge, never
 *       rewrite" as a property of the primitive, not a habit three files independently remember
 *       and one already forgot;
 *    2. the kimi refresh persists the MERGED object through the atomic 0600 credential write
 *       (SecureFile.writeAtomic0600), not a from-scratch kimiAuthJson(...) rebuild.
 *
 *  EXIT 0 = merged. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
 *
 *  V4-154: converted to TypeScript (bun). This is the deepest wall in the burn-down: a VALUE trace,
 *  not a token search. Everything load-bearing was carried exactly — Python's named groups and NAMED
 *  BACKREFERENCES become JS named groups and \k<name>, re.S becomes [\s\S], re.match becomes an
 *  anchored pattern, the brace-ancestry snapshot is taken before the char is processed, the
 *  nearest-visible-assignment pick reproduces Python's max() over a (len, position-or-negative-position)
 *  tuple, and the masker is the same offset-preserving one nf_03 carries.
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
// 2026-08-16 — HD-M8, the :core style slice. `mergedCredentialJson` was a top-level function; the
// Kotlin style law bans those, and both of its arguments are kotlinx JsonObjects (a foreign receiver
// that cannot host a member), so it became a member of `object CredentialJson`. detekt then forces
// two things at once: MatchingDeclarationName wants the file named after its single declaration, and
// MemberNameEqualsClassName forbids `object MergedCredentialJson { fun mergedCredentialJson }` — so
// the FILE moved, MergedCredentialJson.kt -> CredentialJson.kt. Same primitive, same function name,
// same merge order; the token below is unchanged and the "core source missing" arm still refuses a
// vacuous pass if this path ever goes stale again.
const CORE = resolve(ROOT, "core/src/main/kotlin/splice/core/auth/CredentialJson.kt");
const KIMI = resolve(ROOT, "providers/kimi/src/main/kotlin/splice/provider/kimi/KimiAuthProvider.kt");

// 2026-08-18 — HD-28, the re-anchor. Every arm below used to key on `writeSecure(`, the name of a
// one-line PRIVATE forward (`private fun writeSecure(p, c) { SecureFile.writeAtomic0600(p, c) }`)
// that each provider happened to carry. That is an incidental spelling, not the invariant, and it
// failed in BOTH directions: renaming or deleting the forward reddened a wall whose behaviour was
// intact, while routing the persist through a differently-named helper that skipped the atomic 0600
// write stayed GREEN — the exact world-readable-window regression #924 extracted SecureFile to make
// inexpressible. The anchor is now the call that CARRIES the invariant, SecureFile.writeAtomic0600,
// so the wall is blind to what any wrapper is called and red the instant the atomic write is not
// what persists the credential.
const ATOMIC_WRITE = "SecureFile.writeAtomic0600(";

// 2026-08-18 — HD-26, the regression arm made real. The re-anchor above moved the PRESENCE check
// onto SecureFile.writeAtomic0600 correctly and then left the regression arm as two literal
// spellings of the write:
//     SecureFile.writeAtomic0600(authPath, kimiAuthJson(
//     SecureFile.writeAtomic0600(authPath, oauth.kimiAuthJson(
// Neither can EVER match KimiAuthProvider.kt. The persist there goes through the private forward
// `writeSecure(path, content)`, so the atomic write only ever sees the forward's parameter names —
// the merge is built at one call site and the atomic write happens at another. PROVEN out-of-tree
// rather than argued: copy the real CredentialJson.kt + KimiAuthProvider.kt under a fixture root,
// rewrite the single line
//     writeSecure(authPath, merged.toString())
// to
//     writeSecure(authPath, oauth.kimiAuthJson(attempt.tokens, clock()).toString())
// — the merge computed and then dropped, which is EXACTLY the regression this wall is named for —
// and the wall printed "SH-10 WALL GREEN", exit 0. The only arm that had been holding anything was
// the bare-identifier fallback `"mergedCredentialJson(" not in kimi`, and a dead merge satisfies it.
//
// The anchor is now the DATAFLOW at the real call site: whatever content reaches the atomic write
// must come from CredentialJson.mergedCredentialJson, either inlined there or carried by a local
// whose value is that call. This is NARROWER than the bare fallback it replaces, not wider — it
// never asks whether an identifier appears somewhere in the module, only whether the value being
// persisted is the merge. It is blind to the local's name and to the forward's name, which is the
// same blindness the HD-28 re-anchor bought, held one level further in.
const MERGE_CALL = "CredentialJson.mergedCredentialJson(";

const BLOCK_COMMENT = /\/\*[\s\S]*?\*\//g;
const LINE_COMMENT = /\/\/.*?$/gm;
const IMPORT_LINE = /^import .*$/gm;

// A private forward under ANY name, in either body form:
//     private fun writeSecure(path: Path, content: String) { SecureFile.writeAtomic0600(path, content) }
//     private fun writeSecure(path: Path, content: String): Unit = SecureFile.writeAtomic0600(path, content)
const FORWARD = new RegExp(
  "private fun (?<name>\\w+)\\s*\\(\\s*(?<path>\\w+)\\s*:[^,]+,\\s*" +
    "(?<content>\\w+)\\s*:[^)]+\\)[^={]*(?:\\{[^{}]*|=\\s*)" +
    ATOMIC_WRITE.replace(/[^A-Za-z0-9_]/g, (c) => "\\" + c) +
    "\\s*\\k<path>\\s*,\\s*\\k<content>\\s*\\)",
  "g",
);

// The value handed to the persist, when it is carried by a local rather than inlined.
const LOCAL = /^(\w+)(?:\.toString\(\))?$/;
const MERGE_VALUE_RE = new RegExp("^\\s*" + MERGE_CALL.replace(/[^A-Za-z0-9_]/g, (c) => "\\" + c));

/** Blank Kotlin strings/chars without moving offsets. */
export function maskStrings(text: string): string {
  const chars = [...text];
  let i = 0;
  while (i < chars.length) {
    let end: number;
    if (text.startsWith('"""', i)) {
      const close = text.indexOf('"""', i + 3);
      end = close < 0 ? chars.length : close + 3;
    } else if (chars[i] === '"' || chars[i] === "'") {
      const quote = chars[i];
      end = i + 1;
      while (end < chars.length) {
        if (chars[end] === "\\") {
          end += 2;
        } else if (chars[end] === quote) {
          end += 1;
          break;
        } else {
          end += 1;
        }
      }
    } else {
      i += 1;
      continue;
    }
    for (let at = i; at < Math.min(end, chars.length); at++) {
      if (!"\r\n".includes(chars[at])) {
        chars[at] = " ";
      }
    }
    i = end;
  }
  return chars.join("");
}

/** Resolve every requested brace ancestry in one lexical pass. */
export function scopeStacks(text: string, positions: number[]): Map<number, number[]> {
  const targets = [...new Set(positions)].sort((a, b) => a - b);
  const result = new Map<number, number[]>();
  if (targets.length === 0) return result;
  const structure = maskStrings(text);
  const stack: number[] = [];
  let target = 0;
  for (let at = 0; at < structure.length; at++) {
    const ch = structure[at];
    while (target < targets.length && targets[target] === at) {
      result.set(targets[target], [...stack]);
      target += 1;
    }
    if (ch === "{") {
      stack.push(at);
    } else if (ch === "}" && stack.length > 0) {
      stack.pop();
    }
  }
  while (target < targets.length) {
    result.set(targets[target], [...stack]);
    target += 1;
  }
  return result;
}

function reEsc(s: string): string {
  return s.replace(/[^A-Za-z0-9_]/g, (c) => "\\" + c);
}

/** Function, lambda, and loop parameters shadow outer merged properties/locals. */
export function shadowedByParameter(name: string, useAt: number, text: string, used: number[]): boolean {
  const structure = maskStrings(text);
  const head = structure.slice(0, useAt);
  for (const fn of head.matchAll(new RegExp("\\bfun\\s+\\w+\\s*\\((?<params>[^)]*)\\)[^{=]*\\{", "g"))) {
    const endPos = (fn.index as number) + fn[0].length;
    if (used.includes(endPos - 1) && new RegExp("\\b" + reEsc(name) + "\\s*:").test(fn.groups?.["params"] ?? "")) {
      return true;
    }
  }
  for (const brace of used) {
    const before = structure.slice(Math.max(0, brace - 300), brace);
    if (new RegExp("\\bfor\\s*\\(\\s*" + reEsc(name) + "\\s+in\\b[^{}]*$").test(before)) {
      return true;
    }
    const lambdaHead = structure.slice(brace + 1, Math.min(useAt, brace + 300));
    const parameters = /^\s*([^(){};\n]*?)\s*->/.exec(lambdaHead);
    if (parameters !== null && new RegExp("\\b" + reEsc(name) + "\\b").test(parameters[1])) {
      return true;
    }
  }
  return false;
}

/** A mention is not a wiring — and to a wall that reads CALL SITES, a comment is one.
 *
 *  This cuts both ways here, unlike the required-token walls (sh_11/cx_01/cx_02/cx_09/cx_18/jw_08)
 *  that carry the same stripper for the strict direction only. FAIL-OPEN: a commented-out
 *  `val merged = CredentialJson.mergedCredentialJson(...)` sitting above a live
 *  `val merged = oauth.kimiAuthJson(...)` makes the local's trace find the merge in the comment.
 *  FALSE-RED: a commented-out old from-scratch write beside the live merged one reads as a second,
 *  unmerged persist. Both shapes are pinned in the selftest. */
export function codeOnly(text: string | null): string | null {
  if (text === null) return null;
  let stripped = text.replace(BLOCK_COMMENT, "");
  stripped = stripped.replace(LINE_COMMENT, "");
  return stripped.replace(IMPORT_LINE, "");
}

function closeParen(text: string, openIdx: number): number | null {
  let depth = 0;
  for (let i = openIdx; i < text.length; i++) {
    if (text[i] === "(") {
      depth += 1;
    } else if (text[i] === ")") {
      depth -= 1;
      if (depth === 0) return i;
    }
  }
  return null;
}

function splitArgs(inside: string): string[] {
  const parts: string[] = [];
  let cur: string[] = [];
  let depth = 0;
  for (const ch of inside) {
    if ("([{".includes(ch)) {
      depth += 1;
    } else if (")]}".includes(ch)) {
      depth -= 1;
    }
    if (ch === "," && depth === 0) {
      parts.push(cur.join("").trim());
      cur = [];
    } else {
      cur.push(ch);
    }
  }
  parts.push(cur.join("").trim());
  return parts;
}

/** The content argument of every live credential persist — the atomic write itself plus any
 *  private forward that reaches it.
 *
 *  A forward's own body is the DEFINITION of that alias, not a second persist, so its span is
 *  blanked before call sites are collected. Without that the wall would demand that the
 *  `SecureFile.writeAtomic0600(path, content)` INSIDE `private fun writeSecure` trace to the
 *  merge, which is nonsense — `content` is a parameter. */
export function persistContents(kimi: string): [string, number][] {
  const names = new Set<string>([ATOMIC_WRITE.slice(0, -1)]);
  const chars = [...kimi];
  for (const m of kimi.matchAll(FORWARD)) {
    names.add(m.groups?.["name"] as string);
    const close = closeParen(kimi, (m.index as number) + m[0].length - 1);
    const stop = close === null ? kimi.length : close + 1;
    for (let i = m.index as number; i < stop; i++) {
      chars[i] = " ";
    }
  }
  const scrubbed = chars.join("");

  const contents: [string, number][] = [];
  for (const name of [...names].sort()) {
    for (const m of scrubbed.matchAll(new RegExp("(?<![\\w.])" + reEsc(name) + "\\s*\\(", "g"))) {
      const openIdx = (m.index as number) + m[0].length - 1;
      const close = closeParen(scrubbed, openIdx);
      if (close === null) continue;
      const args = splitArgs(scrubbed.slice(openIdx + 1, close));
      if (args.length >= 2) {
        contents.push([args[1], m.index as number]);
      }
    }
  }
  return contents;
}

/** The expression assigned at `start` (just past the `=`): through the end of its first balanced
 *  bracket group, or to end of line if it opens none.
 *
 *  Enough to tell `val x = CredentialJson.mergedCredentialJson(...)` from
 *  `val x = oauth.kimiAuthJson(...)` across the two-line wrap the real file uses, without
 *  pretending to be a Kotlin parser. */
function assignedExpression(text: string, start: number): string {
  let depth = 0;
  let opened = false;
  for (let i = start; i < text.length; i++) {
    const ch = text[i];
    if ("([{".includes(ch)) {
      depth += 1;
      opened = true;
    } else if (")]}".includes(ch)) {
      depth -= 1;
      if (opened && depth === 0) {
        return text.slice(start, i + 1);
      }
    } else if (ch === "\n" && !opened && text.slice(start, i).trim() !== "") {
      return text.slice(start, i);
    }
  }
  return text.slice(start);
}

/** Does the value handed to this persist come from the shared merge?
 *
 *  Two shapes, deliberately only two: the merge inlined at the write, or a visible LOCAL whose
 *  nearest preceding assignment is the merge. The order and lexical visibility are load-bearing:
 *  a post-write assignment or a same-name merge in a closed sibling function cannot define the
 *  value already written here.
 *
 *  STATED LIMIT, so the next reader knows it is a choice and not an oversight: a THIRD shape — the
 *  merge reaching the write through some other transform, say `json.encodeToString(merged)` instead
 *  of `merged.toString()` — reddens this wall. That is the fail-closed direction, and the RED names
 *  the exact expression it saw, so it is a one-line read rather than a mystery. */
export function reachesMerge(content: string, persistAt: number, kimi: string): boolean {
  if (MERGE_VALUE_RE.test(content)) {
    return true;
  }
  const local = LOCAL.exec(content);
  if (local === null) {
    return false;
  }
  const name = local[1];
  const structure = maskStrings(kimi);
  const assignments = [...structure.matchAll(new RegExp("\\b(?:val|var)\\s+" + reEsc(name) + "\\b[^=\\n]*=", "g"))];
  const scopes = scopeStacks(kimi, [persistAt, ...assignments.map((a) => a.index as number)]);
  const used = scopes.get(persistAt) as number[];
  if (shadowedByParameter(name, persistAt, kimi, used)) {
    return false;
  }

  const visible: [number, number, RegExpExecArray][] = [];
  for (const assign of assignments) {
    const at = assign.index as number;
    const assigned = scopes.get(at) as number[];
    const ancestor = assigned.length <= used.length && assigned.every((v, i) => used[i] === v);
    const precedingLocal = at < persistAt;
    const laterMember = at > persistAt && assigned.length < used.length;
    if (ancestor && (precedingLocal || laterMember)) {
      visible.push([assigned.length, precedingLocal ? at : -at, assign]);
    }
  }
  if (visible.length === 0) {
    return false;
  }
  // Python max() over the (len, signed-position) tuple, taking the first maximal element.
  let nearest = visible[0];
  for (const candidate of visible) {
    if (candidate[0] > nearest[0] || (candidate[0] === nearest[0] && candidate[1] > nearest[1])) {
      nearest = candidate;
    }
  }
  const nearestAssign = nearest[2];
  return MERGE_VALUE_RE.test(assignedExpression(kimi, (nearestAssign.index as number) + nearestAssign[0].length));
}

/** Pure detection. No I/O — the selftest feeds it directly. */
export function detect(core: string | null, kimi: string | null): string[] {
  if (kimi === null) {
    return ["KimiAuthProvider.kt missing — refusing to pass vacuously"];
  }
  if (!kimi.includes(ATOMIC_WRITE)) {
    return [
      "the kimi credential write no longer reaches SecureFile.writeAtomic0600 — the " +
        "atomic 0600 primitive is what closes the world-readable window; refusing to pass " +
        "vacuously",
    ];
  }
  const problems: string[] = [];
  if (core === null || !core.includes("fun mergedCredentialJson(")) {
    problems.push(
      "no shared mergedCredentialJson primitive — merge-never-rewrite is still " + "a per-provider habit",
    );
  }
  const contents = persistContents(kimi);
  if (contents.length === 0) {
    problems.push(
      "SecureFile.writeAtomic0600 is present but nothing calls it with a " +
        "(path, content) pair — the persist shape changed; refusing to pass " +
        "vacuously",
    );
    return problems;
  }
  const unmerged = contents.filter(([c, at]) => !reachesMerge(c, at, kimi)).map(([c]) => c);
  if (unmerged.length === 0) {
    return problems;
  }
  const written = unmerged.map((c) => "`" + c + "`").join(", ");
  if (!kimi.includes(MERGE_CALL)) {
    problems.push(
      `kimi's write no longer routes through the shared merge primitive — the ` +
        `credential persist is handed ${written}`,
    );
  } else {
    problems.push(
      `kimi still rewrites the credential file from scratch — the persist is handed ` +
        `${written}, not the CredentialJson.mergedCredentialJson result, so every foreign ` +
        `field (device_id, vendor keys) is dropped on each refresh (the 2026-07-18 audit ` +
        `shape grok/codex already fixed). The merge EXISTING in the file is not the ` +
        `invariant; the merge being what reaches the atomic write is.`,
    );
  }
  return problems;
}

function read(p: string): string | null {
  return existsSync(p) ? codeOnly(readFileSync(p, "utf8")) : null;
}

export const CORE_OK = "public fun mergedCredentialJson(onDisk: JsonObject?, replacements: JsonObject): JsonObject";
export const FRESH = "oauth.kimiAuthJson(attempt.tokens, clock())";
const MERGE = `val merged = CredentialJson.mergedCredentialJson(onDisk, ${FRESH})`;
export const KIMI_OPEN = ATOMIC_WRITE + `authPath, ${FRESH}.toString())`;
export const KIMI_OK = MERGE + "\n" + ATOMIC_WRITE + "authPath, merged.toString())";

/** A persist routed through a private forward — the PRODUCTION shape, and precisely the one the
 *  two dead literal spellings could never see, because the merge and the atomic write sit at
 *  different call sites. */
function viaForward(content: string, persist = "writeSecure"): string {
  return (
    `${persist}(authPath, ${content})\n` +
    `private fun ${persist}(path: Path, content: String) {\n` +
    `    ${ATOMIC_WRITE}path, content)\n` +
    `}`
  );
}

// Direction (b): the persist may go through a private forward under ANY name. The wall must be blind
// to that name — it was `writeSecure` for a year and reddening on the rename is the defect the HD-28
// re-anchor removed.
export const KIMI_WRAPPED = MERGE + "\n" + viaForward("merged.toString()", "persistCredentialFile");
// Direction (a): the hole the old `writeSecure(` anchor left wide open — a correctly MERGED write
// handed to a helper that never reaches the atomic 0600 primitive. Merge intact, world-readable
// window back. The old anchor passed this; this one must not.
export const KIMI_UNSAFE =
  MERGE + "\n" +
  "writeSecure(authPath, merged.toString())\n" +
  "private fun writeSecure(path: Path, content: String) { Files.writeString(path, content) }";
// THE REGRESSION, byte-for-byte the out-of-tree mutation that proved the two literal spellings dead
// (2026-08-18): the merge is still computed — so `mergedCredentialJson(` is still in the file, and
// the bare-identifier fallback is still satisfied — and the atomic write is handed the freshly-built
// six-key object anyway. This printed GREEN before the dataflow anchor.
export const KIMI_DEAD_MERGE = MERGE + "\n" + viaForward(`${FRESH}.toString()`);
// The same regression one step along: the merge lands in a local nobody persists while a DIFFERENT
// local carries the from-scratch object to the write. Pins that the trace follows the VALUE, not the
// presence of a `val merged` somewhere above.
export const KIMI_WRONG_LOCAL = `val fresh = ${FRESH}\n` + MERGE + "\n" + viaForward("fresh.toString()");
// A PURE RENAME — local AND forward renamed, merge still reaching the write. Must stay GREEN.
export const KIMI_RENAMED =
  `val credentialFile = CredentialJson.mergedCredentialJson(onDisk, ${FRESH})\n` +
  viaForward("credentialFile.toString()", "persistCredentials");
// The merge inlined at the write with no local at all — also GREEN. A local is one way to carry the
// value, never the invariant.
export const KIMI_INLINE = viaForward(`CredentialJson.mergedCredentialJson(onDisk, ${FRESH}).toString()`);
// A merge assignment AFTER the persist is not the provenance of the value already written. The old
// whole-file search accepted it merely because the same local name appeared eventually.
export const KIMI_POSTHOC_MERGE =
  viaForward("credentialFile.toString()", "persistCredentials") + "\n" +
  `val credentialFile = CredentialJson.mergedCredentialJson(onDisk, ${FRESH})`;
// A same-name merge in a closed sibling scope cannot define the value read by refresh(). The live
// outer value is fresh, so persisting it is still a rewrite even though a lexical decoy is merged.
export const KIMI_CROSS_SCOPE_MERGE =
  `val credentialFile = ${FRESH}\n` +
  `fun decoy() { val credentialFile = CredentialJson.mergedCredentialJson(onDisk, ${FRESH}) }\n` +
  "fun refresh() { persistCredentials(authPath, credentialFile.toString()) }\n" +
  "private fun persistCredentials(path: Path, content: String) {\n" +
  `    ${ATOMIC_WRITE}path, content)\n` +
  "}";
export const KIMI_PARAMETER_SHADOW =
  `val credentialFile = CredentialJson.mergedCredentialJson(onDisk, ${FRESH})\n` +
  "fun refresh(credentialFile: JsonObject) { " +
  "persistCredentials(authPath, credentialFile.toString()) }\n" +
  "private fun persistCredentials(path: Path, content: String) {\n" +
  `    ${ATOMIC_WRITE}path, content)\n` +
  "}";
export const KIMI_LAMBDA_SHADOW =
  `val credentialFile = CredentialJson.mergedCredentialJson(onDisk, ${FRESH})\n` +
  `fun refresh() { listOf(${FRESH}).map { credentialFile -> ` +
  "persistCredentials(authPath, credentialFile.toString()) } }\n" +
  "private fun persistCredentials(path: Path, content: String) {\n" +
  `    ${ATOMIC_WRITE}path, content)\n` +
  "}";
export const KIMI_TYPED_LAMBDA_SHADOW = KIMI_LAMBDA_SHADOW.replaceAll(
  "credentialFile ->", "credentialFile: JsonObject ->",
);
export const KIMI_UNRELATED_LAMBDA =
  `val credentialFile = CredentialJson.mergedCredentialJson(onDisk, ${FRESH})\n` +
  "fun refresh() { listOf(credentialFile).map { x -> x }; " +
  "persistCredentials(authPath, credentialFile.toString()) }\n" +
  "private fun persistCredentials(path: Path, content: String) {\n" +
  `    ${ATOMIC_WRITE}path, content)\n` +
  "}";
export const KIMI_NESTED_RESULT =
  `val credentialFile = run { CredentialJson.mergedCredentialJson(onDisk, ${FRESH}); ${FRESH} }\n` +
  viaForward("credentialFile.toString()", "persistCredentials");
export const KIMI_DROPPED_FORWARD =
  MERGE + "\nwriteSecure(authPath, merged.toString())\n" +
  "private fun writeSecure(path: Path, content: String) {\n" +
  `    ${ATOMIC_WRITE}path, ${FRESH}.toString())\n` +
  "}";
// Comments lie in both directions to a wall that follows a value; both are fed through code_only,
// exactly as the real file is. FAIL-OPEN: the merge exists only in a comment while the live local
// holds the from-scratch object.
export const KIMI_COMMENT_LIE = "// " + MERGE + `\nval merged = ${FRESH}\n` + viaForward("merged.toString()");
// FALSE-RED: a commented-out old from-scratch write beside the live merged one is not a second
// persist and must not redden a correct file.
export const KIMI_COMMENT_GHOST =
  MERGE + `\n// writeSecure(authPath, ${FRESH}.toString())\n` + viaForward("merged.toString()");

export function selftest(): number {
  const fails: string[] = [];

  const red = (label: string, kimi: string | null, core: string | null = CORE_OK): void => {
    if (detect(core, kimi).length === 0) {
      fails.push(`${label} must be RED`);
    }
  };
  const green = (label: string, kimi: string | null, core: string | null = CORE_OK): void => {
    if (detect(core, kimi).length > 0) {
      fails.push(`${label} must be GREEN, got ${pyRepr(detect(core, kimi))}`);
    }
  };

  red("from-scratch rewrite with no primitive", KIMI_OPEN, null);
  green("primitive + merged write", KIMI_OK);
  red("primitive present but kimi still rewriting", KIMI_OPEN);
  red("kimi merged but no shared primitive (a private fork can drift)", KIMI_OK, null);
  red("missing KimiAuthProvider.kt — never a vacuous pass", null);
  red("an unrecognized persist shape — never a vacuous pass", "class KimiAuthProvider");
  green("a merged write through a RENAMED private forward that still reaches the atomic write", KIMI_WRAPPED);
  red(
    "a merged write through a helper that SKIPS SecureFile.writeAtomic0600 — the merge is not " +
      "the only invariant",
    KIMI_UNSAFE,
  );
  // The four arms this wall lacked: the two literal FROM_SCRATCH spellings they replace could not
  // match the production call site at all, so the regression the wall is named for passed GREEN.
  red("THE REGRESSION — merge computed, from-scratch object persisted through the forward", KIMI_DEAD_MERGE);
  red("merge computed into a local nobody persists while another local carries the fresh object", KIMI_WRONG_LOCAL);
  green("a pure rename of the local AND the forward, merge still reaching the write", KIMI_RENAMED);
  green("the merge inlined at the write with no local at all", KIMI_INLINE);
  red("a same-name merge assignment that occurs only AFTER the persist", KIMI_POSTHOC_MERGE);
  red("a same-name merge assignment in a closed sibling scope", KIMI_CROSS_SCOPE_MERGE);
  red("a fresh function parameter shadowing an outer merged value", KIMI_PARAMETER_SHADOW);
  red("a fresh lambda parameter shadowing an outer merged value", KIMI_LAMBDA_SHADOW);
  red("a typed lambda parameter shadowing an outer merged value", KIMI_TYPED_LAMBDA_SHADOW);
  green("an unrelated lambda that merely reads the merged value", KIMI_UNRELATED_LAMBDA);
  red("a nested expression that calls merge but returns the fresh object", KIMI_NESTED_RESULT);
  red("a persist forward that discards its content parameter", KIMI_DROPPED_FORWARD);
  red(
    "a merge that exists only in a COMMENT above a live from-scratch local",
    codeOnly(KIMI_COMMENT_LIE),
  );
  green(
    "a commented-out old from-scratch write beside the live merged one",
    codeOnly(KIMI_COMMENT_GHOST),
  );

  if (fails.length > 0) {
    process.stdout.write("SH-10 SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write(
    "SH-10 SELFTEST OK — red on fresh/nested/shadowed values, posthoc or sibling merges, " +
      "content-dropping forwards, missing primitive/file, unsafe writes, and comment-only " +
      "merges; green only when the resolved merge value reaches the atomic write\n",
  );
  return 0;
}

export function main(): number {
  if (process.argv.includes("--selftest")) return selftest();
  const problems = detect(read(CORE), read(KIMI));
  if (problems.length > 0) {
    process.stdout.write("SH-10 WALL RED — the kimi credential refresh does not merge-and-atomically-persist:\n");
    for (const p of problems) process.stdout.write(`  · ${p}\n`);
    return 1;
  }
  process.stdout.write(
    "SH-10 WALL GREEN: kimi merges rotations onto the on-disk object via the shared primitive " +
      "and persists them through SecureFile.writeAtomic0600.\n",
  );
  return 0;
}

if (import.meta.main) {
  process.exit(main());
}
