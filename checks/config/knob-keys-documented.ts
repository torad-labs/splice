#!/usr/bin/env bun
/**
 * V4-87 — every operator-facing KNOB KEY has a disposition in the documentation.
 *
 * WHY THIS EXISTS. Knob.kt is the finite knob surface: the keys an operator may write in
 * `[defaults]`, in `[heads.<key>.overrides]`, in state `config.json`, or PATCH through
 * /mgmt/config. Its documentation lives in ANOTHER file — the example config an operator
 * copies — and nothing paired the two. The 2026-09-17 architecture audit (C rows 1, 5, 12)
 * counted the gap by hand and found class (b): 17 knobs an operator can set that the
 * example config never names. A hand count closes the instance; this closes the class — a
 * knob added tomorrow is in scope with no edit to this file, and a knob retired while the
 * doc still describes it is red the same day.
 *
 * This is the KNOB twin of the quirks-key checker (V4-44), whose shape, guards and
 * selftest idiom it mirrors deliberately: same three dispositions, same
 * fail-closed-on-a-vacuous-parse rule, same red-BY-NAME message. The two checkers are
 * separate files rather than one parameterised one because the denominators are parsed out
 * of structurally different Kotlin — a data-class primary constructor there, an enum-entry
 * argument list here — and sharing would mean editing a wall outside this row's fence.
 *
 * DENOMINATOR, from the SOURCE, never a hand list. Every entry of the `Knob` enum in
 * Knob.kt is parsed on disk and its FIRST constructor argument — the `key` property — is
 * the TOML key. 33 keys today (the audit's row said 32; the source says 33, and the source
 * is the denominator). Three guards refuse a vacuous pass:
 *   - the enum declaration must be found: a moved or renamed enum fails rather than
 *     yielding an empty denominator that passes;
 *   - a parse yielding zero keys is a failure, not a pass;
 *   - the parsed entry count must equal the number of `KnobKind.` mentions in the
 *     comment-stripped file — every entry passes exactly one `KnobKind`, so a disagreement
 *     means the parser and the source no longer see the same enum and NO key list from that
 *     run can be trusted.
 *
 * TWO OPERATOR SPELLINGS, ONE KNOB. `Knob.key` is camelCase, which is literally what
 * `[defaults]`, `[heads.<k>.overrides]`, state config.json and a /mgmt/config PATCH key off.
 * The `[daemon]` table is the exception: it deserializes through DaemonConfig, whose
 * @SerialName spells the same knob snake_case — `showReasoning` is written `show_reasoning`
 * there, `controlPort` is `control_port`. Both are the operator's spelling, so either
 * satisfies this wall, and the snake form is TRANSLITERATED from the key rather than mapped
 * by hand. Without this, six knobs the example config does document (show_reasoning, summary,
 * effort, replay_reasoning, mirror_reasoning, control_port) read as undocumented — a wall
 * that reds a documented key teaches the reader to ignore it.
 *
 * DISPOSITION. Every parsed key must be accounted for, in one of two forms:
 *   documented — the key appears as a TOML key token (`key = ...`) in config/splice.example.toml
 *                in EITHER spelling, line-leading or inside an inline table, commented or
 *                live. Commented is a normal spelling in that file: it shows a key and its
 *                default without changing the daily-driven config;
 *   retired    — a machine-readable marker, `# retired: <key> — <reason>`, whose reason must
 *                be non-empty: a retirement with no written reason is an absence wearing a
 *                label and fails BY NAME like any other absence.
 * Absence is not a disposition. An undocumented key fails by name, naming its enum entry, so
 * the fix is obvious without reading the type.
 *
 * MEASURED ON THE TREE at authoring time (2026-09-17, `report .`): 33 keys, 10 with a
 * disposition, 23 red by name. The audit's row predicted 32 keys and 17 undocumented; the
 * source says 33 and 23, and the source is the denominator.
 *
 * WHAT IS NOT A DISPOSITION SURFACE, and why it matters. README.md is not a knob reference.
 * Knob.kt's own KDoc is not documentation of the knob to an OPERATOR — counting the source
 * as its own documentation is the tautology §24 exists to forbid (a check whose denominator
 * and numerator come from the same file cannot fail). And the runtime doctor/status maps
 * that print knob values are reports, not documentation. The surface is exactly the one file
 * an operator copies to write a config.
 *
 * NOT CAUGHT, and why.
 *   "default + unit + semantics". The row asks the doc line to carry the default, the unit
 *   and what the knob does. Only the KEY TOKEN is machine-checkable; prose quality is not.
 *   What would catch part of it: comparing the documented value against the enum's `default`
 *   literal. Not built here because the example config deliberately shows non-default values
 *   in places (maxInflight = "8" on the kimi head is the point of that example), so a
 *   value-equality check would be wrong in exactly the cases the file is teaching.
 *   A key documented in the WRONG place — and this one is LIVE, not hypothetical. The check
 *   proves the key token exists in the surface, not that it sits under a knob table. Two of
 *   the ten green keys are green by coincidence: `port` matches the commented head port at
 *   config/splice.example.toml:219, and `pinnedModel` matches the head field `pinned_model`
 *   at :221. Neither line documents the daemon knob of that name. They are named here rather
 *   than filtered because a section-aware scan is the fix and it is a different wall: the
 *   knob layers are five (`[daemon]`, `[defaults]`, `[heads.*.overrides]`, state config.json,
 *   PATCH) and binding a key token to the right one of them is more machinery than the drift
 *   it would catch on a 33-key surface a reviewer can read. Whoever documents the 23 red keys
 *   should document these two properly in the same pass.
 *   A key documented only in a THIRD file. The surface list is one path, written below;
 *   adding a surface is a change to this checker too.
 *
 * SELFTEST. --selftest builds a temp tree and proves BOTH directions: GREEN on the compliant
 * form (live key, commented key, reasoned retirement) and GREEN WITH A COUNT on the boring
 * one-key tree; RED naming a synthetic key appended to a temp copy of the source (the
 * mutation this row requires), RED on a retirement carrying no reason, RED on a key named
 * only in a runtime report map, RED on the empty enum (refusing to pass vacuously), RED when
 * the enum has been renamed out from under the parser, and RED when the parsed entry count
 * disagrees with the file's `KnobKind.` count.
 *
 * Usage:
 *     bun checks/config/knob-keys-documented.ts check <root>
 *     bun checks/config/knob-keys-documented.ts report <root>
 *     bun checks/config/knob-keys-documented.ts --selftest
 *
 * A BARE RUN IS `check` ON THE REPO ROOT — the one gating mode, so there is no non-gating
 * default to mis-invoke. `report` is the census and sits behind an explicit verb the gate
 * never uses.
 */
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// parents[2]: this file lives at checks/config/, so the repo root is two levels up.
const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");

