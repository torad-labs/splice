#!/usr/bin/env bun
/**
 * V4-87 — every environment variable splice READS has a disposition in the documentation.
 *
 * WHY THIS EXISTS. An env var is the highest-precedence configuration layer splice has: it
 * beats state config.json, it beats the TOML, it beats the knob default. It is also the only
 * layer with no schema, no `splice doctor` line and no file an operator can diff — so an
 * undocumented one is a setting that silently wins and that nobody can discover. The
 * 2026-09-17 architecture audit (C rows 1, 5, 12) counted the gap by hand; a hand count
 * closes the instance, this closes the class. A var read tomorrow is in scope with no edit
 * to this file.
 *
 * Twin of checks/config/knob-keys-documented.ts (the TOML half of the same row) and of
 * checks/config/quirks-keys-documented.ts (V4-44), whose guards, disposition vocabulary and
 * selftest idiom this mirrors. Separate files rather than one parameterised one because the
 * denominators are parsed out of structurally different source, and sharing would mean
 * editing a wall outside this row's fence.
 *
 * THE SEAM. kt-no-system-getenv forces every environment read in the gateway through ONE
 * port: `splice.core.util.EnvReader`, a `(String) -> String?` fun interface threaded by
 * constructor injection (production wires `System::getenv`; a test pins a hermetic map).
 * Main sources name it in exactly two spellings, `env` and `envReader`, measured across
 * gateway/{module}/src/main. That port is what makes this wall possible at all: without it the
 * denominator would be "every string anywhere", which is not a denominator.
 *
 * DENOMINATOR, from the SOURCE, never a hand list. Three resolution kinds, all read off disk:
 *   literal — a string literal at a seam call site: `env("PATH")`, `envReader("SPLICE_CONFIG")`,
 *             either with an explicit `.invoke(...)`;
 *   const   — a file-local `const val NAME = "LITERAL"` passed to the seam, resolved to its
 *             literal (SetupDetection's OPENROUTER_KEY is the live case);
 *   knob    — every name in a `Knob` entry's `envNames` list in Knob.kt. These are read
 *             through the same seam, at ConfigService's
 *             `knob.envNames.firstNotNullOfOrNull { name -> envReader(name) }`, so they are
 *             reads exactly as much as a literal is, and they are the largest family by far.
 *
 * FOUR GUARDS refuse a vacuous pass:
 *   - a direct `System.getenv("X")` CALL anywhere in main sources is red by file:line. Not
 *     because the ast-grep rule already bans it in non-config code — it does, and exempts
 *     core/config — but because a direct call is a read this scan CANNOT see, so its
 *     existence means the denominator is incomplete and no green from that run is true.
 *     Every site on the tree today is a `System::getenv` REFERENCE handed to an EnvReader,
 *     which is injection, not a read;
 *   - zero seam call sites found is a failure: it means the scan lost the seam, not that the
 *     gateway stopped reading the environment;
 *   - zero names in Knob.kt's envNames is a failure, for the same reason;
 *   - zero names overall is a failure rather than a pass.
 *
 * COMPUTED ARGUMENTS — what this wall does NOT cover, named rather than implied. Four seam
 * sites pass a name the parser cannot resolve, and every run prints them by file:line under
 * COMPUTED. They are excluded WITH A REASON, which is the disposition: the name they read is
 * not splice's to document.
 *   - ApiKeyAuthProvider / AddChecks / LoginIo read `envVar`, which is whatever the OPERATOR
 *     wrote in `auth = { kind = "api-key", env = "..." }`. The name is operator-authored, so
 *     there is no splice-owned name to document;
 *   - SetupHeads reads `profiles.apiKeyEnv(headKey)` = `HEADKEY.uppercase() + "_API_KEY"`, a
 *     family whose members depend on the head keys in a config this checker has never seen.
 *   - ConfigService's `envReader(name)` over `knob.envNames` is NOT in this class: the names
 *     are enumerable from Knob.kt and are in the denominator as the `knob` kind above.
 * A site moving from computed to literal lands in the denominator automatically. A NEW
 * computed site appears in the COMPUTED list, where a reviewer sees it; it does not fail,
 * because a computed argument yields no name and a wall cannot demand the documentation of a
 * string that does not exist.
 *
 * DISPOSITION. Every name must be accounted for, in one of two forms:
 *   documented — the name appears inside the ENVIRONMENT VARIABLES header block of
 *                config/splice.example.toml. A BLOCK, not merely the file, because the row
 *                asks the doc to state the precedence chain once and describe each var
 *                against it — and because a bare file-wide token search would count a var
 *                mentioned in passing inside an unrelated provider comment. The block is the
 *                sentinel line `# ENVIRONMENT VARIABLES` plus the contiguous run of comment
 *                and blank lines after it, and it must itself contain the precedence chain
 *                `env > TOML > default`. A missing block is red; a block without the chain is
 *                red;
 *   retired    — a machine-readable marker, `# retired: <NAME> — <reason>`, anywhere in the
 *                surface, whose reason must be non-empty: a retirement with no written reason
 *                is an absence wearing a label and fails BY NAME like any other absence.
 * Absence is not a disposition. Matching is CASE-SENSITIVE: `no_proxy` and `NO_PROXY` are two
 * variables and the gateway reads both.
 *
 * NOT CAUGHT, and why.
 *   A var splice WRITES rather than reads. LaunchService plants ~8 `CLAUDE_CODE_*` values into
 *   the client's environment (buildEnv). Those are outbound, and this row's denominator is the
 *   READ seam, so they are out of scope here by definition — worth its own wall, not worth
 *   quietly widening this one's denominator to a mix of two directions.
 *   A var read outside the JVM — install.sh, the shim, packaging. Different source language
 *   and a different seam; naming them here would be a hand list.
 *   A seam-shaped identifier that is not the seam. The scan keys off the two spellings the
 *   port is threaded under, `env` and `envReader`, measured to be the only two in main
 *   sources. A local `env` of some other type, called with a literal, would be counted. What
 *   is already handled: the same text inside a STRING literal (McpSharing.kt:171 holds the
 *   message "malformed env (expected string values)", which a first draft of this scan
 *   reported as a computed seam site) and inside a comment.
 *   Whether the documented prose is CORRECT. Only the name token and the presence of the
 *   precedence chain are machine-checkable; that a var's description is true is not.
 *
 * SELFTEST. --selftest builds a temp tree and proves BOTH directions: GREEN on the compliant
 * form (literal, const, knob alias and a reasoned retirement), GREEN WITH A COUNT on the
 * boring one-var tree; RED naming a synthetic var injected into a temp copy of the source
 * (the mutation this row requires), RED on a synthetic knob alias, RED on an unreasoned
 * retirement, RED when the header block is absent, RED when the block exists but omits the
 * precedence chain, RED when a var is named only OUTSIDE the block, RED on a direct
 * System.getenv call, RED on zero seam sites, and RED on zero knob aliases.
 *
 * Usage:
 *     bun checks/config/env-vars-documented.ts check <root>
 *     bun checks/config/env-vars-documented.ts report <root>
 *     bun checks/config/env-vars-documented.ts --selftest
 *
 * A BARE RUN IS `check` ON THE REPO ROOT — the one gating mode, so there is no non-gating
 * default to mis-invoke. `report` is the census and sits behind an explicit verb the gate
 * never uses.
 */
