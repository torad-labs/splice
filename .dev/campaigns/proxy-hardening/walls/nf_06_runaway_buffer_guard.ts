#!/usr/bin/env bun
/** WALL for NF-06 — every stream translator must carry the runaway-buffer guard, from ONE source.
 *
 *  GAP (RED at authoring, 2026-08-07): only ChatStreamTranslator trips into an honest failure above
 *  its buffered-chars cap; Responses and Passthrough accumulate into structurally identical unbounded
 *  StringBuilders. spliced is ONE process serving every head — a hostile/broken upstream streaming
 *  deltas forever does not fail a turn, it OOMs codex+grok+kimi simultaneously.
 *
 *  GREEN requires ALL of:
 *    1. a shared splice.upstream.transport.BufferCapacity definition exists (one cap, one predicate — the same
 *       single-source move TerminalStates made for terminal precedence);
 *    2. ALL THREE translators (chat, responses, passthrough) call BufferCapacity.over at the live guard;
 *    3. the chat translator no longer carries its own private MAX_BUFFERED_CHARS (code MOTION,
 *       not a fourth copy).
 *
 *  EXIT 0 = guarded everywhere from one source. EXIT 1 = gap open.
 *  --selftest = the POSITIVE CONTROL (gate check C6).
 *
 *  V4-154: converted to TypeScript (bun). Both lazy captures become [\s\S]*? (a JS dot stops at a
 *  newline, and both patterns carry re.S), and the named groups become positional captures. The
 *  guard predicate's trailing-name test keeps Python's `$` semantics: `\s*` consumes any trailing
 *  whitespace, so a non-multiline JS end-anchor agrees.
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
const SPI = resolve(ROOT, "upstream/src/main/kotlin/splice/upstream/transport/BufferCapacity.kt");
const CHAT = resolve(ROOT, "gateway/dialect-openai-chat/src/main/kotlin/splice/dialect/chat/ChatStreamTranslator.kt");
const RESP = resolve(ROOT, "dialects/openai-responses/src/main/kotlin/splice/dialect/responses/stream/ResponsesStreamTranslator.kt");
const PASS = resolve(ROOT, "dialects/anthropic/src/main/kotlin/splice/dialect/anthropic/PassthroughStreamTranslator.kt");
const EXPECTED_SURFACES: Record<string, string[]> = {
  chat: ["channels.textBuf.length", "channels.thinkingBuf.length",
    "toolCalls.retainedIndexEntryCount", "toolCalls.bufferedArgsChars"],
  responses: ["state.textBuf.length", "state.thinkingBuf.length", "state.blocks.size",
    "pendingArgsChars"],
  passthrough: ["channels.textBuf.length", "channels.thinkingBuf.length",
    "blocks.openBlockCount", "blocks.bufferedToolArgsChars"],
};
const GUARDED_COLLECT_RE = /\.takeWhile\s*\{([\s\S]*?)\}\s*\.collect\s*\{/g;
const OVER_ASSIGN_RE = /\bval\s+(\w+)\s*=\s*!\s*BufferCapacity\.over\s*\(([\s\S]*?)\)/;

function reEsc(s: string): string {
  return s.replace(/[^A-Za-z0-9_]/g, (c) => "\\" + c);
}

/** The one collect() must be gated by one four-surface takeWhile predicate. */
export function hasLiveGuard(text: string, required: string[]): boolean {
  const guards = [...text.matchAll(GUARDED_COLLECT_RE)];
  if (guards.length !== 1 || [...text.matchAll(/\.collect\s*\{/g)].length !== 1) {
    return false;
  }
  const predicate = guards[0][1];
  const assignment = OVER_ASSIGN_RE.exec(predicate);
  return Boolean(
    assignment !== null &&
      required.every((token) => assignment[2].includes(token)) &&
      new RegExp("\\b" + reEsc(assignment[1]) + "\\s*$").test(predicate),
  );
}

/** Pure detection. No I/O — the selftest feeds it directly.
 *
 *  `spi`/`chat`/`resp`/`pas` are the CODE views (comments and imports stripped) and carry every
 *  REQUIRED token; `chatRaw` is the untouched chat text and carries the private-fork BAN. The two
 *  directions want opposite treatment — see codeOnly. */
export function detect(
  spi: string | null,
  chat: string | null,
  resp: string | null,
  pas: string | null,
  chatRaw: string | null,
): string[] {
  const problems: string[] = [];
  for (const [name, text] of [
    ["ChatStreamTranslator", chat],
    ["ResponsesStreamTranslator", resp],
    ["PassthroughStreamTranslator", pas],
  ] as [string, string | null][]) {
    if (text === null) {
      return [`${name}.kt missing — refusing to pass vacuously`];
    }
  }
  if (spi === null || !spi.includes("object BufferCapacity")) {
    problems.push(
      "no shared splice.upstream.transport.BufferCapacity — the cap either does not exist or " +
        "is a per-dialect copy waiting to drift",
    );
  }
  for (const [name, text] of [
    ["chat", chat],
    ["responses", resp],
    ["passthrough", pas],
  ] as [string, string | null][]) {
    if (!hasLiveGuard(text ?? "", EXPECTED_SURFACES[name])) {
      problems.push(
        `${name} driveTurn does not gate its collect() with a four-surface ` +
          "BufferCapacity.over() takeWhile predicate — dead helpers, ignored " +
          "calls, or constant arguments do not bound retained buffers",
      );
    }
  }
  if (chatRaw !== null && chatRaw.includes("MAX_BUFFERED_CHARS =")) {
    problems.push(
      "chat still carries a private MAX_BUFFERED_CHARS — the lift must be code " +
        "MOTION, not a fourth copy that can drift",
    );
  }
  return problems;
}

const BLOCK_COMMENT = /\/\*[\s\S]*?\*\//g;
const LINE_COMMENT = /\/\/.*?$/gm;
const IMPORT_LINE = /^import .*$/gm;

/** A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy a
 *  REQUIRED token after the real call site is deleted. Same stripper cx_02/cx_09/cx_18 carry.
 *  Proven against this wall's own sources: with the passthrough `BufferCapacity.over(...)` call
 *  deleted and its literal text left in a TODO, the raw-matching wall printed WALL GREEN.
 *
 *  Applied to read() (the required tokens) and deliberately NOT to readRaw(), which feeds the
 *  private-fork BAN. The two directions want opposite treatment: stripping makes a required token
 *  harder to satisfy, but would make a banned string easier to hide. Both stay strict this way.
 *  The import strip matters here specifically — `import splice.upstream.transport.BufferCapacity` would otherwise
 *  keep every translator green with its guard deleted. */
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

export const SPI_OK = "public object BufferCapacity { const val X = 1 }";

export function guarded(...args: string[]): string {
  const joined = args.join(", ");
  return (
    "fun driveTurn() { upstream.takeWhile { " +
    `val withinCapacity = !BufferCapacity.over(${joined}); withinCapacity ` +
    "}.collect { evt -> onEvent(evt, sink) } }"
  );
}

export const CHAT_GUARDED = guarded("channels.textBuf.length", "channels.thinkingBuf.length",
  "toolIndexCount = toolCalls.retainedIndexEntryCount",
  "pendingArgsLen = toolCalls.bufferedArgsChars");
export const RESP_GUARDED = guarded(
  "state.textBuf.length", "state.thinkingBuf.length", "toolIndexCount = state.blocks.size",
  "pendingArgsLen = pendingArgsChars",
).replace("driveTurn", "driveRound");
export const PASS_GUARDED = guarded("channels.textBuf.length", "channels.thinkingBuf.length",
  "toolIndexCount = blocks.openBlockCount",
  "pendingArgsLen = blocks.bufferedToolArgsChars");
export const REFERENCED_ONLY = "fun driveTurn() { val capacity = BufferCapacity; upstream.collect { work() } }";
export const UNGUARDED = "fun driveTurn() { upstream.collect { evt -> onEvent(evt, sink) } }";
export const DEAD_GUARD = UNGUARDED + "\nfun telemetry() = BufferCapacity.over(0, 0)";
export const CONSTANT_GUARD = guarded("0", "0", "toolIndexCount = 0", "pendingArgsLen = 0");
export const CHAT_PRIVATE = "private const val MAX_BUFFERED_CHARS = 20_000_000\n" + CHAT_GUARDED;

export const COMMENTED_GUARD =
  "import splice.upstream.transport.BufferCapacity\n" +
  "fun driveRound() { upstream.takeWhile {\n" +
  "// val withinCapacity = !BufferCapacity.over(state.textBuf.length, " +
  "state.thinkingBuf.length, toolIndexCount = state.blocks.size, " +
  "pendingArgsLen = pendingArgsChars)\n" +
  "withinCapacity\n" +
  "}.collect { evt -> router.onEvent(evt, sink) } }";
export const HIDDEN_FORK = CHAT_GUARDED + "\n// private const val MAX_BUFFERED_CHARS = 20_000_000";

function selftest(): number {
  const fails: string[] = [];
  if (detect(null, "x", UNGUARDED, UNGUARDED, "x").length === 0) {
    fails.push("no shared BufferCapacity must be RED");
  }
  if (detect(SPI_OK, CHAT_GUARDED, RESP_GUARDED, PASS_GUARDED, CHAT_GUARDED).length > 0) {
    fails.push(
      `all-guarded must be GREEN, got ${pyRepr(detect(SPI_OK, CHAT_GUARDED, RESP_GUARDED, PASS_GUARDED, CHAT_GUARDED))}`,
    );
  }
  if (detect(SPI_OK, CHAT_GUARDED, UNGUARDED, PASS_GUARDED, CHAT_GUARDED).length === 0) {
    fails.push("one unguarded translator must be RED");
  }
  const refCases: [string, string, string, string][] = [
    ["chat", REFERENCED_ONLY, RESP_GUARDED, PASS_GUARDED],
    ["responses", CHAT_GUARDED, REFERENCED_ONLY, PASS_GUARDED],
    ["passthrough", CHAT_GUARDED, RESP_GUARDED, REFERENCED_ONLY],
  ];
  for (const [name, chat, resp, pas] of refCases) {
    if (detect(SPI_OK, chat, resp, pas, chat).length === 0) {
      fails.push(`${name} mentioning BufferCapacity without a live guard must be RED`);
    }
  }
  const brokenCases: [string, string][] = [["dead helper", DEAD_GUARD], ["constant arguments", CONSTANT_GUARD]];
  for (const [label, broken] of brokenCases) {
    if (detect(SPI_OK, broken, RESP_GUARDED, PASS_GUARDED, broken).length === 0) {
      fails.push(`a chat ${label} must be RED`);
    }
  }
  if (detect(SPI_OK, CHAT_PRIVATE, RESP_GUARDED, PASS_GUARDED, CHAT_PRIVATE).length === 0) {
    fails.push("a chat-side private MAX_BUFFERED_CHARS copy must be RED (motion, not a fork)");
  }
  if (detect(SPI_OK, null, RESP_GUARDED, PASS_GUARDED, null).length === 0) {
    fails.push("a missing translator file must be RED, never a vacuous pass");
  }
  // HD-26 comment-satisfiability controls. Both directions, so a later blind sweep that strips the
  // ban too (or stops stripping the required tokens) breaks the selftest instead of the invariant.
  if (detect(SPI_OK, CHAT_GUARDED, COMMENTED_GUARD, PASS_GUARDED, CHAT_GUARDED).length > 0) {
    fails.push(
      "the raw shape must read GREEN — otherwise this fixture is not the bug and " +
        "the control below proves nothing",
    );
  }
  if (detect(SPI_OK, CHAT_GUARDED, codeOnly(COMMENTED_GUARD), PASS_GUARDED, CHAT_GUARDED).length === 0) {
    fails.push(
      "a translator whose guard survives only as an import + comment must be RED — " +
        "required tokens are matched against code, never raw file text",
    );
  }
  if (detect(SPI_OK, codeOnly(HIDDEN_FORK), RESP_GUARDED, PASS_GUARDED, HIDDEN_FORK).length === 0) {
    fails.push(
      "a private MAX_BUFFERED_CHARS fork commented out of the code view must still " +
        "be RED — the ban reads RAW so a comment cannot hide it",
    );
  }
  if (fails.length > 0) {
    process.stdout.write("NF-06 SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write(
    "NF-06 SELFTEST OK — red on missing cap, dead/reference-only/constant guards, missing " +
      "four-surface arguments, private chat fork, and missing files; green only when each live " +
      "translation path gates collect() through BufferCapacity.over()\n",
  );
  return 0;
}

function main(): number {
  if (process.argv.includes("--selftest")) return selftest();
  const problems = detect(read(SPI), read(CHAT), read(RESP), read(PASS), readRaw(CHAT));
  if (problems.length > 0) {
    process.stdout.write("NF-06 WALL RED — runaway-buffer guard is not one-source-three-dialects:\n");
    for (const p of problems) process.stdout.write(`  · ${p}\n`);
    return 1;
  }
  process.stdout.write("NF-06 WALL GREEN: one BufferCapacity, three guarded translators, no private forks.\n");
  return 0;
}

if (import.meta.main) {
  process.exit(main());
}