// The denominator. Fixed path on purpose: a checker that silently loses its source is a
// checker that passes.
const SOURCE_REL = "gateway/core/src/main/kotlin/splice/core/config/Knob.kt";

// The one file an operator copies to write a config. See NOT CAUGHT for what a second
// surface would mean.
const SURFACES = ["config/splice.example.toml"];

const ENUM_DECL = /\benum\s+class\s+Knob\b/;
const ENTRY_HEAD = /^\s*([A-Z][A-Z0-9_]*)\s*\(/s;
const KIND_MENTION = /\bKnobKind\./g;

/** One parsed enum entry: its TOML key and the entry that declares it. */
interface KnobKey {
  entry: string;
  key: string;
}

function escapeRe(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** Remove line and block comments, respecting string literals.
 *
 *  Only the KnobKind-count guard reads this. A raw count that included a commented
 *  mention — Knob.kt's own header says "live in KnobKind.kt", which contains the token —
 *  would red the wall for a doc change, which is the wrong signal. */
function stripComments(source: string): string {
  const out: string[] = [];
  let i = 0;
  let inString = false;
  let quote = "";
  let escape = false;
  while (i < source.length) {
    const ch = source[i];
    if (inString) {
      out.push(ch);
      if (escape) escape = false;
      else if (ch === "\\") escape = true;
      else if (ch === quote) inString = false;
      i += 1;
      continue;
    }
    if (ch === '"' || ch === "'") {
      inString = true;
      quote = ch;
      out.push(ch);
      i += 1;
      continue;
    }
    if (ch === "/" && i + 1 < source.length && source[i + 1] === "/") {
      const nl = source.indexOf("\n", i);
      i = nl < 0 ? source.length : nl;
      continue;
    }
    if (ch === "/" && i + 1 < source.length && source[i + 1] === "*") {
      const end = source.indexOf("*/", i + 2);
      i = end < 0 ? source.length : end + 2;
      continue;
    }
    out.push(ch);
    i += 1;
  }
  return out.join("");
}

/** Return [bodyStart, bodyEnd] for the balanced pair beginning at or after [start].
 *
 *  Comment- and string-aware: Knob.kt interleaves prose comments between entries, and
 *  those comments contain both parens and braces. */
function walk(source: string, start: number, opener: string, closer: string): [number, number] | null {
  let i = start;
  let depth = 0;
  let bodyStart: number | null = null;
  let inString = false;
  let quote = "";
  let escape = false;
  while (i < source.length) {
    const ch = source[i];
    if (inString) {
      if (escape) escape = false;
      else if (ch === "\\") escape = true;
      else if (ch === quote) inString = false;
      i += 1;
      continue;
    }
    if (ch === '"' || ch === "'") {
      inString = true;
      quote = ch;
      i += 1;
      continue;
    }
    if (ch === "/" && i + 1 < source.length && source[i + 1] === "/") {
      const nl = source.indexOf("\n", i);
      i = nl < 0 ? source.length : nl;
      continue;
    }
    if (ch === "/" && i + 1 < source.length && source[i + 1] === "*") {
      const end = source.indexOf("*/", i + 2);
      i = end < 0 ? source.length : end + 2;
      continue;
    }
    if (ch === opener) {
      depth += 1;
      if (depth === 1) bodyStart = i + 1;
      i += 1;
      continue;
    }
    if (ch === closer) {
      depth -= 1;
      if (depth === 0 && bodyStart !== null) return [bodyStart, i];
      i += 1;
      continue;
    }
    i += 1;
  }
  return null;
}

/** Split on top-level separators, dropping comments. */
function splitTopLevel(body: string, separator = ","): string[] {
  const parts: string[] = [];
  let buf: string[] = [];
  let depth = 0;
  let inString = false;
  let quote = "";
  let escape = false;
  let i = 0;
  while (i < body.length) {
    const ch = body[i];
    if (inString) {
      buf.push(ch);
      if (escape) escape = false;
      else if (ch === "\\") escape = true;
      else if (ch === quote) inString = false;
      i += 1;
      continue;
    }
    if (ch === '"' || ch === "'") {
      inString = true;
      quote = ch;
      buf.push(ch);
      i += 1;
      continue;
    }
    if (ch === "/" && i + 1 < body.length && body[i + 1] === "/") {
      const nl = body.indexOf("\n", i);
      i = nl < 0 ? body.length : nl;
      continue;
    }
    if (ch === "/" && i + 1 < body.length && body[i + 1] === "*") {
      const end = body.indexOf("*/", i + 2);
      i = end < 0 ? body.length : end + 2;
      continue;
    }
    if ("({[".includes(ch)) {
      depth += 1;
      buf.push(ch);
      i += 1;
      continue;
    }
    if (")}]".includes(ch)) {
      depth -= 1;
      buf.push(ch);
      i += 1;
      continue;
    }
    if (ch === separator && depth === 0) {
      parts.push(buf.join(""));
      buf = [];
      i += 1;
      continue;
    }
    buf.push(ch);
    i += 1;
  }
  if (buf.length > 0) parts.push(buf.join(""));
  return parts;
}

/** Return (keys, problems). problems is non-empty only on a parse that cannot be
 *  trusted, never on a merely undocumented key. */
function parseKnobs(source: string, label: string): { keys: KnobKey[]; problems: string[] } {
  const problems: string[] = [];
  const decl = ENUM_DECL.exec(source);
  if (decl === null) {
    return {
      keys: [],
      problems: [
        `${label}: no \`enum class Knob\` declaration found — the knob enum has moved or ` +
          "been renamed, so this run has no denominator and must not pass",
      ],
    };
  }
  const ctor = walk(source, decl.index + decl[0].length, "(", ")");
  if (ctor === null) return { keys: [], problems: [`${label}: the Knob primary constructor could not be parsed`] };
  const bodySpan = walk(source, ctor[1], "{", "}");
  if (bodySpan === null) return { keys: [], problems: [`${label}: the Knob enum body could not be parsed`] };
  const body = source.slice(bodySpan[0], bodySpan[1]);
  // A Kotlin enum's entries end at the first top-level `;`; members follow. Knob.kt has
  // no members today, so this is the shape-proofing, not a live branch.
  const entriesText = splitTopLevel(body, ";")[0];

  const keys: KnobKey[] = [];
  for (const raw of splitTopLevel(entriesText, ",")) {
    const head = ENTRY_HEAD.exec(raw);
    if (head === null) continue;
    const entry = head[1];
    const argsSpan = walk(raw, head.index + head[0].length - 1, "(", ")");
    if (argsSpan === null) {
      problems.push(`${label}: ${entry} argument list could not be parsed`);
      continue;
    }
    const args = splitTopLevel(raw.slice(argsSpan[0], argsSpan[1]), ",");
    if (args.length === 0) {
      problems.push(`${label}: ${entry} declares no arguments — where is its key?`);
      continue;
    }
    const literal = /^\s*"([^"]*)"\s*$/.exec(args[0]);
    if (literal === null) {
      problems.push(
        `${label}: ${entry}'s first argument is not a string literal ` +
          `(${pyRepr(args[0].trim())}) — the key cannot be read from the source`,
      );
      continue;
    }
    keys.push({ entry, key: literal[1] });
  }

  if (keys.length === 0) return { keys, problems };
  const kinds = [...stripComments(source).matchAll(KIND_MENTION)].length;
  if (kinds !== keys.length) {
    problems.push(
      `${label}: parsed ${keys.length} enum entries but the file holds ${kinds} ` +
        "`KnobKind.` mentions — every entry passes exactly one kind, so the parser and " +
        "the source disagree and no key list from this run can be trusted",
    );
  }
  return { keys, problems };
}