import { existsSync, mkdirSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";

// parents[2]: this file lives at checks/config/, so the repo root is two levels up.
const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");

// The knob half of the denominator. Fixed path on purpose: a checker that silently loses
// its source is a checker that passes.
const KNOB_REL = "core/src/main/kotlin/splice/core/config/Knob.kt";

// Where main sources live. The seam scan walks these.
// restructure PR 3: :client is the first module to live outside gateway/, so the production
// universe is no longer one `gateway/*` pattern. A source root this checker stops walking is a
// denominator that shrinks in silence, which is the one failure every ratchet here exists to
// prevent — so the list names every §2.2 module home, the ones that exist and the ones the next
// module commits create (a glob over an absent directory matches nothing, so the denominator can
// only grow), until PR 5 hands these checkers the build-derived source units of tools/gate.
const MAIN_GLOBS = [
  "gateway/*/src/main/**/*.kt", "client/src/main/**/*.kt", "core/src/main/**/*.kt", "upstream/src/main/**/*.kt",
  "dialects/*/src/main/**/*.kt", "providers/*/src/main/**/*.kt", "daemon/*/src/main/**/*.kt", "app/src/main/**/*.kt",
  "quality/*/src/main/**/*.kt",
];

// The one file an operator copies to write a config.
const SURFACE = "config/splice.example.toml";

// The header block that documents the environment layer.
// The sentinel tolerates the file's own box-drawing decoration
// (`# ── ENVIRONMENT VARIABLES ──────`) but nothing that carries meaning: only `#`,
// whitespace and rule characters may sit between the comment marker and the phrase, so a
// sentence that merely mentions environment variables cannot pass as the block header.
const BLOCK_SENTINEL = /^[ \t]*#[ \t#─━═=—–*_.-]*ENVIRONMENT VARIABLES\b/i;
const PRECEDENCE_CHAIN = /env[ \t]*>[ \t]*TOML[ \t]*>[ \t]*default/i;

// The seam's two spellings in main sources, called or `.invoke`d.
const SEAM_CALL = /(?<![\w.])(env|envReader)[ \t]*(?:\.invoke[ \t]*)?\(/g;
// A direct read the seam scan cannot see. `System::getenv` (a reference handed to an
// EnvReader) is injection and deliberately NOT matched.
const DIRECT_GETENV = /(?<![\w:])(?:(?:java\.lang\.)?System\s*\.\s*)?getenv[ \t]*\(/g;
const CONST_DECL = /\bconst\s+val\s+([A-Za-z_]\w*)\s*(?::[^=]+)?=\s*"([^"]*)"/g;
const STRING_ARG = /^\s*"([^"]*)"\s*$/;
const IDENT_ARG = /^\s*([A-Za-z_]\w*)\s*$/;

