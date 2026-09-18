#!/usr/bin/env bun
/**
 * V4-98 — the three hand-authored model rosters agree with config/splice.example.toml.
 *
 * WHY THIS EXISTS. splice declares its model rows THREE times, by hand, in three
 * languages:
 *
 *   config/splice.example.toml                                     the reference an
 *       operator copies — ids, labels, context windows, per-provider commentary. This
 *       file is the SOURCE: it is the one an operator reads, and the one whose windows
 *       carry the measured justification (see the k3[1m] note about 1e6 vs 1048576).
 *   gateway/app/src/main/kotlin/splice/app/cli/AddProfileCatalog.kt  the rows
 *       `splice add <vendor>` renders into the operator's file.
 *   gateway/app/src/main/kotlin/splice/app/TopologyLoader.kt         DEFAULT_TOML, the
 *       starter materialized on first run when no config exists.
 *
 * Nothing paired them. A context window corrected in the example stayed wrong in the two
 * emitters, and a model id added to an emitter never had to exist in the reference at all
 * — which is exactly today's state: `splice add openrouter` and the first-run starter both
 * declare eight OpenRouter ids the example never mentions. A window that disagrees is not
 * cosmetic: TopologyLoader plants the pinned row's window as CLAUDE_CODE_MAX_CONTEXT_TOKENS
 * and ModelCatalog.usageScale compacts every other row against its declared number, so a
 * roster that drifted by 4.6% compacts 4.6% early or late with nothing logging it.
 *
 * This is the §24 shape: three hand-authored lists, two of which agreed with each other and
 * disagreed with the reference in silence, and no denominator enumerated from outside.
 *
 * THE LAW. Every model row a DERIVED roster declares must exist in the example's roster for
 * the SAME provider, with a byte-identical context window. The example may declare more —
 * it is the full reference and the emitters are curated starters — so a superset in the
 * example is not drift. A derived id the example does not carry, or a window that differs,
 * is RED BY NAME.
 *
 * THE JOIN KEY IS base_url, NEVER the table name. The provider keys already disagree on
 * purpose: the catalog calls xAI `grok` and Anthropic `claude` (they name the WRAPPER an
 * operator types), while the example calls them `xai` and `anthropic` (they name the
 * VENDOR). A hand-written alias map between them would be a fourth hand-authored list, and
 * this checker exists because hand-authored lists agree with each other. base_url is the
 * provider's actual identity, it is declared in all three files, and it is what the daemon
 * dials. Two example providers sharing one base_url make the join ambiguous and are RED.
 *
 * DENOMINATORS, from the SOURCES, never a hand list.
 *   The example's providers and their model rows are parsed out of the TOML on disk.
 *   The catalog's rows are parsed out of AddProfileCatalog.kt's AddProfile/AddModel calls,
 *     with the WINDOW_* constants resolved from their own `private const val` declarations
 *     in that same file.
 *   DEFAULT_TOML is extracted from TopologyLoader.kt by its marker and parsed as TOML.
 *   A vendor added to any of the three is in scope with no edit to this file.
 *
 * FOUR GUARDS REFUSE A VACUOUS PASS, because a green over an empty denominator is the whole
 * failure this wall exists to prevent:
 *   - the example must yield at least one provider carrying at least one model row;
 *   - each derived source must yield at least one roster carrying at least one model row;
 *   - the model rows parsed out of each TOML must equal the count of `[[providers.*.models]]`
 *     headers in its comment-stripped text, and the AddModel rows parsed out of the catalog
 *     must equal the count of `AddModel(` calls in its comment-stripped text — a parser that
 *     has drifted off its source produces a list no run can be trusted with;
 *   - the number of (id, window) comparisons actually performed must be non-zero.
 *
 * DISPOSITION. Every derived roster is accounted for in exactly one of three forms:
 *   agrees   — joined to an example provider by base_url, every id present, every window equal;
 *   no-roster— the roster declares NO base_url and NO models, so there is nothing to compare.
 *              That is the generic `api-key` row, whose base URL and models the operator
 *              supplies on the command line. The condition is COMPUTED (models empty AND
 *              base_url absent), not a named exemption — a row that grew models while keeping
 *              a null base_url stops qualifying and goes red;
 *   RED      — anything else, by name.
 * Absence is not a disposition: a roster that matches no example provider fails rather than
 * being skipped.
 *
 * WHAT IS NOT CAUGHT, and why.
 *   A label that disagrees. The example says "Codex 5.6 Sol" where the catalog says
 *   "GPT-5.6 Sol", and "Kimi K3 (256k)" where the catalog says "Kimi K3 256k". Those are
 *   display strings shown in different places (a config comment vs a picker row) and pinning
 *   them would red the wall for a copy edit. Ids and windows are the wire.
 *   A model the example declares and an emitter omits. Deliberate: the emitters are curated
 *   starters — the example lists eight Codex rows and `splice add codex` ships three.
 *   Slots, rates and quirks. Other walls own those surfaces; this one owns id + window.
 *   A window that is wrong in the EXAMPLE. Nothing here validates the reference against the
 *   vendor — that is a live-probe job, not a static one. This wall makes the three agree.
 *
 * SELFTEST. --selftest builds temp trees and proves both directions: GREEN on a compliant
 * three-file tree (including the alias join and the example-superset case), GREEN on the
 * BORING case (one provider, one model), and RED BY NAME on each of: a window mutated in the
 * catalog, a synthetic id added to the catalog, a window mutated in DEFAULT_TOML, an id added
 * to DEFAULT_TOML, a roster whose base_url matches no example provider, a roster with models
 * but a null base_url, a duplicated base_url in the example, an empty example, an empty
 * derived roster set, a DEFAULT_TOML marker that cannot be found, and a parser/source count
 * disagreement in both the TOML and the Kotlin parser.
 *
 * Usage:
 *     bun checks/model-catalogs-single-source.ts check [<root>]
 *     bun checks/model-catalogs-single-source.ts report [<root>]
 *     bun checks/model-catalogs-single-source.ts --selftest
 *
 * A BARE RUN IS `check` — the one gating mode, so there is no non-gating default to mis-invoke.
 * `report` is the census and sits behind an explicit verb the gate never uses.
 */