/** Python's repr for the one value a message embeds, so a failure path reads identically. */
function pyRepr(s: string): string {
  return `'${s.replace(/\\/g, "\\\\").replace(/'/g, "\\'")}'`;
}

/** The knob's OTHER operator-facing spelling, by transliteration — never a hand map, so a
 *  knob added tomorrow needs no edit here. */
function snake(key: string): string {
  return key.replace(/(?<!^)(?=[A-Z])/g, "_").toLowerCase();
}

/** `key =` anywhere, in EITHER spelling: line-leading live key, commented key, or
 *  inline-table entry. The lookbehind keeps a shorter key from matching a longer one that
 *  ends with it, and the two spellings are alternated rather than loosened into one fuzzy
 *  pattern so a camelCase key can never be satisfied by an unrelated snake_case that does
 *  not exist. */
function keyToken(key: string): RegExp {
  const spellings = [...new Set([key, snake(key)])].sort();
  return new RegExp(`(?<![\\w-])(?:${spellings.map(escapeRe).join("|")})\\s*=`, "g");
}

/** Return (marker present, reason). An empty reason means the marker is present and
 *  the disposition is NOT. */
function retiredReason(text: string, key: string): { marked: boolean; reason: string } {
  const pattern = new RegExp(
    `^[ \\t]*#[ \\t]*retired:[ \\t]*${escapeRe(key)}(?![A-Za-z0-9_-])(.*)$`,
    "m",
  );
  const match = pattern.exec(text);
  if (match === null) return { marked: false, reason: "" };
  return { marked: true, reason: match[1].trim().replace(/^[—:-]+/, "").trim() };
}