const ENUM_DECL = /\benum\s+class\s+Knob\b/;
const ENTRY_HEAD = /^\s*([A-Z][A-Z0-9_]*)\s*\(/s;

/** One env var name and the first place it is read. */
interface EnvName {
  name: string;
  kind: string;
  where: string;
}

function escapeRe(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function pyRepr(s: string): string {
  return `'${s.replace(/\\/g, "\\\\").replace(/'/g, "\\'")}'`;
}

/** Replace line and block comment bodies with spaces, preserving length and newlines.
 *
 *  Length-preserving on purpose: every finding this checker reports is a file:line, and a
 *  stripper that deleted bytes would report the wrong line. String literals are left
 *  intact — they are the denominator. */
function blankComments(source: string): string {
  const out = source.split("");
  let i = 0;
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
      let end = source.indexOf("\n", i);
      end = end < 0 ? source.length : end;
      for (let j = i; j < end; j += 1) out[j] = " ";
      i = end;
      continue;
    }
    if (ch === "/" && i + 1 < source.length && source[i + 1] === "*") {
      let end = source.indexOf("*/", i + 2);
      end = end < 0 ? source.length : end + 2;
      for (let j = i; j < end; j += 1) if (out[j] !== "\n") out[j] = " ";
      i = end;
      continue;
    }
    i += 1;
  }
  return out.join("");
}

/** (start, end) of every string literal body in comment-blanked Kotlin.
 *
 *  blankComments deliberately leaves literals intact — they ARE the denominator — which
 *  means a literal can itself contain something that looks like a seam call. It does:
 *  McpSharing.kt:171 holds the message "malformed env (expected string values)", and a
 *  naive regex reported it as a computed seam site. Matches starting inside one of these
 *  spans are dropped. */
function stringSpans(code: string): [number, number][] {
  const spans: [number, number][] = [];
  let i = 0;
  while (i < code.length) {
    const ch = code[i];
    if (ch === '"' || ch === "'") {
      const quote = ch;
      let j = i + 1;
      let escape = false;
      while (j < code.length) {
        if (escape) escape = false;
        else if (code[j] === "\\") escape = true;
        else if (code[j] === quote) break;
        j += 1;
      }
      spans.push([i + 1, Math.min(j, code.length)]);
      i = j + 1;
      continue;
    }
    i += 1;
  }
  return spans;
}

const insideString = (spans: [number, number][], index: number): boolean =>
  spans.some(([start, end]) => start <= index && index < end);

/** Index of the paren closing the one at [openIndex], string-aware. */
function closeParen(text: string, openIndex: number): number | null {
  let depth = 0;
  let i = openIndex;
  let inString = false;
  let quote = "";
  let escape = false;
  while (i < text.length) {
    const ch = text[i];
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
    if ("({[".includes(ch)) depth += 1;
    else if (")}]".includes(ch)) {
      depth -= 1;
      if (depth === 0) return i;
    }
    i += 1;
  }
  return null;
}

const lineOf = (text: string, index: number): number => text.slice(0, index).split("\n").length;

function mainSources(root: string): string[] {
  const found: string[] = [];
  for (const pattern of MAIN_GLOBS) {
    found.push(...[...new Bun.Glob(pattern).scanSync({ cwd: root, followSymlinks: true })].sort());
  }
  return found;
}

/** Return (names, computedSites, problems) from the EnvReader seam in main sources. */
function scanSeam(root: string): { names: EnvName[]; computed: string[]; problems: string[] } {
  const names: EnvName[] = [];
  const computed: string[] = [];
  const problems: string[] = [];
  let sites = 0;
  for (const rel of mainSources(root)) {
    const raw = readFileSync(join(root, rel), "utf8");
    const code = blankComments(raw);
    const spans = stringSpans(code);
    const constants = new Map<string, string>();
    for (const m of code.matchAll(CONST_DECL)) constants.set(m[1], m[2]);

    for (const direct of code.matchAll(DIRECT_GETENV)) {
      if (insideString(spans, direct.index as number)) continue;
      problems.push(
        `DIRECT READ OUTSIDE THE SEAM: ${rel}:${lineOf(code, direct.index as number)} calls ` +
          "getenv() directly — this scan cannot see the name it reads, so the " +
          "denominator is incomplete and no green from this run is true. Inject an " +
          "EnvReader instead (kt-no-system-getenv)",
      );
    }

    for (const call of code.matchAll(SEAM_CALL)) {
      const at = call.index as number;
      if (insideString(spans, at)) continue;
      const openIndex = code.indexOf("(", at + call[0].length - 1);
      const end = closeParen(code, openIndex);
      if (end === null) {
        problems.push(
          `${rel}:${lineOf(code, at)}: a seam call's argument list does ` +
            "not close — the argument cannot be read",
        );
        continue;
      }
      const arg = code.slice(openIndex + 1, end);
      // `EnvReader(env)` / `foo(envReader)` style pass-throughs are not reads.
      const passthrough = IDENT_ARG.exec(arg);
      if (passthrough !== null && (passthrough[1] === "env" || passthrough[1] === "envReader")) continue;
      sites += 1;
      const where = `${rel}:${lineOf(code, at)}`;
      const literal = STRING_ARG.exec(arg);
      if (literal !== null) {
        names.push({ name: literal[1], kind: "literal", where });
        continue;
      }
      const ident = IDENT_ARG.exec(arg);
      if (ident !== null && constants.has(ident[1])) {
        names.push({ name: constants.get(ident[1]) as string, kind: "const", where });
        continue;
      }
      computed.push(`${where}  ${call[1]}(${arg.trim()})`);
    }
  }
  if (sites === 0) {
    problems.push(
      "no EnvReader seam call site found in main sources — the scan has lost the " +
        "seam, and a gateway that reads no environment is not the gateway this wall " +
        "was written against",
    );
  }
  return { names, computed, problems };
}

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