import { existsSync, mkdirSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// parents[1]: this file lives at checks/, so the repo root is one level up.
const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");

// The source of truth. A fixed path on purpose: a checker that silently loses its source is
// a checker that passes.
const EXAMPLE_REL = "config/splice.example.toml";
const CATALOG_REL = "gateway/app/src/main/kotlin/splice/app/cli/AddProfileCatalog.kt";
const STARTER_REL = "gateway/app/src/main/kotlin/splice/app/TopologyLoader.kt";

const STARTER_MARKER = "DEFAULT_TOML";

const TABLE = /^[ \t]*(\[\[?)([^[\]\n]+)\]\]?[ \t]*$/;
const KV = /^[ \t]*([A-Za-z0-9_-]+)[ \t]*=[ \t]*(.*)$/;
const MODELS_HEADER = /^[ \t]*\[\[providers\.[^[\]\n]+\.models\]\][ \t]*$/gm;
const WINDOW_CONST = /\bconst\s+val\s+(WINDOW_\w+)\s*=\s*([0-9_]+)L?\b/g;
const ADD_MODEL_CALL = /\bAddModel\s*\(/g;
const ADD_PROFILE_CALL = /\bAddProfile\s*\(/g;
const NUMBER = /^([0-9_]+)L?$/;
const STRING_ARG = /^"((?:[^"\\]|\\.)*)"$/;

/** Python's repr() of a string. */
const pyRepr = (value: string): string => `'${value.replace(/\\/g, "\\\\").replace(/'/g, "\\'")}'`;

/** Python's repr() of a list of strings: `['a', 'b']`. */
const pyReprList = (items: string[]): string => `[${items.map(pyRepr).join(", ")}]`;

/** One provider's declared model rows, as parsed out of one file. */
class Roster {
  readonly label: string;
  readonly provider: string;
  baseUrl: string | null;
  readonly models: [string, number | null][] = [];

  constructor(label: string, provider: string, baseUrl: string | null) {
    this.label = label;
    this.provider = provider;
    this.baseUrl = baseUrl;
  }

  get where(): string {
    return `${this.label} [${this.provider}]`;
  }
}

/** Drop `#` comments, respecting quoted strings. Keeps line structure so a header
 *  regex over the stripped text still anchors. */
function stripTomlComments(text: string): string {
  const out: string[] = [];
  for (const line of text.split("\n")) {
    const kept: string[] = [];
    let inString = false;
    let quote = "";
    let escape = false;
    for (const ch of line) {
      if (inString) {
        kept.push(ch);
        if (escape) escape = false;
        else if (ch === "\\") escape = true;
        else if (ch === quote) inString = false;
        continue;
      }
      if (ch === '"' || ch === "'") {
        inString = true;
        quote = ch;
        kept.push(ch);
        continue;
      }
      if (ch === "#") break;
      kept.push(ch);
    }
    out.push(kept.join("").replace(/\s+$/, ""));
  }
  return out.join("\n");
}