function surfaceTexts(root: string): { texts: Map<string, string>; problems: string[] } {
  const texts = new Map<string, string>();
  const problems: string[] = [];
  for (const rel of SURFACES) {
    const p = join(root, rel);
    if (!existsSync(p)) {
      problems.push(
        `${rel}: disposition surface missing — a surface that cannot be read ` +
          "cannot document anything",
      );
      continue;
    }
    texts.set(rel, readFileSync(p, "utf8"));
  }
  return { texts, problems };
}

/** key -> where it is documented, for `report`. Empty when nothing documents it. */
function dispositions(root: string): Map<string, string> {
  const sourcePath = join(root, SOURCE_REL);
  if (!existsSync(sourcePath)) return new Map();
  const { keys } = parseKnobs(readFileSync(sourcePath, "utf8"), SOURCE_REL);
  const { texts } = surfaceTexts(root);
  const found = new Map<string, string>();
  for (const knob of keys) {
    for (const [rel, text] of texts) {
      const { marked, reason } = retiredReason(text, knob.key);
      if (marked && reason) {
        found.set(knob.key, `${rel}: retired`);
      } else {
        const match = keyToken(knob.key).exec(text);
        if (match !== null) {
          const line = text.slice(0, match.index).split("\n").length;
          found.set(knob.key, `${rel}:${line} (${match[0].replace(/=\s*$/, "").trim()})`);
        }
      }
    }
  }
  return found;
}