/** Every name in a Knob entry's envNames list — read at ConfigService's
 *  `knob.envNames.firstNotNullOfOrNull { name -> envReader(name) }`. */
function scanKnobAliases(root: string): { names: EnvName[]; problems: string[] } {
  const path = join(root, KNOB_REL);
  if (!existsSync(path)) {
    return {
      names: [],
      problems: [
        `${KNOB_REL}: missing — its envNames lists are the largest part of the ` +
          "denominator, so its absence cannot pass",
      ],
    };
  }
  const raw = readFileSync(path, "utf8");
  const code = blankComments(raw);
  const decl = ENUM_DECL.exec(code);
  if (decl === null) {
    return {
      names: [],
      problems: [
        `${KNOB_REL}: no \`enum class Knob\` declaration found — the enum has moved or ` +
          "been renamed, so this run has no knob denominator and must not pass",
      ],
    };
  }
  const openIndex = code.indexOf("(", decl.index + decl[0].length);
  const ctorEnd = closeParen(code, openIndex);
  if (ctorEnd === null) return { names: [], problems: [`${KNOB_REL}: the Knob primary constructor could not be parsed`] };
  const brace = code.indexOf("{", ctorEnd);
  const bodyEnd = closeParen(code, brace);
  if (bodyEnd === null) return { names: [], problems: [`${KNOB_REL}: the Knob enum body could not be parsed`] };
  const entriesText = splitTopLevel(code.slice(brace + 1, bodyEnd), ";")[0];

  const names: EnvName[] = [];
  const problems: string[] = [];
  for (const part of splitTopLevel(entriesText, ",")) {
    const head = ENTRY_HEAD.exec(part);
    if (head === null) continue;
    const entry = head[1];
    const argsOpen = part.indexOf("(", head.index + head[0].length - 1);
    const argsEnd = closeParen(part, argsOpen);
    if (argsEnd === null) {
      problems.push(`${KNOB_REL}: ${entry} argument list could not be parsed`);
      continue;
    }
    const listed = splitTopLevel(part.slice(argsOpen + 1, argsEnd), ",").filter((arg) =>
      arg.trim().startsWith("listOf("),
    );
    if (listed.length === 0) {
      problems.push(
        `${KNOB_REL}: ${entry} declares no envNames listOf(...) — its env aliases ` +
          "cannot be read from the source",
      );
      continue;
    }
    const inner = listed[0].trim();
    const innerEnd = closeParen(inner, inner.indexOf("("));
    if (innerEnd === null) {
      problems.push(`${KNOB_REL}: ${entry} envNames list could not be parsed`);
      continue;
    }
    for (const alias of splitTopLevel(inner.slice(inner.indexOf("(") + 1, innerEnd), ",")) {
      const literal = STRING_ARG.exec(alias);
      if (literal === null) {
        if (alias.trim()) {
          problems.push(
            `${KNOB_REL}: ${entry} lists a non-literal env alias ` +
              `(${pyRepr(alias.trim())}) — the name cannot be read from the source`,
          );
        }
        continue;
      }
      names.push({ name: literal[1], kind: "knob", where: `Knob.${entry}` });
    }
  }
  if (names.length === 0) {
    problems.push(
      `${KNOB_REL}: parsed 0 env aliases — refusing to pass vacuously, because a ` +
        "green over an empty denominator is what this wall exists to prevent",
    );
  }
  return { names, problems };
}

/** name -> first read site, plus the computed residue and any untrusted-parse problems. */
function denominator(root: string): { names: Map<string, EnvName>; computed: string[]; problems: string[] } {
  const seam = scanSeam(root);
  const knob = scanKnobAliases(root);
  const problems = [...seam.problems, ...knob.problems];
  const names = new Map<string, EnvName>();
  for (const env of [...seam.names, ...knob.names]) if (!names.has(env.name)) names.set(env.name, env);
  if (names.size === 0 && problems.length === 0) {
    problems.push(
      "parsed 0 environment variable names — refusing to pass vacuously over an " + "empty denominator",
    );
  }
  return { names, computed: seam.computed, problems };
}

