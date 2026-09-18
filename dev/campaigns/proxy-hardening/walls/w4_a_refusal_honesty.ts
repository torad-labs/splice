#!/usr/bin/env bun
/** WALL for W4-A (collapses CX-07 + CX-08) — a backend-sent refusal/pause/hard-truncation signal
 *  must reach the client as an honest failure, not as a clean success.
 *
 *  V4-154: converted from Python to TypeScript (bun). Unlike inf_01/inf_02 this is NOT a mechanical
 *  substitution — it carries a token table, a comment stripper and a derived-control selftest — so it
 *  was read in full and transcribed rather than derived. What had to survive is the whole contract
 *  the runner reads: the exit codes, the detection semantics, and the LAST LINE of each output.
 *
 *  THE CHAIN IS CHECKED AT BOTH ENDS, which is the shape the derived siblings should inherit. The
 *  arm tokens below prove each dialect READS the signal and hands it to a FailureCause; WIRE_REQUIRED
 *  proves those causes still RESOLVE to the verdicts this item promises. One end moving while the
 *  other stays is the failure that matters, and only holding both can see it — V4-117 moved the arm
 *  spelling AND kept the mappings, which is why the behaviour survived a rename.
 */
import { existsSync } from "node:fs";
import { resolve } from "node:path";

const ROOT = resolve(import.meta.dir, "../../../..");

const PASS = [
  resolve(ROOT, "gateway/dialect-anthropic-passthrough/src/main/kotlin/splice/dialect/passthrough/PassthroughTerminalState.kt"),
];
const CHAT = [
  resolve(ROOT, "gateway/dialect-openai-chat/src/main/kotlin/splice/dialect/chat/ChatProseFold.kt"),
  resolve(ROOT, "gateway/dialect-openai-chat/src/main/kotlin/splice/dialect/chat/ChatEventRouter.kt"),
  resolve(ROOT, "gateway/dialect-openai-chat/src/main/kotlin/splice/dialect/chat/ChatTerminalState.kt"),
];
const RESP = [
  resolve(ROOT, "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesEventReducer.kt"),
  resolve(ROOT, "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesTerminalBackfill.kt"),
  resolve(ROOT, "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesTurnState.kt"),
  resolve(ROOT, "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesTerminalDecision.kt"),
];
const PATHS: Record<string, string | string[]> = { passthrough: PASS, chat: CHAT, responses: RESP };

/** The far end of the chain. A SECOND, INDEPENDENT pair from the arm tokens in REQUIRED — the pair is
 *  what makes this a chain check rather than two greps. */
const WIRE = resolve(ROOT, "gateway/core/src/main/kotlin/splice/core/turn/WireType.kt");
const WIRE_REQUIRED = [
  "FailureCause.MODEL_REFUSED to ErrorType.API_ERROR",
  "FailureCause.UPSTREAM_STATUS_5XX to ErrorType.OVERLOADED",
];

/** The two halves are NOT the same shape, and reading them as if they were is what the agreement
 *  oracle caught on this file's first run. `carriers` is a list of ENTRIES — a string, or an array of
 *  equivalent spellings of the SAME call site — and it is ALL-OF across entries, ANY-OF within one.
 *  `conversion` is a FLAT list of equivalent spellings of ONE invariant, ANY-OF across the whole
 *  list. Writing conversion as a list-of-lists parses fine and behaves differently, which is exactly
 *  the class of silent port error this file's control exists to catch. */
type Entry = string | string[];
type Dialect = { carriers: Entry[]; conversion: string[] };

