#!/usr/bin/env bun
/** WALL for CX-11 — the loop guard must read Anthropic's STRUCTURED failure verdict, not only a
 *  Claude Code formatting string.
 *
 *  GAP (RED at authoring, 2026-08-10): `ToolResultBlock` did not declare `is_error`, so Anthropic's
 *  documented boolean was discarded on every dialect — a repo-wide grep for `is_error`/`isError`
 *  across `gateway/` returned zero hits. The only failure signal LoopGuard had was the literal
 *  `<tool_use_error>`, a Claude Code internal formatting detail with NO canary and NO drift alarm
 *  (contrast Compact.kt's markers, which have both). A wording change upstream silently disarms the
 *  circuit breaker for the 89-101x identical-failed-call pathology it exists to stop, and nothing
 *  fails — the guard just never arms again.
 *
 *  GREEN requires BOTH halves, and they are separate failures:
 *    1. THE FIELD IS PARSED — ToolResultBlock declares @SerialName("is_error"). Without this the
 *       structured signal never reaches any consumer, whatever LoopGuard does.
 *    2. THE GUARD PREFERS IT, WITH THE STRING AS FALLBACK — one expression, `isError ?: (marker)`.
 *       Both directions are wrong on their own:
 *         · reading is_error and DROPPING the marker fallback breaks every client that omits the
 *           field (today's Claude Code sends the marker), silently disarming the guard for them;
 *         · keeping the marker as an OR rather than a fallback re-arms on `is_error: false` results
 *           whose OUTPUT merely quotes the marker (a grep hit, a test log) — a false circuit-break
 *           that tells the model to stop doing something that worked.
 *       The elvis is what encodes "the client's structured verdict is authoritative, the string only
 *       answers when the client said nothing", so the wall pins the elvis, not the two tokens apart.
 *
 *  Every token below was measured at 0 occurrences in HEAD d0da545 and >=1 after the fix, so none can
 *  be satisfied by code that was already there for another reason.
 *
 *  EXIT 0 = closed. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6): the half-fixes are
 *  DERIVED FROM THE REAL SOURCES, deleting exactly one half at a time and asserting the mutant is red
 *  for THAT half's reason.
 *
 *  V4-154: converted to TypeScript (bun). Straight port; the derived half-fix controls are the
 *  load-bearing part and were transcribed one for one.
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

export const PATHS: Record<string, string> = {
  wire: "gateway/core/src/main/kotlin/splice/core/wire/ContentBlock.kt",
  guard: "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/LoopGuard.kt",
};

// key -> (token, why it is the thing that must exist)
export const REQUIRED: Record<string, [string, string][]> = {
  wire: [
    [
      '@SerialName("is_error")',
      "ToolResultBlock never declares Anthropic's structured failure field, so it is discarded " +
        "on every dialect and the guard has only a formatting string to go on",
    ],
  ],
  guard: [
    [
      "block.isError ?: (ERROR_MARKER in text)",
      "the guard does not prefer the structured verdict with the marker as FALLBACK — either " +
        "the string is still the only signal, or it now overrides an explicit is_error:false",
    ],
  ],
};

// --- CODE, NOT MENTIONS -------------------------------------------------------------------------
// Adversarial review (2026-08-10) proved every wall in this campaign that matched raw file text was
// satisfiable by a COMMENT or an IMPORT naming the token. Concretely: the CX-02 wall graded a tree
// GREEN where the Responses call body had been replaced by `return system.orEmpty()`, because the
// KDoc above it still said "withCompactDirective"; and the CX-11 wall graded GREEN with its required
// expression moved into a `// TODO(next):` comment and the pre-fix branch restored. Both are exactly
// the regression these walls exist to catch. Tokens are therefore matched against code with comments
// and imports removed — a mention is not a wiring.
const BLOCK_COMMENT = /\/\*[\s\S]*?\*\//g;
const LINE_COMMENT = /\/\/.*?$/gm;
const IMPORT_LINE = /^import .*$/gm;

export function codeOnly(text: string | null): string | null {
  if (text === null) return null;
  let stripped = text.replace(BLOCK_COMMENT, "");
  stripped = stripped.replace(LINE_COMMENT, "");
  return stripped.replace(IMPORT_LINE, "");
}

/** Pure detection. No I/O — the selftest feeds it derived sources directly. */
export function detect(sources: Record<string, string | null>): string[] {
  const problems: string[] = [];
  for (const key of Object.keys(REQUIRED)) {
    const text = sources[key] ?? null;
    if (text === null) {
      problems.push(`${key} source missing — refusing to pass vacuously`);
      continue;
    }
    for (const [token, why] of REQUIRED[key]) {
      if (!text.includes(token)) {
        problems.push(`${key}: ${why} (missing \`${token}\`)`);
      }
    }
  }
  return problems;
}