/** Remove // and block comments, respecting string literals (including raw triple-quoted). */
function stripKotlinComments(source: string): string {
  const out: string[] = [];
  let i = 0;
  let inString = false;
  let quote = "";
  let escape = false;
  while (i < source.length) {
    const ch = source[i];
    if (inString) {
      out.push(ch);
      if (quote === '"""') {
        if (source.startsWith('"""', i)) {
          out.push('""');
          i += 3;
          inString = false;
          continue;
        }
        i += 1;
        continue;
      }
      if (escape) escape = false;
      else if (ch === "\\") escape = true;
      else if (ch === quote) inString = false;
      i += 1;
      continue;
    }
    if (source.startsWith('"""', i)) {
      inString = true;
      quote = '"""';
      out.push('"""');
      i += 3;
      continue;
    }
    if (ch === '"' || ch === "'") {
      inString = true;
      quote = ch;
      out.push(ch);
      i += 1;
      continue;
    }
    if (ch === "/" && source.startsWith("//", i)) {
      const nl = source.indexOf("\n", i);
      i = nl < 0 ? source.length : nl;
      continue;
    }
    if (ch === "/" && source.startsWith("/*", i)) {
      const end = source.indexOf("*/", i + 2);
      i = end < 0 ? source.length : end + 2;
      continue;
    }
    out.push(ch);
    i += 1;
  }
  return out.join("");
}

/** A TOML/Kotlin scalar's string value, or null when it is not a quoted string. */
function unquote(raw: string): string | null {
  const match = STRING_ARG.exec(raw.trim());
  if (match === null) return null;
  return match[1];
}

function parseNumber(raw: string): number | null {
  const match = NUMBER.exec(raw.trim());
  if (match === null) return null;
  return parseInt(match[1].replace(/_/g, ""), 10);
}

/** Providers and their `[[providers.X.models]]` rows out of TOML text.
 *
 *  `extra_windows` and every non-provider table (heads, daemon, quirks) are not model
 *  rosters and are deliberately skipped; only `[providers.X]` and the exact
 *  `[[providers.X.models]]` shape contribute. */
function parseTomlRosters(text: string, label: string): { rosters: Roster[]; problems: string[] } {
  const problems: string[] = [];
  const rosters = new Map<string, Roster>();
  const order: string[] = [];
  const stripped = stripTomlComments(text);

  // The closure state `close_model` writes back, as one object — the direct port of Python's
  // four `nonlocal` names, which JS has no equivalent for.
  const state: {
    provider: string | null;
    inModel: boolean;
    modelId: string | null;
    modelWindow: number | null;
    rowsParsed: number;
  } = { provider: null, inModel: false, modelId: null, modelWindow: null, rowsParsed: 0 };

  const closeModel = (): void => {
    if (!state.inModel) return;
    state.rowsParsed += 1;
    if (state.provider === null) {
      problems.push(`${label}: a [[providers.*.models]] row outside any provider table`);
    } else if (state.modelId === null) {
      problems.push(`${label} [${state.provider}]: a model row declares no id`);
    } else {
      (rosters.get(state.provider) as Roster).models.push([state.modelId, state.modelWindow]);
    }
    state.inModel = false;
    state.modelId = null;
    state.modelWindow = null;
  };

  for (const line of stripped.split("\n")) {
    const header = TABLE.exec(line);
    if (header !== null) {
      closeModel();
      const path = header[2].trim();
      const parts = path.split(".");
      if (parts[0] !== "providers" || parts.length < 2) {
        state.provider = null;
        continue;
      }
      const name = parts[1];
      if (parts.length === 2) {
        state.provider = name;
        if (!rosters.has(name)) {
          rosters.set(name, new Roster(label, name, null));
          order.push(name);
        }
        continue;
      }
      if (parts[parts.length - 1] === "models" && parts.length === 3 && header[1] === "[[") {
        state.provider = name;
        if (!rosters.has(name)) {
          rosters.set(name, new Roster(label, name, null));
          order.push(name);
        }
        state.inModel = true;
        continue;
      }
      // quirks, tool_surface, extra_windows, overrides: the provider stays current so a
      // later base_url cannot be mis-attributed, but these tables carry no model rows.
      state.provider = rosters.has(name) ? name : state.provider;
      continue;
    }
    const pair = KV.exec(line);
    if (pair === null || state.provider === null) continue;
    const key = pair[1];
    const raw = pair[2];
    if (state.inModel) {
      if (key === "id") state.modelId = unquote(raw);
      else if (key === "context_window") state.modelWindow = parseNumber(raw);
      continue;
    }
    if (key === "base_url") (rosters.get(state.provider) as Roster).baseUrl = unquote(raw);
  }
  closeModel();

  const rawRows = [...stripped.matchAll(MODELS_HEADER)].length;
  if (rawRows !== state.rowsParsed) {
    problems.push(
      `${label}: parsed ${state.rowsParsed} model rows but the text holds ${rawRows} ` +
        "[[providers.*.models]] headers — the parser and the source disagree, so no " +
        "roster from this run can be trusted",
    );
  }
  return { rosters: order.map((name) => rosters.get(name) as Roster), problems };
}