const REQUIRED: Record<string, Dialect> = {
  passthrough: {
    carriers: [
      '"refusal" -> FailureCause.MODEL_REFUSED to',
      'FailureCause.UPSTREAM_STATUS_5XX to "backend paused',
      ['"model_context_window_exceeded" -> ErrorType.API_ERROR to',
       '"model_context_window_exceeded" -> UpstreamFailureClassifier.overflowFailure('],
    ],
    conversion: [
      "else -> failureRules.stopReasonFailure(reason)",
      "else -> stopReasonFailure(reason)",
    ],
  },
  chat: {
    carriers: [
      'strIfString(obj["refusal"])',
      ["appendRefusal(refusalBuf, delta, isDelta = true)",
       "appendRefusal(terminal.refusalBuf, delta, isDelta = true)"],
      ["appendRefusal(refusalBuf, msg, isDelta = false)",
       "appendRefusal(terminal.refusalBuf, msg, isDelta = false)"],
    ],
    conversion: [
      "refusalBuf.isNotBlank() -> TurnOutcome.Failure",
      "refusalBuf.isNotEmpty() -> TurnOutcome.Failure",
    ],
  },
  responses: {
    carriers: [
      ['"response.refusal.delta", "response.refusal.done" -> ops.addRefusal(this, evt)',
       '"response.refusal.delta", "response.refusal.done" -> addRefusal(evt)',
       '"response.refusal.delta", "response.refusal.done" -> state.addRefusal(evt)'],
      'strIfString(if (isDelta) obj["delta"] else obj["refusal"])',
      ['if (JsonScalars.strOrEmpty(obj["type"]) == "refusal") ops.addRefusal(reducer, obj)',
       'if (strOrEmpty(obj["type"]) == "refusal") ops.addRefusal(reducer, obj)',
       'if (strOrEmpty(obj["type"]) == "refusal") reducer.addRefusal(obj)',
       'if (JsonScalars.strOrEmpty(obj["type"]) == "refusal") state.addRefusal(obj)'],
    ],
    conversion: [
      "?: refusalFailure(reducer)",
      "?: refusalFailure(state)",
    ],
  },
};

const NO_VERDICT = "reads the signal but never turns it into a provider-reported Failure";

/** Equivalent spellings of ONE call site. A bare string is its own only spelling. */
function alts(entry: Entry): string[] {
  return typeof entry === "string" ? [entry] : entry;
}

/** Pure detection. No I/O — the selftest feeds it derived sources directly. */
export function detect(sources: Record<string, string | null>): string[] {
  const problems: string[] = [];
  for (const [name, { carriers, conversion }] of Object.entries(REQUIRED)) {
    const text = sources[name];
    if (text === null || text === undefined) {
      problems.push(`${name} translator missing — refusing to pass vacuously`);
      continue;
    }
    // ALL-OF over carriers, ANY-OF within one carrier's spellings.
    const missing = carriers
      .filter((c) => !alts(c).some((a) => text.includes(a)))
      .map((c) => alts(c).join(" | "));
    if (missing.length > 0) {
      problems.push(
        `${name} never reads the backend's refusal/non-clean signal (${missing.join(", ")}) ` +
          "— the turn ends as a clean success",
      );
      continue;
    }
    const unwired = conversion.some((c) => text.includes(c)) ? [] : conversion;
    if (unwired.length > 0) {
      problems.push(
        `${name} ${NO_VERDICT} (${unwired.join(", ")}) — a read with no verdict is not a gate`,
      );
    }
  }
  const wire = sources["wire"];
  if (wire === null || wire === undefined) {
    problems.push("WireType.kt missing — refusing to pass vacuously on the mapping end");
  } else {
    for (const mapping of WIRE_REQUIRED) {
      if (!wire.includes(mapping)) {
        problems.push(
          `the refusal/pause verdict mapping changed: '${mapping}' is gone from ` +
            "WireType.kt — the client is now told something other than what W4-A promises",
        );
      }
    }
  }
  return problems;
}

const BLOCK_COMMENT = /\/\*[\s\S]*?\*\//g;
const LINE_COMMENT = /\/\/.*$/gm;
const IMPORT_LINE = /^import .*$/gm;

/** A mention is not a wiring. Without this the wall is satisfiable by a COMMENT: delete the verdict
 *  call site, leave `// TODO(next): restore \`...\`` behind, and the wall still reads GREEN while a
 *  backend refusal reaches the client as a clean success. */
function codeOnly(text: string | null): string | null {
  if (text === null) return null;
  return text.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "").replace(IMPORT_LINE, "");
}

async function readOneAsync(p: string): Promise<string | null> {
  if (!existsSync(p)) return null;
  return codeOnly(await Bun.file(p).text());
}

/** A source is one path or a LIST of paths, concatenated in order. ANY missing file in a list makes
 *  the whole key null — a deleted file must never go quiet by dropping out silently. */