/** The ENVIRONMENT VARIABLES block: the sentinel line plus the contiguous run of
 *  comment and blank lines after it. Returns (block text, sentinel line number). */
function headerBlock(text: string): { block: string | null; line: number } {
  const lines = text.split("\n");
  for (let index = 0; index < lines.length; index += 1) {
    if (BLOCK_SENTINEL.test(lines[index])) {
      const block = [lines[index]];
      for (const following of lines.slice(index + 1)) {
        const stripped = following.trim();
        if (stripped === "" || stripped.startsWith("#")) {
          block.push(following);
          continue;
        }
        break;
      }
      return { block: block.join("\n"), line: index + 1 };
    }
  }
  return { block: null, line: 0 };
}

/** The name as a whole token. CASE-SENSITIVE: `no_proxy` and `NO_PROXY` are two
 *  variables and the gateway reads both. */
const nameToken = (name: string): RegExp => new RegExp(`(?<![\\w-])${escapeRe(name)}(?![\\w-])`);

function retiredReason(text: string, name: string): { marked: boolean; reason: string } {
  const pattern = new RegExp(
    `^[ \\t]*#[ \\t]*retired:[ \\t]*${escapeRe(name)}(?![A-Za-z0-9_-])(.*)$`,
    "m",
  );
  const match = pattern.exec(text);
  if (match === null) return { marked: false, reason: "" };
  return { marked: true, reason: match[1].trim().replace(/^[—:-]+/, "").trim() };
}

function audit(root: string): string[] {
  const { names, problems } = denominator(root);
  if (problems.length > 0) {
    // An untrusted parse is terminal: a disposition report over a denominator that
    // cannot be trusted would be a green wearing the wrong number.
    return problems;
  }

  const surface = join(root, SURFACE);
  if (!existsSync(surface)) {
    return [
      `${SURFACE}: disposition surface missing — a surface that cannot be read ` + "cannot document anything",
    ];
  }
  const text = readFileSync(surface, "utf8");
  let { block, line } = headerBlock(text);
  if (block === null) {
    problems.push(
      `${SURFACE}: no \`# ENVIRONMENT VARIABLES\` header block — environment is the ` +
        "highest-precedence config layer and the file that teaches the config does not " +
        `mention it. Add the block, state the precedence chain (env > TOML > default) ` +
        `in it, and describe each of the ${names.size} variables splice reads`,
    );
    block = "";
  } else if (!PRECEDENCE_CHAIN.test(block)) {
    problems.push(
      `${SURFACE}:${line}: the ENVIRONMENT VARIABLES block does not state the ` +
        "precedence chain — a var list that does not say env beats TOML beats default " +
        "leaves the one fact an operator needs unwritten. Write `env > TOML > default`",
    );
  }

  for (const name of [...names.keys()].sort()) {
    const env = names.get(name) as EnvName;
    const { marked, reason } = retiredReason(text, name);
    if (marked) {
      if (!reason) {
        problems.push(
          `${SURFACE}: ${name} is retired with NO reason — a retirement without a ` +
            "written reason is an absence wearing a label",
        );
      }
      continue;
    }
    if (nameToken(name).test(block)) continue;
    const outside = nameToken(name).test(text) ? "" : " (not named anywhere in the file)";
    problems.push(
      `NO DISPOSITION: ${name} (${env.kind}, read at ${env.where}) is not documented in ` +
        `the ENVIRONMENT VARIABLES block of ${SURFACE}${outside}; document it there ` +
        `against the precedence chain, or retire it with \`# retired: ${name} — <reason>\``,
    );
  }
  return problems;
}

// ── selftest fixtures ─────────────────────────────────────────────────────────────────

const SEAM_SOURCE_REL = "app/src/main/kotlin/splice/app/cli/Fixture.kt";
const COMPUTED_SOURCE_REL = "app/src/main/kotlin/splice/app/cli/Computed.kt";

const SEAM_SOURCE = `package splice.app.cli

internal object Fixture {
    fun paths(env: EnvReader = EnvReader(System::getenv)): String? {
        // env("COMMENTED_OUT_VAR") — a commented read is not a read.
        val config = env("SPLICE_CONFIG")
        val openRouter = env(OPENROUTER_KEY)
        val explicit = env.invoke("XDG_CONFIG_HOME")
        return config ?: openRouter ?: explicit
    }
}

private const val OPENROUTER_KEY = "OPENROUTER_API_KEY"
`;

const COMPUTED_SOURCE = `package splice.app.cli

internal object Computed {
    fun key(envVar: String, env: EnvReader): String? = env(envVar)

    // The McpSharing.kt:171 shape: a seam-shaped call inside a STRING literal.
    fun complain(): String = "malformed env (expected string values)"
}
`;