function audit(root: string): string[] {
  const problems: string[] = [];
  const sourcePath = join(root, SOURCE_REL);
  if (!existsSync(sourcePath)) {
    return [
      `${SOURCE_REL}: missing — the knob enum IS the denominator, so its absence ` + "cannot pass",
    ];
  }
  const { keys, problems: parseProblems } = parseKnobs(readFileSync(sourcePath, "utf8"), SOURCE_REL);
  problems.push(...parseProblems);
  if (keys.length === 0) {
    problems.push(
      `${SOURCE_REL}: parsed 0 knob keys — refusing to pass vacuously, because a ` +
        "green over an empty denominator is what this wall exists to prevent",
    );
    return problems;
  }

  const { texts, problems: surfaceProblems } = surfaceTexts(root);
  problems.push(...surfaceProblems);

  for (const knob of keys) {
    let documented = false;
    // attempted: a retirement marker was written for this key. It keeps an unreasoned
    // retirement to ONE problem — the specific, actionable one — rather than also
    // reporting the same key as undocumented.
    let attempted = false;
    for (const [rel, text] of texts) {
      const { marked, reason } = retiredReason(text, knob.key);
      if (marked) {
        attempted = true;
        if (reason) {
          documented = true;
          continue;
        }
        problems.push(
          `${rel}: ${knob.key} is retired with NO reason — a retirement without ` +
            "a written reason is an absence wearing a label",
        );
        continue;
      }
      if (keyToken(knob.key).test(text)) documented = true;
    }
    if (!documented && !attempted) {
      problems.push(
        `NO DISPOSITION: ${knob.key} (Knob.${knob.entry}) is documented nowhere in ` +
          `${SURFACES.join(" or ")}; document it there with its default, its unit and ` +
          `what it does, or retire it with \`# retired: ${knob.key} — <reason>\``,
      );
    }
  }
  return problems;
}

