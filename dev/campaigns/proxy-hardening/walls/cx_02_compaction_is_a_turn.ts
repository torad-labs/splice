#!/usr/bin/env bun
/** WALL for CX-02 — a compaction is built EXACTLY like a turn, in every dialect.
 *
 *  LAW (operator, 2026-09-05): the compaction request HAS TO USE THE SAME MODEL, THE SAME REASONING,
 *  THE SAME TOOLS, EVERYTHING as the session. The backend's prompt cache is an exact-prefix match, so
 *  every compact-only reshaping a request builder does (a directive appended to instructions/system,
 *  tools or tool_choice stripped, tool results folded, images dropped, an effort pin) moves the prefix
 *  from token zero and the most expensive turn class there is reads the whole transcript cold (perf
 *  rows 2026-09-05: compact cached_tokens=0 on every model, on every dialect).
 *
 *  This wall REPLACES the earlier CX-02 wall, which required the opposite (a shared COMPACT MODE
 *  directive emitted by all three dialects). That doctrine produced the miss.
 *
 *  RED when a request builder's CODE (comments and imports stripped) names any compact-shaping token,
 *  or when a dialect's builder test no longer carries the byte-identity canary — the test that builds
 *  one body as a turn and as a compaction and asserts the request bytes are equal.
 *
 *  EXIT 0 = GREEN. EXIT 1 = RED. --selftest = positive controls.
 *
 *  V4-154: converted to TypeScript (bun). The line scanner strips STRING LITERALS before testing the
 *  compact-read pattern, so a message containing the word compact is not a read of the flag; that
 *  replaces Python's re.sub with a global JS regex, and the handoff exemption is checked against the
 *  UNSTRIPPED line exactly as the original does.
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

// The request-builder surface per dialect: every file where a compaction turn is shaped for the wire.
export const PATHS: Record<string, string[]> = {
  "openai-responses": [
    "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesRequestBuilder.kt",
    "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesInputBuilder.kt",
    "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesInputTools.kt",
    "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesToolPlan.kt",
    "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesLite.kt",
    "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesRequestAssembler.kt",
    "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesReasoningKnobs.kt",
    "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesTurnOptions.kt",
  ],
  "openai-chat": [
    "gateway/dialect-openai-chat/src/main/kotlin/splice/dialect/chat/ChatRequestBuilder.kt",
  ],
  "anthropic-passthrough": [
    "gateway/dialect-anthropic-passthrough/src/main/kotlin/splice/dialect/passthrough/PassthroughRequestBuilder.kt",
    "gateway/dialect-anthropic-passthrough/src/main/kotlin/splice/dialect/passthrough/PassthroughThinking.kt",
  ],
};

// Any of these in a builder's CODE is a compact-only reshaping of the request — the exact class
// this wall exists to keep out. `opts.compact` / `meta.compact` are allowed ONLY on the line that
// hands the flag to TurnMeta (the response side); a builder that reads the flag anywhere else is
// shaping the request on it.
export const FORBIDDEN_TOKENS = [
  "withCompactDirective",
  "compactDirective",
  "CompactInstructions",
  "compactAwareInstructions",
  "compactAwareSystem",
  "compactEffortPin",
  "compactEffort",
  "COMPACT MODE",
];
const COMPACT_READ = /\b(?:opts|meta)\.compact\b|\bcompact\b\s*\)|!compact\b|\(compact\)|if \(compact\)/;
const COMPACT_HANDOFF = /compact\s*=\s*(?:opts\.)?compact\b/;

export const CANARY_TESTS: Record<string, string> = {
  "openai-responses": "gateway/dialect-openai-responses/src/test/kotlin/ResponsesRequestBuilderTest.kt",
  "openai-chat": "gateway/dialect-openai-chat/src/test/kotlin/ChatRequestBuilderTest.kt",
  "anthropic-passthrough":
    "gateway/dialect-anthropic-passthrough/src/test/kotlin/PassthroughRequestBuilderTest.kt",
};
export const CANARY_TOKEN = "compaction is built byte-identical to a turn";

const BLOCK_COMMENT = /\/\*[\s\S]*?\*\//g;
const LINE_COMMENT = /\/\/.*?$/gm;
const IMPORT_LINE = /^import .*$/gm;
const STRING = /"(?:\\.|[^"\\])*"/g;

export function codeOnly(text: string | null): string | null {
  if (text === null) return null;
  let stripped = text.replace(BLOCK_COMMENT, "");
  stripped = stripped.replace(LINE_COMMENT, "");
  return stripped.replace(IMPORT_LINE, "");
}

/** Lines that READ the compact flag other than to hand it to TurnMeta. */
export function compactReads(code: string): string[] {
  const hits: string[] = [];
  for (const line of code.split("\n")) {
    if (COMPACT_HANDOFF.test(line)) continue;
    if (COMPACT_READ.test(line.replace(STRING, '""'))) hits.push(line.trim());
  }
  return hits;
}