const KNOB_SOURCE = `package splice.core.config

public enum class Knob(
    public val key: String,
    public val kind: KnobKind,
    public val envNames: List<String>,
) {
    PORT("port", KnobKind.NUMBER, listOf("CODEX_PROXY_PORT")),
    // A prose comment with (parens) and "quotes" a naive walk would choke on.
    DEBUG("debug", KnobKind.BOOL, listOf("CLAUDEX_DEBUG", "CODEX_PROXY_DEBUG")),
    GROK_PORT("grokPort", KnobKind.NUMBER, listOf("GROK_PROXY_PORT")),
}
`;

const COMPLIANT_DOC = `[daemon]
control_port = 3096

# ── ENVIRONMENT VARIABLES ──────────────────────────────────────────────────────
# Precedence: env > TOML > default. An env var set in the daemon's environment wins
# over anything in this file.
#   SPLICE_CONFIG        absolute path to splice.toml; overrides the XDG lookup
#   XDG_CONFIG_HOME      base for the default config lookup (~/.config when unset)
#   OPENROUTER_API_KEY   OpenRouter bearer, read by ` + "`splice setup`" + ` detection
#   CODEX_PROXY_PORT     the codex head's listen port (knob ` + "`port`" + `)
#   CLAUDEX_DEBUG        verbose daemon logging (knob ` + "`debug`" + `)
# retired: CODEX_PROXY_DEBUG — superseded by CLAUDEX_DEBUG in v0.3.0; still read as an alias
# retired: GROK_PROXY_PORT — the grok head takes its port from [heads.*.port]

[providers.openrouter]
dialect = "openai-chat"
`;

const NO_BLOCK_DOC = COMPLIANT_DOC.replace(
  "# ── ENVIRONMENT VARIABLES ──────────────────────────────────────────────────────\n",
  "",
);

const NO_CHAIN_DOC = COMPLIANT_DOC.replace(
  "# Precedence: env > TOML > default. An env var set in the daemon's environment wins\n" +
    "# over anything in this file.\n",
  "# Set these in the daemon's environment.\n",
);

// SPLICE_CONFIG named only OUTSIDE the block — past a live TOML line, which is what ends
// the block: the drift a file-wide token search misses.
const OUTSIDE_BLOCK_DOC =
  COMPLIANT_DOC.replace(
    "#   SPLICE_CONFIG        absolute path to splice.toml; overrides the XDG lookup\n",
    "",
  ) + "# SPLICE_CONFIG is mentioned down here, in an unrelated provider comment.\n";

const RETIRED_NOREASON_DOC = COMPLIANT_DOC.replace(
  "# retired: GROK_PROXY_PORT — the grok head takes its port from [heads.*.port]",
  "# retired: GROK_PROXY_PORT —",
);

// The boring case: one var, one knob alias, documented.
const BORING_SEAM = `package splice.app.cli

internal object Fixture {
    fun path(env: EnvReader): String? = env("SPLICE_CONFIG")
}
`;

const BORING_KNOB = `package splice.core.config

public enum class Knob(
    public val key: String,
    public val envNames: List<String>,
) {
    PORT("port", listOf("CODEX_PROXY_PORT")),
}
`;

const BORING_DOC = `# ENVIRONMENT VARIABLES
# Precedence: env > TOML > default.
#   SPLICE_CONFIG      absolute path to splice.toml
#   CODEX_PROXY_PORT   the codex head's listen port
`;

const DIRECT_GETENV_SOURCE = `package splice.app.cli

internal object Sneaky {
    fun home(): String? = System.getenv("HOME")
}
`;

const NO_SEAM_SOURCE = `package splice.app.cli

internal object Inert {
    fun nothing(): String = "no environment here"
}
`;

const EMPTY_KNOB_SOURCE = `package splice.core.config

public enum class Knob(
    public val key: String,
    public val envNames: List<String>,
) {
}
`;

function writeTree(
  root: string,
  doc: string,
  seam: string = SEAM_SOURCE,
  knob: string = KNOB_SOURCE,
  computed: string | null = COMPUTED_SOURCE,
  extra: [string, string] | null = null,
): void {
  for (const [rel, content] of [
    [SEAM_SOURCE_REL, seam],
    [KNOB_REL, knob],
  ] as const) {
    const p = join(root, rel);
    mkdirSync(dirname(p), { recursive: true });
    writeFileSync(p, content, "utf8");
  }
  const path = join(root, COMPUTED_SOURCE_REL);
  if (computed === null) {
    if (existsSync(path)) rmSync(path);
  } else {
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, computed, "utf8");
  }
  if (extra !== null) {
    const extraPath = join(root, extra[0]);
    mkdirSync(dirname(extraPath), { recursive: true });
    writeFileSync(extraPath, extra[1], "utf8");
  }
  const surface = join(root, SURFACE);
  mkdirSync(dirname(surface), { recursive: true });
  writeFileSync(surface, doc, "utf8");
}