// ── selftest fixtures ─────────────────────────────────────────────────────────────────

const COMPLIANT_SOURCE = `package splice.core.config

public enum class Knob(
    public val key: String,
    public val kind: KnobKind,
    public val envNames: List<String>,
    public val default: Any?,
    public val restartRequired: Boolean = false,
) {
    PORT("port", KnobKind.NUMBER, listOf("CODEX_PROXY_PORT"), 3099L, restartRequired = true),
    // A prose comment between entries, with (parens) and {braces} a naive walk would choke on.
    MAX_INFLIGHT("maxInflight", KnobKind.NUMBER, listOf("CLAUDEX_MAX_INFLIGHT"), 12L),
    DEBUG(
        "debug",
        KnobKind.BOOL,
        listOf("CLAUDEX_DEBUG", "CODEX_PROXY_DEBUG"),
        false,
        restartRequired = true,
    ),
    SHOW_REASONING("showReasoning", KnobKind.STRING, listOf("CLAUDEX_SHOW_REASONING"), "text"),
    GROK_PORT("grokPort", KnobKind.NUMBER, listOf("GROK_PROXY_PORT"), 3100L, restartRequired = true),
}
`;

// Every sanctioned spelling in one tree: port / maxInflight live camelCase, debug
// commented, showReasoning ONLY in its snake_case `[daemon]` spelling, grokPort retired
// with a reason.
const COMPLIANT_DOC = `[daemon]
show_reasoning = "text"      # "text" | "thinking" | "off"

[defaults]
port = "3099"
maxInflight = "12"
# debug = "false"   # daemon-wide verbose logging
# retired: grokPort — the grok head now takes its port from [heads.*.port]
`;

const RETIRED_NOREASON_DOC = COMPLIANT_DOC.replace(
  "# retired: grokPort — the grok head now takes its port from [heads.*.port]",
  "# retired: grokPort —",
);

// grokPort named only by a runtime report map: a report is not documentation.
const RUNTIME_MAP_DOC = `[daemon]
show_reasoning = "text"

[defaults]
port = "3099"
maxInflight = "12"
# debug = "false"
`;

const RUNTIME_MAP = `private fun shape(c: Config) = buildJsonObject {
    put("grokPort", c.grokPort)
}
`;

// The boring case: one knob, documented. A wall that cannot count to one cannot count.
const BORING_SOURCE = `package splice.core.config

public enum class Knob(
    public val key: String,
    public val kind: KnobKind,
) {
    PORT("port", KnobKind.NUMBER),
}
`;

const BORING_DOC = `[defaults]
port = "3099"
`;

const EMPTY_SOURCE = `package splice.core.config

public enum class Knob(
    public val key: String,
    public val kind: KnobKind,
) {
}
`;

const MOVED_SOURCE = COMPLIANT_SOURCE.replace("enum class Knob(", "enum class Tuning(");

// An extra `KnobKind.` the entry parser cannot attribute: the two counts disagree.
const DRIFT_SOURCE =
  COMPLIANT_SOURCE +
  `
public val orphanKind: KnobKind = KnobKind.STRING
`;

const FAKE_KEY_ENTRY = '    FAKE_NEW_KNOB("fakeNewKnob", KnobKind.BOOL, listOf("CLAUDEX_FAKE"), false),\n}';

function writeTree(root: string, source: string, doc: string, runtime = ""): void {
  const src = join(root, SOURCE_REL);
  mkdirSync(dirname(src), { recursive: true });
  writeFileSync(src, source, "utf8");
  const surface = join(root, SURFACES[0]);
  mkdirSync(dirname(surface), { recursive: true });
  writeFileSync(surface, doc + runtime, "utf8");
}