async function readSource(source: string | string[]): Promise<string | null> {
  if (Array.isArray(source)) {
    const texts = await Promise.all(source.map(readOneAsync));
    return texts.some((t) => t === null) ? null : texts.join("\n");
  }
  return readOneAsync(source);
}

async function live(): Promise<Record<string, string | null>> {
  const sources: Record<string, string | null> = {};
  for (const [name, p] of Object.entries(PATHS)) sources[name] = await readSource(p);
  // `readOneAsync`, not `readSource`, because it strips comments — WireType's mapping table sits
  // under a KDoc that names the very verdicts being checked, so without this the wall would pass on
  // the prose after the mapping itself had been deleted.
  sources["wire"] = await readOneAsync(WIRE);
  return sources;
}

/** The pre-fix shape, kept as a cheap synthetic floor alongside the derived cases. */
const PREFIX_SHAPE: Record<string, string> = {
  passthrough: "else -> Unit // stop_sequence / end_turn / other",
  chat: "content reasoning_content tool_calls",
  responses: "else -> Unit",
};

/** THE control that matters: mutate the REAL sources, one dialect's verdict at a time. */
function selftestDerived(fails: string[], liveSources: Record<string, string | null>): void {
  if (detect(liveSources).length > 0) {
    fails.push(
      "the real sources must be GREEN before a half-fix can be derived from them; " +
        `got ${JSON.stringify(detect(liveSources))}`,
    );
    return;
  }
  for (const [one, { conversion }] of Object.entries(REQUIRED)) {
    const mutant = { ...liveSources };
    let text = liveSources[one];
    if (text === null || text === undefined) {
      fails.push(`cannot derive a ${one} half-fix: its sources were unreadable`);
      return;
    }
    // ANY-OF lists hold equivalent spellings, so only the spelling actually PRESENT can be deleted.
    const present = conversion.filter((token) => text!.includes(token));
    if (present.length === 0) {
      fails.push(`cannot derive a ${one} half-fix: none of ${JSON.stringify(conversion)} is in the real source`);
      return;
    }
    for (const token of present) text = text!.replace(token, "");
    mutant[one] = text;
    const problems = detect(mutant);
    if (problems.length === 0) {
      fails.push(`${one} with ONLY its verdict call site deleted must be RED`);
    } else if (!problems.some((p) => p.startsWith(one) && p.includes(NO_VERDICT))) {
      fails.push(`${one} half-fix must be red for the NO-VERDICT reason, got ${JSON.stringify(problems)}`);
    }
  }
}

async function selftest(): Promise<number> {
  const fails: string[] = [];
  const liveSources = await live();
  selftestDerived(fails, liveSources);

  if (detect(PREFIX_SHAPE).length === 0) fails.push("the pre-fix shape must be RED");
  for (const one of Object.keys(REQUIRED)) {
    const partial = { ...liveSources, [one]: PREFIX_SHAPE[one] };
    if (detect(partial).length === 0) fails.push(`a gap left open in ${one} alone must be RED`);
    const missing = { ...liveSources, [one]: null };
    if (detect(missing).length === 0) {
      fails.push(`a missing ${one} file must be RED, never a vacuous pass`);
    }
  }

  if (fails.length > 0) {
    process.stdout.write("W4-A SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write(
    "W4-A SELFTEST OK — red on the pre-fix shape, on any single dialect left open, on a " +
      "missing file, and — derived from the REAL sources, one dialect at a time — on a tree " +
      "that keeps every buffer, helper and comment but deletes the verdict call site.\n",
  );
  return 0;
}

async function main(): Promise<number> {
  if (process.argv.includes("--selftest")) return selftest();
  const problems = detect(await live());
  if (problems.length > 0) {
    process.stdout.write("W4-A WALL RED — a backend-sent refusal can still reach the client as a clean success:\n");
    for (const p of problems) process.stdout.write(`  · ${p}\n`);
    return 1;
  }
  process.stdout.write(
    "W4-A WALL GREEN: all three dialects read the backend's refusal/non-clean terminal signal " +
      "and convert it to a providerReported Failure.\n",
  );
  return 0;
}

if (import.meta.main) {
  process.exit(await main());
}