function selftest(): number {
  const failures: string[] = [];
  const has = (hits: string[], ...needles: string[]): boolean =>
    hits.some((h) => needles.every((n) => h.includes(n)));
  const root = join(tmpdir(), `env-vars-${process.pid}-${Math.random().toString(36).slice(2)}`);
  try {
    writeTree(root, COMPLIANT_DOC);
    let hits = audit(root);
    if (hits.length > 0) {
      failures.push(
        "compliant tree must be GREEN (literal, const, .invoke, knob alias, two " +
          "reasoned retirements): " + hits.join("; "),
      );
    }
    let den = denominator(root);
    if (den.problems.length > 0) {
      failures.push(`the compliant denominator must be trusted, got: ${den.problems}`);
    }
    const expected = [
      "CLAUDEX_DEBUG",
      "CODEX_PROXY_DEBUG",
      "CODEX_PROXY_PORT",
      "GROK_PROXY_PORT",
      "OPENROUTER_API_KEY",
      "SPLICE_CONFIG",
      "XDG_CONFIG_HOME",
    ];
    const got = [...den.names.keys()].sort();
    if (JSON.stringify(got) !== JSON.stringify(expected)) failures.push(`denominator wrong: ${got}`);
    if (den.names.has("COMMENTED_OUT_VAR")) {
      failures.push("a read inside a comment must not enter the denominator");
    }
    if (den.computed.length !== 1 || !den.computed[0].includes("env(envVar)")) {
      failures.push(
        "the computed residue must be exactly the one real computed site — a " +
          "seam-shaped call inside a string literal is not a seam site — got: " +
          `${den.computed}`,
      );
    }

    // The BORING case: one literal, one knob alias, and the count must come out at two.
    writeTree(root, BORING_DOC, BORING_SEAM, BORING_KNOB, null);
    hits = audit(root);
    if (hits.length > 0) failures.push(`the one-var tree must be GREEN, got: ${hits}`);
    den = denominator(root);
    if (
      den.problems.length > 0 ||
      JSON.stringify([...den.names.keys()].sort()) !== JSON.stringify(["CODEX_PROXY_PORT", "SPLICE_CONFIG"]) ||
      den.computed.length !== 0
    ) {
      failures.push(
        "the boring tree must parse to exactly 2 names and no computed residue, " +
          `got ${[...den.names.keys()].sort()} computed=${den.computed} problems=${den.problems}`,
      );
    }

    // The mutation this row requires: a synthetic literal read injected into a temp copy.
    const mutated = SEAM_SOURCE.replace(
      'val config = env("SPLICE_CONFIG")',
      'val config = env("SPLICE_FAKE_NEW_VAR") ?: env("SPLICE_CONFIG")',
    );
    if (mutated === SEAM_SOURCE) {
      failures.push("the seam mutation did not apply");
    } else {
      writeTree(root, COMPLIANT_DOC, mutated);
      hits = audit(root);
      if (!hits.some((h) => h.includes("SPLICE_FAKE_NEW_VAR"))) {
        failures.push(`a synthetic literal read must be RED BY NAME, got: ${hits}`);
      }
    }

    const mutatedKnob = KNOB_SOURCE.replace(
      'GROK_PORT("grokPort", KnobKind.NUMBER, listOf("GROK_PROXY_PORT")),',
      'GROK_PORT("grokPort", KnobKind.NUMBER, listOf("GROK_PROXY_PORT", "SPLICE_FAKE_ALIAS")),',
    );
    if (mutatedKnob === KNOB_SOURCE) {
      failures.push("the knob mutation did not apply");
    } else {
      writeTree(root, COMPLIANT_DOC, SEAM_SOURCE, mutatedKnob);
      hits = audit(root);
      if (!hits.some((h) => h.includes("SPLICE_FAKE_ALIAS"))) {
        failures.push(`a synthetic knob alias must be RED BY NAME, got: ${hits}`);
      }
    }

    writeTree(root, RETIRED_NOREASON_DOC);
    hits = audit(root);
    if (!has(hits, "GROK_PROXY_PORT", "NO reason")) {
      failures.push(`a retirement with an empty reason must be RED by name, got: ${hits}`);
    }
    if (hits.filter((h) => h.includes("GROK_PROXY_PORT")).length !== 1) {
      failures.push(`an unreasoned retirement is ONE problem, not a duplicate pair, got: ${hits}`);
    }

    writeTree(root, NO_BLOCK_DOC);
    hits = audit(root);
    if (!hits.some((h) => h.includes("no `# ENVIRONMENT VARIABLES` header block"))) {
      failures.push(`a missing header block must be RED, got: ${hits}`);
    }

    writeTree(root, NO_CHAIN_DOC);
    hits = audit(root);
    if (!hits.some((h) => h.includes("precedence chain"))) {
      failures.push(`a block without the precedence chain must be RED, got: ${hits}`);
    }

    writeTree(root, OUTSIDE_BLOCK_DOC);
    hits = audit(root);
    if (!hits.some((h) => h.includes("NO DISPOSITION: SPLICE_CONFIG"))) {
      failures.push(`a var named only OUTSIDE the block must be RED by name, got: ${hits}`);
    }
    if (hits.some((h) => h.includes("not named anywhere in the file") && h.includes("SPLICE_CONFIG"))) {
      failures.push("a var named outside the block must NOT be reported as absent from the file");
    }

    writeTree(root, COMPLIANT_DOC, SEAM_SOURCE, KNOB_SOURCE, COMPUTED_SOURCE, [
      "app/src/main/kotlin/splice/app/cli/Sneaky.kt",
      DIRECT_GETENV_SOURCE,
    ]);
    hits = audit(root);
    if (!hits.some((h) => h.includes("DIRECT READ OUTSIDE THE SEAM"))) {
      failures.push(`a direct System.getenv call must be RED, got: ${hits}`);
    }
    rmSync(join(root, "app/src/main/kotlin/splice/app/cli/Sneaky.kt"));

    writeTree(root, COMPLIANT_DOC, NO_SEAM_SOURCE, KNOB_SOURCE, null);
    hits = audit(root);
    if (!hits.some((h) => h.includes("has lost the seam"))) {
      failures.push(`zero seam call sites must be RED, got: ${hits}`);
    }

    writeTree(root, COMPLIANT_DOC, SEAM_SOURCE, EMPTY_KNOB_SOURCE);
    hits = audit(root);
    if (!hits.some((h) => h.includes("refusing to pass vacuously"))) {
      failures.push(`zero knob aliases must be RED, got: ${hits}`);
    }

    writeTree(root, COMPLIANT_DOC);
    rmSync(join(root, SURFACE));
    hits = audit(root);
    if (!hits.some((h) => h.includes("disposition surface missing"))) {
      failures.push(`a missing surface must be RED, got: ${hits}`);
    }
  } finally {
    rmSync(root, { recursive: true, force: true });
  }

  if (failures.length > 0) {
    process.stdout.write("env-vars-documented SELFTEST FAIL:\n");
    for (const failure of failures) process.stdout.write("  " + failure + "\n");
    return 1;
  }
  process.stdout.write(
    "env-vars-documented SELFTEST OK — a literal read, a const read, an explicit " +
      ".invoke, a knob alias and two reasoned retirements are green, and the one-var tree " +
      "is green with a count of 2; a synthetic literal read, a synthetic knob alias, an " +
      "unreasoned retirement, a missing header block, a block without the precedence " +
      "chain, a var named only outside the block, a direct System.getenv call, zero seam " +
      "sites, zero knob aliases and a missing surface are all red by name\n",
  );
  return 0;
}