function selftest(): number {
  const failures: string[] = [];
  const root = join(tmpdir(), `knob-keys-${process.pid}-${Math.random().toString(36).slice(2)}`);
  const has = (hits: string[], a: string, b?: string): boolean =>
    hits.some((h) => h.includes(a) && (b === undefined || h.includes(b)));
  try {
    writeTree(root, COMPLIANT_SOURCE, COMPLIANT_DOC);
    let hits = audit(root);
    if (hits.length > 0) {
      failures.push(
        "compliant tree must be GREEN (live key, commented key, reasoned retirement): " + hits.join("; "),
      );
    }
    const live = dispositions(root);
    if (live.size !== 5) {
      failures.push(`report must account for all 5 fixture keys, got ${live.size}: ${[...live.keys()].sort()}`);
    }
    if (!live.has("showReasoning")) {
      failures.push(
        "a knob documented ONLY in its snake_case [daemon] spelling must count as " +
          `documented, got: ${[...live.keys()].sort()}`,
      );
    }

    // The snake_case alternative must not loosen into a fuzzy match: deleting the ONE
    // line that documents showReasoning reds it by name again.
    writeTree(root, COMPLIANT_SOURCE, COMPLIANT_DOC.replace('show_reasoning = "text"', ""));
    hits = audit(root);
    if (!has(hits, "NO DISPOSITION", "showReasoning")) {
      failures.push(`removing the only snake_case doc line must be RED by name, got: ${hits}`);
    }

    // The BORING case: exactly one knob, and the count must come out as one.
    writeTree(root, BORING_SOURCE, BORING_DOC);
    hits = audit(root);
    if (hits.length > 0) failures.push(`the one-knob tree must be GREEN, got: ${hits}`);
    const boring = parseKnobs(BORING_SOURCE, "boring");
    if (boring.problems.length > 0 || boring.keys.length !== 1 || boring.keys[0].key !== "port") {
      failures.push(
        "the one-knob tree must parse to exactly 1 key named port, got " +
          `${boring.keys.map((k) => k.key)} problems=${boring.problems}`,
      );
    }

    // The mutation the row requires: a fake key appended to a temp copy of the source.
    const mutated = COMPLIANT_SOURCE.replace(
      '    GROK_PORT("grokPort", KnobKind.NUMBER, listOf("GROK_PROXY_PORT"), 3100L, ' +
        "restartRequired = true),\n}",
      '    GROK_PORT("grokPort", KnobKind.NUMBER, listOf("GROK_PROXY_PORT"), 3100L, ' +
        "restartRequired = true),\n" + FAKE_KEY_ENTRY,
    );
    if (mutated === COMPLIANT_SOURCE) {
      failures.push("the mutation did not apply — the fake key never reached the temp copy");
    } else {
      writeTree(root, mutated, COMPLIANT_DOC);
      hits = audit(root);
      if (!hits.some((h) => h.includes("fakeNewKnob"))) {
        failures.push(`synthetic fake key must be RED BY NAME, got: ${hits}`);
      }
    }

    writeTree(root, COMPLIANT_SOURCE, RETIRED_NOREASON_DOC);
    hits = audit(root);
    if (!has(hits, "grokPort", "NO reason")) {
      failures.push(`a retirement with an empty reason must be RED by name, got: ${hits}`);
    }
    if (hits.filter((h) => h.includes("grokPort")).length !== 1) {
      failures.push(`an unreasoned retirement is ONE problem, not a duplicate pair, got: ${hits}`);
    }

    writeTree(root, COMPLIANT_SOURCE, RUNTIME_MAP_DOC);
    hits = audit(root);
    if (!has(hits, "NO DISPOSITION", "grokPort")) {
      failures.push(`a key with no disposition at all must be RED by name, got: ${hits}`);
    }

    writeTree(root, COMPLIANT_SOURCE, RUNTIME_MAP_DOC, RUNTIME_MAP);
    hits = audit(root);
    if (!has(hits, "NO DISPOSITION", "grokPort")) {
      failures.push(`a key named only in a runtime report map is not documented, got: ${hits}`);
    }

    writeTree(root, EMPTY_SOURCE, COMPLIANT_DOC);
    hits = audit(root);
    if (!hits.some((h) => h.includes("refusing to pass vacuously"))) {
      failures.push(`an enum with no entries must be RED, got: ${hits}`);
    }

    writeTree(root, MOVED_SOURCE, COMPLIANT_DOC);
    hits = audit(root);
    if (!hits.some((h) => h.includes("moved or"))) {
      failures.push(`a renamed/moved enum must be RED, got: ${hits}`);
    }

    writeTree(root, DRIFT_SOURCE, COMPLIANT_DOC);
    hits = audit(root);
    if (!hits.some((h) => h.includes("disagree"))) {
      failures.push(`a KnobKind mention the entry parser cannot attribute must be RED, got: ${hits}`);
    }

    writeTree(root, COMPLIANT_SOURCE, COMPLIANT_DOC);
    rmSync(join(root, SURFACES[0]));
    hits = audit(root);
    if (!hits.some((h) => h.includes("disposition surface missing"))) {
      failures.push(`a missing surface must be RED, got: ${hits}`);
    }
  } finally {
    rmSync(root, { recursive: true, force: true });
  }

  if (failures.length > 0) {
    process.stdout.write("knob-keys-documented SELFTEST FAIL:\n");
    for (const failure of failures) process.stdout.write("  " + failure + "\n");
    return 1;
  }
  process.stdout.write(
    "knob-keys-documented SELFTEST OK — a live key, a commented key and a reasoned " +
      "retirement are green, and the one-knob tree is green with a count of 1; a " +
      "synthetic key added to a temp source copy, an unreasoned retirement, a key named " +
      "only in a runtime report map, an empty enum, a renamed enum, a KnobKind count " +
      "that disagrees with the file, and a missing surface are all red by name\n",
  );
  return 0;
}