/** The text inside the parens whose opener is at openIndex. Comment- and string-aware. */
function parenBody(source: string, openIndex: number): string | null {
  let i = openIndex;
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
    if (source.startsWith("//", i)) {
      const nl = source.indexOf("\n", i);
      i = nl < 0 ? source.length : nl;
      continue;
    }
    if (source.startsWith("/*", i)) {
      const end = source.indexOf("*/", i + 2);
      i = end < 0 ? source.length : end + 2;
      continue;
    }
    if (ch === "(") {
      depth += 1;
      if (depth === 1) bodyStart = i + 1;
      i += 1;
      continue;
    }
    if (ch === ")") {
      depth -= 1;
      if (depth === 0 && bodyStart !== null) return source.slice(bodyStart, i);
      i += 1;
      continue;
    }
    i += 1;
  }
  return null;
}

/** Split a call's argument list on top-level commas, dropping comments. */
function splitArgs(body: string): string[] {
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
    if (body.startsWith("//", i)) {
      const nl = body.indexOf("\n", i);
      i = nl < 0 ? body.length : nl;
      continue;
    }
    if (body.startsWith("/*", i)) {
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
    if ( ")}]".includes(ch)) {
      depth -= 1;
      buf.push(ch);
      i += 1;
      continue;
    }
    if (ch === "," && depth === 0) {
      parts.push(buf.join(""));
      buf = [];
      i += 1;
      continue;
    }
    buf.push(ch);
    i += 1;
  }
  if (buf.join("").trim()) parts.push(buf.join(""));
  return parts;
}

/** The raw text of `name = ...` in an argument list, or null. */
function namedArg(args: string[], name: string): string | null {
  const pattern = new RegExp(`^\\s*${escapeRe(name)}\\s*=\\s*([\\s\\S]*)$`);
  for (const arg of args) {
    const match = pattern.exec(arg);
    if (match !== null) return match[1].trim();
  }
  return null;
}

