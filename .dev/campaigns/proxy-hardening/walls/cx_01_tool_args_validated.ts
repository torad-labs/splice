#!/usr/bin/env bun
/** WALL for CX-01 — tool-call arguments must be validated as JSON before a turn is Success.
 *
 *  GAP (RED at authoring, 2026-08-08): both OpenAI-family translators stream argument text straight
 *  to input_json_delta and close the block with no parse. A backend that truncates arguments
 *  mid-string but still emits a terminal produces a Success carrying a corrupt tool_use — Claude
 *  Code then hard-errors on parse or dispatches the tool with garbage. An opened tool with zero arg
 *  deltas ships input:{} the same way.
 *
 *  GREEN requires BOTH translators to:
 *    1. accumulate the argument text (not just stream it) and parse it at block close;
 *    2. latch a translator-level failure (toolArgsInvalid) that terminalOutcome turns into a
 *       Failure instead of a Success.
 *
 *  THE THREE REQUIREMENTS ANCHOR ON CALL SITES, NOT ON BARE IDENTIFIERS (repair, 2026-08-17). Both
 *  halves of this wall were once keyed on the two bare tokens `toolArgsInvalid` and
 *  `parseToJsonElement`, and both halves were satisfiable by code that is not the invariant: a field
 *  DECLARATION and an unrelated parser each matched, so deleting the work left the wall GREEN. The
 *  responses half was repaired first by narrowing its file list to the carrier chain; the note on that
 *  fix recorded what the list alone could NOT close, and this is it — with the tokens still bare,
 *  deleting only the latch assignment stayed green, because the terminal branch's own read of the
 *  field kept the identifier alive.
 *
 *  MEASURED on the chat half at 1f77412, which is what forced this repair: deleting BOTH the latch
 *  assignment (ChatStreamTranslator.kt) and the `toolArgsInvalid != null -> TurnOutcome.Failure` arm
 *  (ChatTerminalState.kt) — the entire CX-01 L3 invariant, so a truncated tool call closes as a clean
 *  Success carrying a malformed tool_use — left this wall GREEN, because both bare tokens were still
 *  satisfied inside ChatToolCalls.kt (the field declaration, and the parse inside invalidArgsReason).
 *
 *  So each of the three requirements is now a LITERAL CALL SITE that exists only because that step is
 *  wired: the parse inside the reason helper, the latch assignment at terminal, and the branch that
 *  turns the latch into a provider-reported Failure. Deleting any one of them takes the wall red for
 *  that step's own reason. This mirrors w4_a's repair round 2, whose lesson was the same one: a token
 *  must be satisfiable only by the file that does the work.
 *
 *  Both halves match LITERAL SOURCE SUBSTRINGS, so a pure-style migration can break a token while the
 *  invariant is intact — the remedy, as in w4_a, is that an entry is a TUPLE of equivalent spellings
 *  of the SAME call site, satisfied by any one of them. That is not a relaxation: every step still has
 *  to be matched by something, each spelling still names a whole call site, and deleting the step
 *  removes every spelling at once.
 *
 *  EXIT 0 = validated. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6): synthetic floors for
 *  the pre-fix shape and the vacuity guard, PLUS the control that matters — mutate the REAL sources,
 *  one requirement at a time, and assert the mutant is red for that requirement's own reason.
 *
 *  V4-154: converted to TypeScript (bun). Straight port of the file-list and ANY-OF mechanisms; the
 *  derived control is exercised by the CLI drive, since it reads the live sources.
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
// LIST, not a single file (HD-24 decomposition, 2026-08-17; repointed to the carrier chain by the
// repair above): the CX-01 chat chain is entry point + latch (ChatStreamTranslator), accumulate +
// parse (ChatToolCalls) and convert-to-Failure (ChatTerminalState). The single-file repoint that
// preceded this named only ChatToolCalls.kt, which left both other steps unread — and moved the
// vacuity guard off the translator, so deleting ChatStreamTranslator.kt no longer made the key None.
const CHAT = [
  resolve(ROOT, "dialects/openai-chat/src/main/kotlin/splice/dialect/chat/ChatStreamTranslator.kt"),
  resolve(ROOT, "dialects/openai-chat/src/main/kotlin/splice/dialect/chat/ChatToolCalls.kt"),
  resolve(ROOT, "dialects/openai-chat/src/main/kotlin/splice/dialect/chat/ChatTerminalState.kt"),
];
// LIST, not a single file (HD-24 decomposition, 2026-08-17): a target may move the validation latch
// and its parser to siblings. Every path must exist or the whole key reads as missing (vacuity
// guard unchanged — see the file-list mechanism note in cx_09/w4_a).
//
// THE LIST NAMES THE IMPLEMENTATION, NOT THE PACKAGE (repair, 2026-08-17). The first cut of this
// list carried ResponsesTurnState.kt and ResponsesToolSearchParse.kt, and BOTH tokens were then
// satisfied by code that is not CX-01: `toolArgsInvalid` matched only the bare field DECLARATION,
// and `parseToJsonElement` matched only the tool_search_call query parser. Measured: deleting the
// latch assignment AND invalidToolArgsReason outright left this wall GREEN. Same lesson w4_a
// recorded in its repair round 2 — a token must be satisfiable only by the file that does the work.
// The four files below are exactly the CX-01 carrier chain: entry point, accumulate+latch, parse,
// convert-to-Failure.
const RESP = [
  resolve(ROOT, "dialects/openai-responses/src/main/kotlin/splice/dialect/responses/stream/ResponsesStreamTranslator.kt"),
  resolve(ROOT, "dialects/openai-responses/src/main/kotlin/splice/dialect/responses/stream/ResponsesItemFold.kt"),
  resolve(ROOT, "dialects/openai-responses/src/main/kotlin/splice/dialect/responses/stream/ResponsesFrameParse.kt"),
  resolve(ROOT, "dialects/openai-responses/src/main/kotlin/splice/dialect/responses/stream/ResponsesTerminalDecision.kt"),
];
export const PATHS: Record<string, string[]> = { chat: CHAT, responses: RESP };

// Per dialect, the three steps of the CX-01 chain, in wire order. The value is the call site that
// exists ONLY because that step is wired — or a TUPLE of equivalent spellings of that one call site
// (ANY-OF, see `alts`). The step list itself stays ALL-OF: no step is optional.
export const REQUIRED: Record<string, Record<string, string | string[]>> = {
  chat: {
    "parses the accumulated args": "Json.parseToJsonElement(text)",
    "latches toolArgsInvalid at terminal":
      "if (toolCalls.toolArgsInvalid == null) toolCalls.toolArgsInvalid = toolCalls.firstInvalidToolArgs()",
    "turns the latch into a provider-reported Failure":
      "toolCalls.toolArgsInvalid != null -> TurnOutcome.Failure",
  },
  responses: {
    "parses the accumulated args": "Json.parseToJsonElement(text)",
    "latches toolArgsInvalid at terminal":
      "if (state.toolArgsInvalid == null) state.toolArgsInvalid = frames.invalidToolArgsReason(",
    "turns the latch into a provider-reported Failure": "?: state.toolArgsInvalid?.let {",
  },
};

const MISSING = "translator missing — refusing to pass vacuously";

/** Equivalent spellings of ONE call site. A bare string is its own only spelling. */
export function alts(entry: string | string[]): string[] {
  return typeof entry === "string" ? [entry] : entry;
}