function report(root: string): void {
  const sourcePath = join(root, SOURCE_REL);
  const { keys, problems } = parseKnobs(readFileSync(sourcePath, "utf8"), SOURCE_REL);
  const live = dispositions(root);
  process.stdout.write(`knob-keys-documented: ${keys.length} knob keys parsed from ${SOURCE_REL}\n`);
  for (const problem of problems) process.stdout.write(`  UNTRUSTED: ${problem}\n`);
  for (const knob of keys) {
    const where = live.get(knob.key) ?? "NO DISPOSITION";
    process.stdout.write(`  ${knob.key.padEnd(24)} Knob.${knob.entry.padEnd(24)} ${where}\n`);
  }
  const missing = keys.filter((k) => !live.has(k.key)).map((k) => k.key);
  process.stdout.write(
    `  documented ${live.size}/${keys.length}; undocumented ${missing.length}: ${missing.join(", ")}\n`,
  );
}

function main(argv: string[]): number {
  if (argv.includes("--selftest")) return selftest();
  let root = ROOT;
  for (const arg of argv) {
    if (arg !== "check" && arg !== "report" && !arg.startsWith("-")) {
      root = arg;
      break;
    }
  }
  if (!existsSync(root)) {
    process.stderr.write("knob-keys-documented: tree missing\n");
    return 1;
  }
  if (argv.includes("report")) {
    report(root);
    return 0;
  }
  const problems = audit(root);
  if (problems.length > 0) {
    process.stdout.write("knob-keys-documented RED:\n");
    for (const problem of problems) process.stdout.write("  " + problem + "\n");
    return 1;
  }
  process.stdout.write(
    "knob-keys-documented GREEN: every knob key in Knob.kt has a disposition in " +
      "config/splice.example.toml\n",
  );
  return 0;
}

process.exit(main(process.argv.slice(2)));