function escapeRe(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** AddProfile rows out of AddProfileCatalog.kt, with WINDOW_* constants resolved. */
function parseKotlinCatalog(source: string, label: string): { rosters: Roster[]; problems: string[] } {
  const problems: string[] = [];
  const rosters: Roster[] = [];
  const windows = new Map<string, number>(
    [...source.matchAll(WINDOW_CONST)].map((m) => [m[1], parseInt(m[2].replace(/_/g, ""), 10)]),
  );
  let rowsParsed = 0;

  for (const match of source.matchAll(ADD_PROFILE_CALL)) {
    const body = parenBody(source, (match.index as number) + match[0].length - 1);
    if (body === null) {
      problems.push(`${label}: an AddProfile( call could not be parsed`);
      continue;
    }
    const args = splitArgs(body);
    const rawName = namedArg(args, "name");
    const name = rawName ? unquote(rawName) : null;
    if (name === null) {
      problems.push(`${label}: an AddProfile row declares no literal name`);
      continue;
    }
    const rawBase = namedArg(args, "baseUrl");
    const baseUrl = rawBase === null || rawBase === "null" ? null : unquote(rawBase ?? "");
    if (rawBase !== null && rawBase !== "null" && baseUrl === null) {
      problems.push(
        `${label} [${name}]: baseUrl is neither a string literal nor null ` +
          `(${pyRepr(rawBase)}) — it cannot be joined to the example`,
      );
    }
    const roster = new Roster(label, name, baseUrl);
    const rawModels = namedArg(args, "models") ?? "";
    for (const modelMatch of rawModels.matchAll(ADD_MODEL_CALL)) {
      const modelBody = parenBody(rawModels, (modelMatch.index as number) + modelMatch[0].length - 1);
      rowsParsed += 1;
      if (modelBody === null) {
        problems.push(`${label} [${name}]: an AddModel( call could not be parsed`);
        continue;
      }
      const modelArgs = splitArgs(modelBody);
      const positional = modelArgs.filter((arg) => !(arg.split('"')[0] ?? "").includes("="));
      const modelId = positional.length > 0 ? unquote(positional[0]) : null;
      if (modelId === null) {
        problems.push(`${label} [${name}]: an AddModel row declares no literal id`);
        continue;
      }
      const rawWindow = positional.length > 2 ? positional[2].trim() : null;
      let window: number | null = null;
      if (rawWindow === null) {
        problems.push(`${label} [${name}]: ${modelId} declares no context window`);
      } else if (windows.has(rawWindow)) {
        window = windows.get(rawWindow) as number;
      } else {
        window = parseNumber(rawWindow);
        if (window === null) {
          problems.push(
            `${label} [${name}]: ${modelId}'s window ${pyRepr(rawWindow)} resolves to no ` +
              "constant in this file and is not a literal — an unresolved window " +
              "cannot be compared, so this run is not trusted",
          );
        }
      }
      roster.models.push([modelId, window]);
    }
    rosters.push(roster);
  }

  const rawRows = [...stripKotlinComments(source).matchAll(ADD_MODEL_CALL)].length;
  if (rawRows !== rowsParsed) {
    problems.push(
      `${label}: parsed ${rowsParsed} AddModel rows but the file holds ${rawRows} ` +
        "AddModel( calls — the parser and the source disagree, so no roster from this " +
        "run can be trusted",
    );
  }
  if (windows.size === 0) {
    problems.push(`${label}: no WINDOW_* constant declarations found — windows cannot be resolved`);
  }
  return { rosters, problems };
}

/** DEFAULT_TOML's raw-string body out of TopologyLoader.kt, by its marker. */
function extractStarterToml(source: string, label: string): { text: string | null; problems: string[] } {
  const anchor = source.indexOf(STARTER_MARKER);
  if (anchor < 0) {
    return { text: null, problems: [`${label}: ${STARTER_MARKER} not found — the starter roster's source is absent`] };
  }
  const openQuote = source.indexOf('"""', anchor);
  if (openQuote < 0) {
    return { text: null, problems: [`${label}: ${STARTER_MARKER} is not followed by a raw string literal`] };
  }
  const closeQuote = source.indexOf('"""', openQuote + 3);
  if (closeQuote < 0) {
    return { text: null, problems: [`${label}: ${STARTER_MARKER}'s raw string is unterminated`] };
  }
  return { text: source.slice(openQuote + 3, closeQuote), problems: [] };
}

function indexByBaseUrl(rosters: Roster[], label: string): { index: Map<string, Roster>; problems: string[] } {
  const problems: string[] = [];
  const index = new Map<string, Roster>();
  for (const roster of rosters) {
    if (roster.baseUrl === null) continue;
    if (index.has(roster.baseUrl)) {
      const prior = index.get(roster.baseUrl) as Roster;
      problems.push(
        `${label}: base_url ${roster.baseUrl} is declared by BOTH ` +
          `[providers.${prior.provider}] and [providers.${roster.provider}] ` +
          "— the join key is ambiguous, so no comparison against this file is trustworthy",
      );
      continue;
    }
    index.set(roster.baseUrl, roster);
  }
  return { index, problems };
}

/** (example rosters, [catalog rosters, starter rosters], problems). */
function loadSources(root: string): { example: Roster[]; derived: Roster[][]; problems: string[] } {
  const problems: string[] = [];
  let example: Roster[] = [];
  const derived: Roster[][] = [];

  const examplePath = join(root, EXAMPLE_REL);
  if (!existsSync(examplePath)) {
    problems.push(`${EXAMPLE_REL}: missing — it IS the source, so its absence cannot pass`);
  } else {
    const parsed = parseTomlRosters(readFileSync(examplePath, "utf8"), EXAMPLE_REL);
    example = parsed.rosters;
    problems.push(...parsed.problems);
  }

  const catalogPath = join(root, CATALOG_REL);
  if (!existsSync(catalogPath)) {
    problems.push(`${CATALOG_REL}: missing — a roster that cannot be read cannot be proven to agree`);
    derived.push([]);
  } else {
    const parsed = parseKotlinCatalog(readFileSync(catalogPath, "utf8"), CATALOG_REL);
    problems.push(...parsed.problems);
    derived.push(parsed.rosters);
  }

  const starterPath = join(root, STARTER_REL);
  if (!existsSync(starterPath)) {
    problems.push(`${STARTER_REL}: missing — a roster that cannot be read cannot be proven to agree`);
    derived.push([]);
  } else {
    const extracted = extractStarterToml(readFileSync(starterPath, "utf8"), STARTER_REL);
    problems.push(...extracted.problems);
    if (extracted.text === null) {
      derived.push([]);
    } else {
      const label = `${STARTER_REL}:${STARTER_MARKER}`;
      const parsed = parseTomlRosters(extracted.text, label);
      problems.push(...parsed.problems);
      derived.push(parsed.rosters);
    }
  }
  return { example, derived, problems };
}

function audit(root: string): string[] {
  const { example, derived, problems } = loadSources(root);

  if (!example.some((roster) => roster.models.length > 0)) {
    problems.push(
      `${EXAMPLE_REL}: parsed no provider carrying a model row — refusing to compare ` +
        "against an empty source, because a green over an empty denominator is what this " +
        "wall exists to prevent",
    );
    return problems;
  }

  const { index, problems: indexProblems } = indexByBaseUrl(example, EXAMPLE_REL);
  problems.push(...indexProblems);

  let comparisons = 0;
  for (const rosters of derived) {
    if (!rosters.some((roster) => roster.models.length > 0)) {
      const label = rosters.length > 0 ? rosters[0].label : "a derived roster source";
      problems.push(`${label}: parsed no roster carrying a model row — refusing to pass vacuously`);
      continue;
    }
    for (const roster of rosters) {
      if (roster.baseUrl === null) {
        if (roster.models.length === 0) {
          // no-roster: nothing to compare. COMPUTED, not a named exemption.
          continue;
        }
        problems.push(
          `${roster.where}: declares ${roster.models.length} model row(s) but no base_url ` +
            "— it cannot be joined to the example, and an unjoinable roster is an " +
            "absence, not a disposition",
        );
        continue;
      }
      const target = index.get(roster.baseUrl);
      if (target === undefined) {
        problems.push(
          `${roster.where}: base_url ${roster.baseUrl} matches no provider table in ` +
            `${EXAMPLE_REL} — add the provider there, or point this roster at a declared one`,
        );
        continue;
      }
      const expected = new Map<string, number | null>(target.models.map(([id, w]) => [id, w]));
      for (const [modelId, window] of roster.models) {
        comparisons += 1;
        if (!expected.has(modelId)) {
          problems.push(
            `${roster.where}: ${modelId} (context_window ${window}) is absent from ` +
              `${EXAMPLE_REL} [providers.${target.provider}] — the example is the source, ` +
              "so declare the row there or drop it here",
          );
          continue;
        }
        if (expected.get(modelId) !== window) {
          problems.push(
            `${roster.where}: ${modelId} declares context_window ${window} but ` +
              `${EXAMPLE_REL} [providers.${target.provider}] declares ` +
              `${expected.get(modelId)} — the example is the source`,
          );
        }
      }
    }
  }
  if (comparisons === 0 && problems.length === 0) {
    problems.push("compared 0 model rows across the derived rosters — refusing to pass vacuously");
  }
  return problems;
}

// ── selftest fixtures ─────────────────────────────────────────────────────────────────

const EXAMPLE_OK = `[daemon]
control_port = 3096

[providers.xai]
dialect = "openai-responses"
base_url = "https://api.x.ai/v1"
[[providers.xai.models]]
id = "grok-4.6"
label = "Grok 4.6"
context_window = 500000        # a trailing comment the stripper must drop
[[providers.xai.models]]
id = "grok-4.3"
label = "Grok 4.3"
context_window = 1000000

[providers.kimi]
base_url = "https://api.kimi.com/coding"
[[providers.kimi.extra_windows]]
id = "k3"
context_window = 1000000
[[providers.kimi.models]]
id = "k3[1m]"
label = "Kimi K3 (1M)"
context_window = 1000000

[heads.grok]
provider = "xai"
context_window = 500000
`;

const CATALOG_OK = `package splice.app.cli

private const val WINDOW_500K = 500_000L
private const val WINDOW_1M = 1_000_000L

internal class AddProfileCatalog {
    val rows: List<AddProfile> = listOf(
        AddProfile(
            // The catalog calls xAI \`grok\`; the example calls it \`xai\`. base_url joins them.
            name = "grok",
            baseUrl = "https://api.x.ai/v1",
            models = listOf(
                AddModel("grok-4.6", "Grok 4.6", WINDOW_500K),
            ),
        ),
        AddProfile(
            name = "kimi",
            baseUrl = "https://api.kimi.com/coding",
            models = listOf(
                AddModel("k3[1m]", "Kimi K3 (1M)", WINDOW_1M, slots = listOf("opus")),
            ),
        ),
        AddProfile(
            name = "api-key",
            baseUrl = null,
            models = emptyList(),
        ),
    )
}
`;

const STARTER_OK = `package splice.app

public object TopologyLoader {
    private const val DEFAULT_TOML = """
[providers.xai]
base_url = "https://api.x.ai/v1"
[[providers.xai.models]]
id = "grok-4.6"
label = "Grok 4.6"
context_window = 500000
"""
}
`;

const EXAMPLE_BORING = `[providers.solo]
base_url = "https://example.invalid/v1"
[[providers.solo.models]]
id = "only-one"
label = "Only One"
context_window = 128000
`;

const CATALOG_BORING = `package splice.app.cli

private const val WINDOW_128K = 128_000L

internal class AddProfileCatalog {
    val rows: List<AddProfile> = listOf(
        AddProfile(name = "solo", baseUrl = "https://example.invalid/v1", models = listOf(
            AddModel("only-one", "Only One", WINDOW_128K),
        )),
    )
}
`;

const STARTER_BORING = `package splice.app

public object TopologyLoader {
    private const val DEFAULT_TOML = """
[providers.solo]
base_url = "https://example.invalid/v1"
[[providers.solo.models]]
id = "only-one"
context_window = 128000
"""
}
`;

function writeTree(root: string, example: string, catalog: string, starter: string): void {
  for (const [rel, text] of [
    [EXAMPLE_REL, example],
    [CATALOG_REL, catalog],
    [STARTER_REL, starter],
  ] as const) {
    const path = join(root, rel);
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, text, "utf8");
  }
}