export function load(): Record<string, string | null> {
  const out: Record<string, string | null> = {};
  for (const key of Object.keys(PATHS)) {
    const p = resolve(ROOT, PATHS[key]);
    out[key] = existsSync(p) ? codeOnly(readFileSync(p, "utf8")) : null;
  }
  return out;
}

// The pre-fix shape, kept as a cheap synthetic floor: literally true of both files at HEAD d0da545.
export const PREFIX_SHAPE: Record<string, string> = {
  wire: '@SerialName("tool_use_id") val toolUseId: String = ""',
  guard: "if (ERROR_MARKER in text) {",
};

function selftest(): number {
  const fails: string[] = [];
  const live = load();

  if (detect(live).length > 0) {
    fails.push(`the real sources must be GREEN before half-fixes can be derived: ${pyRepr(detect(live))}`);
  } else {
    // Derived controls: delete exactly one required token from the REAL file, keeping every
    // other line — comments, helpers and the pre-existing marker constant all stay.
    for (const key of Object.keys(REQUIRED)) {
      for (const [token] of REQUIRED[key]) {
        const mutant = { ...live, [key]: (live[key] ?? "").replaceAll(token, "") };
        const problems = detect(mutant);
        if (!problems.some((p) => p.startsWith(`${key}:`) && p.includes(token))) {
          fails.push(
            `deleting \`${token}\` from ${key} must be RED for its own reason, got ${pyRepr(problems)}`,
          );
        }
      }
    }
  }

  if (detect({ ...PREFIX_SHAPE }).length === 0) {
    fails.push("the pre-fix shape must be RED");
  }

  for (const key of Object.keys(REQUIRED)) {
    const partial = { ...live, [key]: PREFIX_SHAPE[key] };
    if (detect(partial).length === 0) {
      fails.push(`a gap left open in ${key} alone must be RED`);
    }
    const missing = { ...live, [key]: null };
    if (detect(missing).length === 0) {
      fails.push(`a missing ${key} file must be RED, never a vacuous pass`);
    }
  }

  if (fails.length > 0) {
    process.stdout.write("CX-11 SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write(
    "CX-11 SELFTEST OK — red on the pre-fix shape, on either half left open, on a missing " +
      "file, and — derived from the REAL sources, one token at a time — on a tree that keeps " +
      "every comment, constant and helper but drops one half of the structured-verdict read.\n",
  );
  return 0;
}

function main(): number {
  if (process.argv.includes("--selftest")) return selftest();
  const problems = detect(load());
  if (problems.length > 0) {
    process.stdout.write("CX-11 WALL RED — the loop guard depends on an uncanaried formatting string:\n");
    for (const p of problems) process.stdout.write(`  · ${p}\n`);
    return 1;
  }
  process.stdout.write(
    "CX-11 WALL GREEN: is_error is parsed and the guard prefers it, keeping the marker as " +
      "the fallback for clients that omit the field.\n",
  );
  return 0;
}

if (import.meta.main) {
  process.exit(main());
}