export function detect(sources: Record<string, string | null>): [string[], string] {
  const absent = Object.keys(sources).filter((k) => sources[k] === null);
  if (absent.length > 0) {
    return [
      [
        `file not found: ${absent.slice().sort().join(", ")} — a builder moved; refusing to pass vacuously`,
      ],
      "inconclusive",
    ];
  }
  const problems: string[] = [];
  for (const dialect of Object.keys(PATHS)) {
    const code = sources[dialect] ?? "";
    const found = FORBIDDEN_TOKENS.filter((t) => code.includes(t)).sort();
    if (found.length > 0) {
      problems.push(
        `${dialect} shapes the compaction request (${found.join(", ")}) — a compaction ` +
          "must be built byte-identical to a turn or the prompt cache misses the transcript",
      );
    }
    const reads = compactReads(code);
    if (reads.length > 0) {
      problems.push(
        `${dialect} reads the compact flag while building the request: ` + reads.slice(0, 3).join(" | "),
      );
    }
  }
  const missing = Object.keys(CANARY_TESTS)
    .filter((k) => !(sources[`canary:${k}`] ?? "").includes(CANARY_TOKEN))
    .sort();
  if (missing.length > 0) {
    problems.push(
      `the byte-identity canary test is gone for ${missing.join(", ")} — without it a ` +
        "builder can be re-shaped with nothing red",
    );
  }
  return [problems, `${Object.keys(PATHS).length} dialects checked`];
}

function readSource(rels: string[]): string | null {
  const texts = rels.map((r) => {
    const p = resolve(ROOT, r);
    return existsSync(p) ? readFileSync(p, "utf8") : null;
  });
  if (texts.some((t) => t === null)) {
    return null;
  }
  return codeOnly(texts.filter((t) => t !== null).join("\n"));
}

export function load(): Record<string, string | null> {
  const out: Record<string, string | null> = {};
  for (const k of Object.keys(PATHS)) {
    out[k] = readSource(PATHS[k]);
  }
  for (const key of Object.keys(CANARY_TESTS)) {
    const p = resolve(ROOT, CANARY_TESTS[key]);
    out[`canary:${key}`] = existsSync(p) ? readFileSync(p, "utf8") : null;
  }
  return out;
}

export const CLEAN =
  "val instructions = body.system.orEmpty()\nval meta = TurnMeta(compact = opts.compact)\n";
export const SHAPED = "val instructions = if (opts.compact) withCompactDirective(system) else system\n";
export const STRIPPED = "val emitTools = quirks.supportsTools && !compact && body.tools.isNotEmpty()\n";
export const PINNED = "if (compact) quirks.compactEffort?.let { return it }\n";

export function fixture(overrides: Record<string, string | null> = {}): Record<string, string | null> {
  const base: Record<string, string | null> = {};
  for (const d of Object.keys(PATHS)) base[d] = CLEAN;
  for (const k of Object.keys(CANARY_TESTS)) base[`canary:${k}`] = `fun \`${CANARY_TOKEN}\`()`;
  return { ...base, ...overrides };
}

function selftest(): number {
  const fails: string[] = [];
  const [d0, d1, d2] = Object.keys(PATHS);
  if (detect(fixture())[0].length > 0) {
    fails.push(`a clean tree must be GREEN: ${pyRepr(detect(fixture())[0])}`);
  }
  if (detect(fixture({ [d0]: SHAPED }))[0].length === 0) {
    fails.push("a directive appended on compact must be RED");
  }
  if (detect(fixture({ [d1]: STRIPPED }))[0].length === 0) {
    fails.push("tools stripped on compact must be RED");
  }
  if (detect(fixture({ [d2]: PINNED }))[0].length === 0) {
    fails.push("an effort pin on compact must be RED");
  }
  if (detect(fixture({ [d0]: codeOnly("// " + SHAPED) ?? "" }))[0].length > 0) {
    fails.push("a comment naming a token is not a shaping — must stay GREEN");
  }
  if (detect(fixture({ [`canary:${d0}`]: "fun `something else`()" }))[0].length === 0) {
    fails.push("a missing canary test must be RED");
  }
  if (detect(fixture({ [d2]: null }))[0].length === 0) {
    fails.push("a missing builder file must be RED, never a vacuous pass");
  }
  const live = load();
  if (detect(live)[0].length > 0) {
    fails.push(`the real sources must be GREEN: ${pyRepr(detect(live)[0])}`);
  }
  if (fails.length > 0) {
    process.stdout.write("CX-02 SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write(
    "CX-02 SELFTEST OK — red on a directive, on stripped tools, on an effort pin, on a missing " +
      "canary and on a missing builder; green on a comment and on the real sources.\n",
  );
  return 0;
}

function main(): number {
  if (process.argv.includes("--selftest")) return selftest();
  const [problems, summary] = detect(load());
  process.stdout.write(`CX-02: ${summary}\n`);
  if (problems.length > 0) {
    process.stdout.write("CX-02 WALL RED:\n");
    for (const p of problems) process.stdout.write(`  · ${p}\n`);
    return 1;
  }
  process.stdout.write("CX-02 WALL GREEN: every dialect builds a compaction byte-identical to a turn.\n");
  return 0;
}

if (import.meta.main) {
  process.exit(main());
}