function selftest(): number {
  const failures: string[] = [];

  const expectGreen = (root: string, what: string): void => {
    const hits = audit(root);
    if (hits.length > 0) failures.push(`${what} must be GREEN, got: ${pyReprList(hits)}`);
  };
  const expectRed = (root: string, what: string, ...needles: string[]): void => {
    const hits = audit(root);
    if (hits.length === 0) {
      failures.push(`${what} must be RED, got a clean pass`);
      return;
    }
    const blob = hits.join(" | ");
    for (const needle of needles) {
      if (!blob.includes(needle)) {
        failures.push(`${what} must name ${pyRepr(needle)}, got: ${blob}`);
      }
    }
  };

  const root = join(tmpdir(), `model-catalogs-${process.pid}-${Math.random().toString(36).slice(2)}`);
  mkdirSync(root, { recursive: true });
  try {
    // CONTROL. Every fixture below claims "this mutation turns green into red", which is
    // worth nothing unless the unmutated tree is green.
    writeTree(root, EXAMPLE_OK, CATALOG_OK, STARTER_OK);
    expectGreen(root, "the compliant tree (alias join, example superset, null-baseUrl row)");
    if (failures.length > 0) {
      process.stdout.write("model-catalogs-single-source SELFTEST FAIL (control):\n");
      for (const failure of failures) process.stdout.write("  " + failure + "\n");
      return 1;
    }

    // BORING: one provider, one model, nothing else. The case that gets waved through.
    writeTree(root, EXAMPLE_BORING, CATALOG_BORING, STARTER_BORING);
    expectGreen(root, "the boring tree (one provider, one model)");

    // A window mutated in the catalog.
    writeTree(root, EXAMPLE_OK, CATALOG_OK.replace("WINDOW_500K = 500_000L", "WINDOW_500K = 400_000L"), STARTER_OK);
    expectRed(root, "a catalog window that disagrees", "grok-4.6", "400000", "500000");

    // A synthetic id added to the catalog — the mutation this row requires.
    const mutated = CATALOG_OK.replace(
      '                AddModel("grok-4.6", "Grok 4.6", WINDOW_500K),',
      '                AddModel("grok-4.6", "Grok 4.6", WINDOW_500K),\n' +
        '                AddModel("grok-fake-9", "Grok Fake 9", WINDOW_1M),',
    );
    if (mutated === CATALOG_OK) failures.push("the catalog id mutation did not apply");
    writeTree(root, EXAMPLE_OK, mutated, STARTER_OK);
    expectRed(root, "a synthetic catalog id", "grok-fake-9", "absent from");

    // A window mutated in DEFAULT_TOML.
    writeTree(root, EXAMPLE_OK, CATALOG_OK, STARTER_OK.replace("context_window = 500000", "context_window = 262144"));
    expectRed(root, "a starter window that disagrees", "DEFAULT_TOML", "grok-4.6", "262144");

    // A synthetic id added to DEFAULT_TOML.
    writeTree(
      root,
      EXAMPLE_OK,
      CATALOG_OK,
      STARTER_OK.replace(
        'context_window = 500000\n"""',
        'context_window = 500000\n[[providers.xai.models]]\nid = "grok-fake-9"\ncontext_window = 500000\n"""',
      ),
    );
    expectRed(root, "a synthetic starter id", "grok-fake-9", "absent from");

    // A roster whose base_url matches no example provider.
    writeTree(root, EXAMPLE_OK, CATALOG_OK.replace("https://api.x.ai/v1", "https://api.xai.example/v1"), STARTER_OK);
    expectRed(root, "an unjoinable base_url", "matches no provider table");

    // A roster with models but a null base_url: the no-roster disposition stops applying.
    writeTree(
      root,
      EXAMPLE_OK,
      CATALOG_OK.replace(
        '            name = "api-key",\n            baseUrl = null,\n            models = emptyList(),',
        '            name = "api-key",\n            baseUrl = null,\n            models = listOf(\n' +
          '                AddModel("mystery", "Mystery", WINDOW_1M),\n            ),',
      ),
      STARTER_OK,
    );
    expectRed(root, "a null-baseUrl roster that grew models", "no base_url");

    // A duplicated base_url in the example: the join key is ambiguous.
    writeTree(root, EXAMPLE_OK + '\n[providers.xai-clone]\nbase_url = "https://api.x.ai/v1"\n', CATALOG_OK, STARTER_OK);
    expectRed(root, "a duplicated example base_url", "ambiguous");

    // An empty example.
    writeTree(root, "[daemon]\ncontrol_port = 3096\n", CATALOG_OK, STARTER_OK);
    expectRed(root, "an empty example", "refusing to compare against an empty source");

    // An empty derived roster set.
    writeTree(root, EXAMPLE_OK, "package splice.app.cli\n", STARTER_OK);
    expectRed(root, "a catalog with no rosters", "refusing to pass vacuously");

    // A DEFAULT_TOML marker that cannot be found.
    writeTree(root, EXAMPLE_OK, CATALOG_OK, "package splice.app\npublic object TopologyLoader\n");
    expectRed(root, "a missing starter marker", "DEFAULT_TOML not found");

    // Parser/source disagreement, TOML side: a models header the walker cannot attribute.
    writeTree(
      root,
      EXAMPLE_OK,
      CATALOG_OK,
      STARTER_OK.replace(
        "[providers.xai]\nbase_url",
        '[[providers.xai.models]]\nid = "orphan"\ncontext_window = 1\n[providers.xai]\nbase_url',
      ),
    );
    {
      const hits = audit(root);
      if (!hits.some((hit) => hit.includes("orphan") || hit.includes("disagree"))) {
        failures.push(`a model row the walker cannot attribute must be RED, got: ${pyReprList(hits)}`);
      }
    }

    // Parser/source disagreement, Kotlin side: an AddModel( call outside any AddProfile.
    writeTree(root, EXAMPLE_OK, CATALOG_OK + '\nprivate val orphan = AddModel("orphan", "Orphan", WINDOW_1M)\n', STARTER_OK);
    expectRed(root, "an AddModel outside any AddProfile", "the parser and the source disagree");
  } finally {
    rmSync(root, { recursive: true, force: true });
  }

  if (failures.length > 0) {
    process.stdout.write("model-catalogs-single-source SELFTEST FAIL:\n");
    for (const failure of failures) process.stdout.write("  " + failure + "\n");
    return 1;
  }
  process.stdout.write(
    "model-catalogs-single-source SELFTEST OK — a compliant tree (alias join by base_url, " +
      "example superset, computed no-roster row) and the BORING one-provider/one-model tree are " +
      "green; a mutated window and a synthetic id in EITHER emitter, an unjoinable base_url, a " +
      "null-baseUrl roster that grew models, a duplicated example base_url, an empty example, an " +
      "empty emitter, a missing DEFAULT_TOML marker, and a parser/source count disagreement on " +
      "both the TOML and the Kotlin side are all red by name\n",
  );
  return 0;
}