/** Pure detection. No I/O — the selftest feeds it derived sources directly. */
export function detect(sources: Record<string, string | null>): string[] {
  const problems: string[] = [];
  for (const name of Object.keys(REQUIRED)) {
    const text = sources[name] ?? null;
    if (text === null) {
      problems.push(`${name} ${MISSING}`);
      continue;
    }
    for (const step of Object.keys(REQUIRED[name])) {
      const entry = REQUIRED[name][step];
      const a = alts(entry);
      if (!a.some((x) => text.includes(x))) {
        problems.push(
          `${name} translator never ${step} (${a.join(" | ")}) — a truncated ` +
            "tool call still closes as a Success with corrupt JSON",
        );
      }
    }
  }
  return problems;
}

const BLOCK_COMMENT = /\/\*[\s\S]*?\*\//g;
const LINE_COMMENT = /\/\/.*?$/gm;
const IMPORT_LINE = /^import .*$/gm;

/** A mention is not a wiring. Without this the wall is satisfiable by a COMMENT: delete the
 *  real call site, leave `// FIXME: reinstate \`...\`` behind, and every token still matches while
 *  the invariant is gone. Proven against this file's own sources before the stripper landed. The
 *  file list widening (one file -> three) made the surface larger, since any listed sibling's
 *  comments counted too. Same stripper cx_02/cx_09/cx_18 already carry. */