function report(root: string): void {
  const { names, computed, problems } = denominator(root);
  const surface = join(root, SURFACE);
  const text = existsSync(surface) ? readFileSync(surface, "utf8") : "";
  const { block, line } = headerBlock(text);
  process.stdout.write(
    `env-vars-documented: ${names.size} env var names read through the EnvReader seam ` +
      `(${KNOB_REL} envNames + main-source literals)\n`,
  );
  for (const problem of problems) process.stdout.write(`  UNTRUSTED: ${problem}\n`);
  process.stdout.write(
    `  header block: ${block ? "present at " + SURFACE + ":" + line : "ABSENT"}` +
      (block ? `, precedence chain ${PRECEDENCE_CHAIN.test(block) ? "stated" : "MISSING"}` : "") +
      "\n",
  );
  let documented = 0;
  for (const name of [...names.keys()].sort()) {
    const env = names.get(name) as EnvName;
    const { marked, reason } = retiredReason(text, name);
    let where: string;
    if (marked && reason) where = "retired";
    else if (block && nameToken(name).test(block)) where = "block";
    else where = "NO DISPOSITION";
    if (where !== "NO DISPOSITION") documented += 1;
    process.stdout.write(`  ${name.padEnd(32)} ${env.kind.padEnd(8)} ${env.where.padEnd(60)} ${where}\n`);
  }
  process.stdout.write(`  COMPUTED seam arguments (${computed.length}) — excluded, see this file's header:\n`);
  for (const site of computed) process.stdout.write(`    ${site}\n`);
  const undocumented = [...names.keys()]
    .sort()
    .filter((name) => !(retiredReason(text, name).reason || (block && nameToken(name).test(block))));
  process.stdout.write(
    `  documented ${documented}/${names.size}; undocumented ${undocumented.length}: ` +
      undocumented.join(", ") +
      "\n",
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
    process.stderr.write("env-vars-documented: tree missing\n");
    return 1;
  }
  root = realpathSync(root);
  if (argv.includes("report")) {
    report(root);
    return 0;
  }
  const problems = audit(root);
  if (problems.length > 0) {
    process.stdout.write("env-vars-documented RED:\n");
    for (const problem of problems) process.stdout.write("  " + problem + "\n");
    return 1;
  }
  process.stdout.write(
    "env-vars-documented GREEN: every env var read through the EnvReader seam has a " +
      `disposition in the ENVIRONMENT VARIABLES block of ${SURFACE}\n`,
  );
  return 0;
}

process.exit(main(process.argv.slice(2)));