function report(root: string): void {
  const { example, derived, problems } = loadSources(root);
  const { index } = indexByBaseUrl(example, EXAMPLE_REL);
  process.stdout.write(`model-catalogs-single-source: ${EXAMPLE_REL} is the SOURCE\n`);
  for (const problem of problems) process.stdout.write(`  UNTRUSTED: ${problem}\n`);
  for (const roster of example) {
    process.stdout.write(
      `  source   [${roster.provider.padEnd(12)}] ${String(roster.models.length).padStart(2)} rows  ${roster.baseUrl}\n`,
    );
  }
  for (const rosters of derived) {
    if (rosters.length > 0) process.stdout.write(`  ${rosters[0].label}\n`);
    for (const roster of rosters) {
      const target = index.get(roster.baseUrl ?? "");
      let state: string;
      if (roster.baseUrl === null && roster.models.length === 0) {
        state = "no-roster (no base_url, no models)";
      } else if (target === undefined) {
        state = "NO DISPOSITION (base_url matches no example provider)";
      } else {
        const expected = new Map<string, number | null>(target.models.map(([id, w]) => [id, w]));
        const bad = roster.models
          .filter(([id, w]) => !expected.has(id) || expected.get(id) !== w)
          .map(([id]) => id);
        state = bad.length === 0 ? `agrees with [${target.provider}]` : `DRIFT: ${bad.join(", ")}`;
      }
      process.stdout.write(
        `    [${roster.provider.padEnd(12)}] ${String(roster.models.length).padStart(2)} rows  ${state}\n`,
      );
    }
  }
}