export function codeOnly(text: string | null): string | null {
  if (text === null) return null;
  let stripped = text.replace(BLOCK_COMMENT, "");
  stripped = stripped.replace(LINE_COMMENT, "");
  return stripped.replace(IMPORT_LINE, "");
}

function read(p: string): string | null {
  return existsSync(p) ? codeOnly(readFileSync(p, "utf8")) : null;
}

/** Concatenate a key's file list. ANY missing file makes the whole key null — a deleted file
 *  must never go quiet by dropping out of the concatenation silently. */
function readAll(paths: string[]): string | null {
  const texts = paths.map(read);
  if (texts.some((t) => t === null)) return null;
  return texts.filter((t) => t !== null).join("\n");
}

export function live(): Record<string, string | null> {
  const out: Record<string, string | null> = {};
  for (const name of Object.keys(PATHS)) out[name] = readAll(PATHS[name]);
  return out;
}

// The pre-fix shape, kept as a cheap synthetic floor alongside the derived cases below: none of the
// three call sites is present, which is literally true of both translators at the authoring HEAD.
export const OPEN = "streams args to input_json_delta, closes the block, no parse";

function selftestSynthetic(fails: string[]): void {
  const bothOpen: Record<string, string | null> = { chat: OPEN, responses: OPEN };
  if (detect(bothOpen).length === 0) {
    fails.push("no-validation shape must be RED");
  }
  for (const one of Object.keys(REQUIRED)) {
    const lopsided = { ...bothOpen };
    lopsided[one] = Object.keys(REQUIRED[one])
      .map((step) => alts(REQUIRED[one][step])[0])
      .join("\n");
    if (detect(lopsided).length === 0) {
      fails.push(`only ${one} validated must still be RED`);
    }
    const vacuous = { ...lopsided, [one]: null };
    if (!detect(vacuous).some((p) => p.includes(MISSING))) {
      fails.push(`a missing ${one} file must be RED, never a vacuous pass`);
    }
  }
}

/** THE control that matters: mutate the REAL sources, one dialect's step at a time. A
 *  hand-written fixture is what let both halves of this wall report OK while the tree they guarded
 *  had the invariant deleted. */
function selftestDerived(fails: string[], liveSources: Record<string, string | null>): void {
  if (detect(liveSources).length > 0) {
    fails.push(
      "the real sources must be GREEN before a mutant can be derived from them; " +
        `got ${pyRepr(detect(liveSources))}`,
    );
    return;
  }
  for (const one of Object.keys(REQUIRED)) {
    for (const step of Object.keys(REQUIRED[one])) {
      let text = liveSources[one] ?? "";
      for (const spelling of alts(REQUIRED[one][step])) {
        text = text.replaceAll(spelling, "");
      }
      const problems = detect({ ...liveSources, [one]: text });
      if (!problems.some((p) => p.includes(one) && p.includes(step))) {
        fails.push(
          `deleting ${one}'s '${step}' call site must be RED for that step; got ${pyRepr(problems)}`,
        );
      }
    }
  }
}

function selftest(): number {
  const fails: string[] = [];
  selftestSynthetic(fails);
  selftestDerived(fails, live());
  if (fails.length > 0) {
    process.stdout.write("CX-01 SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write(
    "CX-01 SELFTEST OK — red on the no-validation shape, on a one-sided fix, on a missing " +
      "file, and on the REAL sources with any one of the six call sites deleted\n",
  );
  return 0;
}

function main(): number {
  if (process.argv.includes("--selftest")) return selftest();
  const problems = detect(live());
  if (problems.length > 0) {
    process.stdout.write("CX-01 WALL RED — tool-call arguments are not validated before Success:\n");
    for (const p of problems) process.stdout.write(`  · ${p}\n`);
    return 1;
  }
  process.stdout.write("CX-01 WALL GREEN: both translators parse accumulated tool args and fail a corrupt tool call.\n");
  return 0;
}

if (import.meta.main) {
  process.exit(main());
}