const USAGE = `usage: model-catalogs-single-source [check | report] [<root>] [--selftest]
  check      gate leg: every derived model row must agree with the example (a bare run does this)
  report     the roster census, no gating
  --selftest red-green proof, out of tree
`;

function main(argv: string[]): number {
  if (argv.includes("--selftest")) return selftest();
  let root = ROOT;
  for (const arg of argv) {
    if (arg !== "check" && arg !== "report" && arg !== "--selftest" && !arg.startsWith("-")) {
      root = arg;
      break;
    }
  }
  if (!existsSync(root)) {
    process.stderr.write("model-catalogs-single-source: tree missing\n");
    return 1;
  }
  const resolved = realpathSync(root);
  if (argv.includes("report")) {
    report(resolved);
    return 0;
  }
  const problems = audit(resolved);
  if (problems.length > 0) {
    process.stdout.write("model-catalogs-single-source RED:\n");
    for (const problem of problems) process.stdout.write("  " + problem + "\n");
    return 1;
  }
  process.stdout.write(
    "model-catalogs-single-source GREEN: every model row AddProfileCatalog.kt and " +
      `TopologyLoader.DEFAULT_TOML declare exists in ${EXAMPLE_REL} with the same context window\n`,
  );
  return 0;
}

process.exit(main(process.argv.slice(2)));
