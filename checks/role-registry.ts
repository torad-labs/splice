#!/usr/bin/env bun
/**
 * V4-89 (ARCH-AUDIT 2026-09-17, audit B row 5; concept #924 "make drift not compile") — ONE ROLE,
 * ONE INTERFACE. Every `fun interface` in main sources is a NAMED SEAM, and two names that normalise
 * to the same single-abstract-method signature are either two roles that must say why, or one role
 * spelled twice.
 *
 * WHY THIS EXISTS. kt-no-lambda-seam made every seam a named port and its header states the doctrine
 * this wall enforces the other half of: "one interface per role; a role with two spellings is one
 * interface". It also names the reason the matcher cannot do it — 200 seams over 132 signatures, and
 * the mapping is many-to-many in both directions. `() -> Boolean` is legitimately ten roles
 * (ClientGone and ClientFrameEmitted are read a few lines apart on the same retry path and drive
 * OPPOSITE decisions; collapsing them into a shared BooleanSupplier would be strictly worse than the
 * lambdas they replaced). And `() -> Long` is legitimately six roles PLUS two duplicates. Nothing in
 * the compiler, and nothing in an ast-grep rule, can tell those two situations apart — so this wall
 * does not try to. It requires that every shared signature be DISPOSITIONED IN WRITING, and treats
 * absence as the failure.
 *
 * THE THREE DUPLICATES THIS ROW WAS OPENED FOR, each verified against the declarations rather than
 * taken from the audit (2026-09-17):
 *   ElapsedNow (provider-spi/RuntimeSeams.kt:40) == ElapsedClock (core/util/RuntimePorts.kt:143).
 *       ElapsedNow's KDoc: "A monotonic now-reading in milliseconds. The seam behind retry deadlines
 *       and the shared 429 cooldown." ElapsedClock's: "Reads a MONOTONIC timebase in milliseconds …
 *       for budgets, deadlines, watchdog caps and elapsed timings." Same role, two modules.
 *   AccountNow (provider-spi/AccountSelection.kt:22) == WallClock (core/util/RuntimePorts.kt:105).
 *       AccountNow's KDoc: "Epoch time seam used to compare provider reset timestamps." WallClock's
 *       contract is exactly "a real point in calendar time … compared against a foreign epoch".
 *   HeaderLookup (gateway/usage/RateLimitHeaders.kt:22) == QuotaHeaderRead (core/usage/QuotaHeaders.kt:15).
 *       QuotaHeaderRead's own KDoc ADMITS it: "The gateway's own HeaderLookup lives a module above
 *       this one, so the header families are decoded against this port." One role, duplicated to
 *       satisfy a module direction.
 * These three names are deliberately ABSENT from the disposition file, so this wall is RED on them by
 * name today. That red list IS the work list for the sibling fix row; this wall does not fix
 * instances and does not allowlist them away.
 *
 * DENOMINATOR, FROM THE SOURCE (§24). Every `gateway/{module}/src/main` Kotlin file is parsed on disk
 * and every `fun interface` in it is enumerated — nested ones included, since a per-class seam is
 * still a seam. 200 declarations over 132 signatures at authoring, of which 26 signatures are shared
 * by 2+ names. An interface added tomorrow is in scope with no edit to this file. THREE guards refuse
 * a vacuous pass: a parse yielding zero interfaces is a failure rather than a pass; the count of
 * `fun interface` occurrences in the comment-blanked source must EQUAL the number of declarations
 * parsed (parser drift, the same shape as quirks-keys-documented.ts's @SerialName guard); and a
 * declaration whose body does not yield EXACTLY ONE abstract method is reported as UNTRUSTED rather
 * than silently grouped — a `fun interface` has one abstract method by language rule, so a different
 * count means this parser, not the code, is wrong.
 *
 * SIGNATURE NORMALISATION, and why each part of it is load-bearing.
 *   param types + return type, whitespace removed. The row's definition.
 *   `suspend` is PART of the signature. Measured both ways: folding suspend in with non-suspend
 *   merged Ticker (`suspend (Long) -> Boolean`, the pacing seam whose false means "stop the loop")
 *   with PidAlive (`(Long) -> Boolean`, "is this pid alive"), and HeadSignIn/ProfileAdd with
 *   BrowserOpener/CredentialPresenceProbe/DirectoryProbe/VersionedRestart. Those are not near-misses
 *   that want a written reason; a suspend seam and a blocking one cannot be substituted for each
 *   other at all, so grouping them would have manufactured four false duplicates and buried the real
 *   ones.
 *   TYPE PARAMETERS ARE POSITIONAL (`#1`, `#2`), never their declared spelling. `CoalescedWork<T>`
 *   and `MaterializedRequest<R>` ARE the same shape; a normaliser that kept `T` and `R` would call
 *   them different and let a real duplicate through on a rename.
 *   The METHOD NAME is NOT part of the signature, deliberately. `invoke`, `beforeWrite`, `abort`,
 *   `run` and `keyPresentNow` all appear on `() -> Unit`-shaped ports; a duplicate role renamed from
 *   `invoke` to `run` is exactly the drift this wall is for, so the name must not be allowed to
 *   separate two groups.
 *
 * DISPOSITION. checks/config/role-registry.toml carries one `[[groups]]` entry per shared signature:
 *   signature = the normalised signature, verbatim
 *   dated     = the date the disposition was written
 *   names     = the interface names this entry accounts for
 *   reason    = why these are DISTINCT ROLES. Non-empty, in words.
 * Absence is not a disposition. A name in a shared group with no entry covering it fails BY NAME.
 * The disposition is FAIL-CLOSED IN BOTH DIRECTIONS, which is what makes it a ratchet rather than a
 * list:
 *   GROWTH   a new name joining a dispositioned group is not in `names`, so it fails by name. Adding
 *            a seam that shares a shape costs one written sentence, every time.
 *   STALENESS a name in `names` that no longer exists, or an entry whose signature is no longer
 *            shared by 2+ names, fails as stale. A disposition cannot outlive its subject — which is
 *            how the fix row's own success is detected: the moment ElapsedNow is deleted, the
 *            `()->Long` entry must drop it or this wall reds.
 *   UNREASONED an entry with a missing, blank or whitespace-only `reason` is an absence wearing a
 *            label and fails by name, the same as no entry at all.
 *
 * WHAT IS NOT CAUGHT, and why it is written down rather than implied.
 *   TWO DECLARATIONS OF THE SAME NAME. `SynthesizeExpiry` exists three times (codex, grok, kimi) and
 *   `PersistRotation` twice (codex, grok). Those groups hold ONE distinct name, so the 2+-names test
 *   does not reach them — and that is correct here, not a hole being excused: the 2026-09-15
 *   SEPARATION law requires vendor facts to live in that vendor's provider-* module, so a per-vendor
 *   expiry synthesis IS one role per vendor. The wall reports these under `report` so the count stays
 *   visible; it does not grade them.
 *   A DUPLICATE WITH DIFFERENT SIGNATURES. Two names for one role whose methods take different
 *   parameter lists (`(Long) -> Long` vs `(Long, Long) -> Long`) land in different groups and are
 *   invisible here. Grouping by anything looser than the signature would turn the 26 groups into
 *   noise; this wall's claim is exactly "same shape, undeclared intent", never "same meaning".
 *   A ROLE-INAPPROPRIATE REASON. This wall proves a reason EXISTS and is not blank. Whether it is a
 *   good reason is a reviewer's judgement, and the entries are checked-in text precisely so a
 *   reviewer sees them in a diff.
 *
 * EXIT 0 = every shared signature is accounted for in writing. EXIT 1 = at least one name is not,
 * named by name, with its file:line and its signature siblings.
 * `--selftest` = the fixture proofs (both directions, plus the boring and vacuous cases).
 * `report`     = the whole census, for writing or reviewing a disposition.
 *
 * Usage:
 *     bun checks/role-registry.ts check [<root>]
 *     bun checks/role-registry.ts report [<root>]
 *     bun checks/role-registry.ts --selftest
 *     bun checks/role-registry.ts [--config=<path>] ...
 *
 * A BARE RUN IS `check` — the one gating mode, so there is no non-gating default to mis-invoke.
 * `report` is the census and sits behind an explicit verb the gate never uses.
 */
import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// parents[1]: this file lives at checks/, so the repo root is one level up.
const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");

const SOURCE_GLOB = "gateway/*/src/main/**/*.kt";
const CONFIG_REL = "checks/config/role-registry.toml";

const DECL = /\bfun\s+interface\s+(\w+)/g;
const METHOD_HEAD = /\s*(\w+)\s*\(/;

/** Blank comments and string literals WITHOUT moving offsets or newlines.
 *
 *  One lexical pass, so a `//` inside a string cannot open a comment and a quote inside a comment
 *  cannot open a string. Offsets survive so every finding still names file:LINE, which is this
 *  wall's contract. Raw strings (triple-quoted) are handled first: a KDoc example or a raw string
 *  containing the words `fun interface` must not become a phantom declaration, and the parser-drift
 *  guard counts occurrences in THIS view so the two halves cannot disagree. */
function codeView(text: string): string {
  const out = text.split("");
  const n = text.length;
  let i = 0;

  const blank = (start: number, end: number): void => {
    for (let k = start; k < Math.min(end, n); k += 1) {
      if (out[k] !== "\n") out[k] = " ";
    }
  };

  while (i < n) {
    const ch = text[i];
    if (ch === "/" && i + 1 < n && text[i + 1] === "/") {
      let end = text.indexOf("\n", i);
      end = end < 0 ? n : end;
      blank(i, end);
      i = end;
      continue;
    }
    if (ch === "/" && i + 1 < n && text[i + 1] === "*") {
      let end = text.indexOf("*/", i + 2);
      end = end < 0 ? n : end + 2;
      blank(i, end);
      i = end;
      continue;
    }
    if (text.startsWith('"""', i)) {
      let end = text.indexOf('"""', i + 3);
      end = end < 0 ? n : end + 3;
      blank(i, end);
      i = end;
      continue;
    }
    if (ch === '"' || ch === "'") {
      let j = i + 1;
      while (j < n) {
        if (text[j] === "\\") {
          j += 2;
          continue;
        }
        if (text[j] === ch) {
          j += 1;
          break;
        }
        if (text[j] === "\n") break;
        j += 1;
      }
      blank(i, j);
      i = j;
      continue;
    }
    i += 1;
  }
  return out.join("");
}

/** Index of the close bracket matching the one at [at], or -1. */
function balancedAt(code: string, at: number, openC: string, closeC: string): number {
  let depth = 0;
  let i = at;
  while (i < code.length) {
    if (code[i] === openC) depth += 1;
    else if (code[i] === closeC) {
      depth -= 1;
      if (depth === 0) return i;
    }
    i += 1;
  }
  return -1;
}

/** Index of the `>` closing the type-parameter list opened at [at], or -1.
 *
 *  Stops at `{` or `;` so a `<` used as a comparison cannot run the scan off the end of the file. */
function angleEnd(code: string, at: number): number {
  let depth = 0;
  let i = at;
  while (i < code.length) {
    const ch = code[i];
    if (ch === "<") depth += 1;
    else if (ch === ">") {
      depth -= 1;
      if (depth === 0) return i;
    } else if (ch === "{" || ch === ";") return -1;
    i += 1;
  }
  return -1;
}

/** Split on top-level commas, honouring (), [], {} and <>. */
function splitTop(text: string): string[] {
  const parts: string[] = [];
  let buf: string[] = [];
  let depth = 0;
  for (const ch of text) {
    if ("([{<".includes(ch)) depth += 1;
    else if (")]}>".includes(ch)) depth -= 1;
    if (ch === "," && depth === 0) {
      parts.push(buf.join(""));
      buf = [];
      continue;
    }
    buf.push(ch);
  }
  if (buf.join("").trim()) parts.push(buf.join(""));
  return parts;
}

/** `T`, `K`, `V` from a type-parameter list body, dropping bounds and variance. */
function typeParamNames(text: string): string[] {
  const names: string[] = [];
  for (const part of splitTop(text)) {
    const head = (part.trim().split(":")[0] ?? "").trim();
    if (head) names.push(head.trim().split(/\s+/).pop() as string);
  }
  return names;
}

/** Python's str.isalnum() for the identifier-boundary checks below. */
const isWordChar = (ch: string | undefined): boolean => ch !== undefined && /[A-Za-z0-9_]/.test(ch);

class Role {
  readonly name: string;
  readonly path: string;
  readonly line: number;
  readonly signature: string;
  readonly method: string;

  constructor(name: string, path: string, line: number, signature: string, method: string) {
    this.name = name;
    this.path = path;
    this.line = line;
    this.signature = signature;
    this.method = method;
  }

  get at(): string {
    return `${this.path}:${this.line}`;
  }
}

/** [(method name, signature)], problems) for the TOP-LEVEL abstract methods of an interface body.
 *
 *  Top-level only (bracket depth 0 within the body), so a nested enum's or data class's own
 *  functions are never mistaken for the seam's method — AccountCredentialIdentitySource has one
 *  abstract method beside two defaulted ones, a nested enum and a nested data class with an `init`,
 *  and a naive `fun` count reads it as seven.
 *
 *  Abstract means no body: the signature is followed by neither `=` nor `{`. A defaulted method is
 *  not the seam. */
function abstractMethods(body: string, ownerParams: string[]): { found: [string, string][]; problems: string[] } {
  const problems: string[] = [];
  const found: [string, string][] = [];
  let depth = 0;
  let i = 0;
  while (i < body.length) {
    const ch = body[i];
    if ("{([".includes(ch)) {
      depth += 1;
      i += 1;
      continue;
    }
    if ("})]".includes(ch)) {
      depth -= 1;
      i += 1;
      continue;
    }
    const isFun =
      depth === 0 &&
      body.startsWith("fun", i) &&
      (i === 0 || !isWordChar(body[i - 1])) &&
      (i + 3 >= body.length || !isWordChar(body[i + 3]));
    if (!isFun) {
      i += 1;
      continue;
    }
    // Modifiers sit between the previous member boundary and this `fun`.
    let back = i - 1;
    while (back >= 0 && !"};\n".includes(body[back])) back -= 1;
    const prefix = body.slice(back + 1, i);
    const suspend = /\bsuspend\b/.test(prefix);

    let cursor = i + 3;
    let methodParams: string[] = [];
    while (cursor < body.length && (body[cursor] === " " || body[cursor] === "\t")) cursor += 1;
    if (cursor < body.length && body[cursor] === "<") {
      const end = angleEnd(body, cursor);
      if (end < 0) {
        problems.push("unterminated method type-parameter list");
        i = cursor + 1;
        continue;
      }
      methodParams = typeParamNames(body.slice(cursor + 1, end));
      cursor = end + 1;
    }
    const head = METHOD_HEAD.exec(body.slice(cursor));
    if (head === null) {
      i += 3;
      continue;
    }
    const popen = cursor + head[0].length - 1;
    const pclose = balancedAt(body, popen, "(", ")");
    if (pclose < 0) {
      problems.push(`unbalanced parameter list on \`${head[1]}\``);
      i = popen + 1;
      continue;
    }
    const paramText = body.slice(popen + 1, pclose);
    const rest = body.slice(pclose + 1);
    let ret = "Unit";
    let tail = rest;
    const colon = /^\s*:\s*/.exec(rest);
    if (colon !== null) {
      const typed = rest.slice(colon[0].length);
      let depth2 = 0;
      let cut = typed.length;
      for (let idx = 0; idx < typed.length; idx += 1) {
        const c = typed[idx];
        if ("([<".includes(c)) depth2 += 1;
        else if (")]>" .includes(c)) depth2 -= 1;
        if (depth2 === 0 && "={\n".includes(c)) {
          cut = idx;
          break;
        }
      }
      ret = typed.slice(0, cut).trim() || "Unit";
      tail = typed.slice(cut);
    }
    if (/^\s*[={]/.test(tail)) {
      i = pclose + 1;
      continue; // defaulted: has a body, so not the seam
    }

    const names = [...new Set([...ownerParams, ...methodParams])];
    const positional = new Map<string, string>(names.map((n, k) => [n, `#${k + 1}`]));

    const normalise = (kind: string): string => {
      let collapsed = kind.replace(/\s+/g, "");
      for (const [declared, slot] of positional) {
        collapsed = collapsed.replace(new RegExp(`\\b${escapeRe(declared)}\\b`, "g"), slot);
      }
      return collapsed;
    };

    const types: string[] = [];
    for (const raw of splitTop(paramText)) {
      let param = raw.trim();
      if (!param) continue;
      param = param.replace(/^(vararg\s+|noinline\s+|crossinline\s+)+/, "");
      const kind = param.includes(":") ? param.split(":").slice(1).join(":") : param;
      types.push(normalise(kind.split("=")[0]));
    }
    const pre = suspend ? "suspend " : "";
    found.push([head[1], `${pre}(${types.join(",")})->${normalise(ret)}`]);
    i = pclose + 1;
  }
  return { found, problems };
}

function escapeRe(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** (roles, problems) for the whole tree. problems is non-empty only on a parse that cannot be
 *  trusted, never on a merely undispositioned name.
 *
 *  SELF-ACCOUNTING is the guard, not a second hand count. Every `fun interface` occurrence in the
 *  comment-blanked view must resolve to EXACTLY ONE outcome — a Role, or a named problem — and the
 *  totals are compared at the end. It catches the failure this parser is actually prone to: a
 *  branch that gives up on a declaration and moves the cursor on without recording anything, which
 *  would quietly shrink the denominator and make the wall greener. (The independent denominator
 *  lives in `denominator`, which asks ast-grep, and is run by
 *  checks/role-registry-selftest.sh — a regex cross-checking itself is a tautology, so that check
 *  does not belong inside this parser.) */
export function collect(root: string): { roles: Role[]; problems: string[] } {
  const roles: Role[] = [];
  const problems: string[] = [];
  let occurrences = 0;
  let resolved = 0;
  const files = [...new Bun.Glob(SOURCE_GLOB).scanSync({ cwd: root, followSymlinks: true })].sort();
  for (const rel of files) {
    const code = codeView(readFileSync(join(root, rel), "utf8"));
    for (const match of code.matchAll(DECL)) {
      occurrences += 1;
      const name = match[1];
      const line = code.slice(0, match.index as number).split("\n").length;
      let cursor = (match.index as number) + match[0].length;
      let ownerParams: string[] = [];
      while (cursor < code.length && (code[cursor] === " " || code[cursor] === "\t")) cursor += 1;
      if (cursor < code.length && code[cursor] === "<") {
        const end = angleEnd(code, cursor);
        if (end < 0) {
          problems.push(`${rel}:${line} ${name}: unterminated type-parameter list`);
          resolved += 1;
          continue;
        }
        ownerParams = typeParamNames(code.slice(cursor + 1, end));
        cursor = end + 1;
      }
      const brace = code.indexOf("{", cursor);
      if (brace < 0) {
        problems.push(
          `${rel}:${line} ${name}: no interface body — this parser and the ` +
            "source disagree, so no role list from this run can be trusted",
        );
        resolved += 1;
        continue;
      }
      const end = balancedAt(code, brace, "{", "}");
      if (end < 0) {
        problems.push(
          `${rel}:${line} ${name}: unbalanced interface body — this parser and ` +
            "the source disagree, so no role list from this run can be trusted",
        );
        resolved += 1;
        continue;
      }
      const { found: methods, problems: methodProblems } = abstractMethods(
        code.slice(brace + 1, end),
        ownerParams,
      );
      for (const problem of methodProblems) problems.push(`${rel}:${line} ${name}: ${problem}`);
      if (methods.length !== 1) {
        problems.push(
          `${rel}:${line} ${name}: parsed ${methods.length} abstract method(s); a \`fun ` +
            "interface\` has exactly one by language rule, so this parser and the source " +
            "disagree and no signature from this run can be trusted",
        );
        resolved += 1;
        continue;
      }
      const [method, signature] = methods[0];
      roles.push(new Role(name, rel, line, signature, method));
      resolved += 1;
    }
  }
  if (resolved !== occurrences) {
    problems.push(
      `counted ${occurrences} \`fun interface\` occurrence(s) in the comment-blanked sources ` +
        `but resolved only ${resolved} of them to a role or a named problem — a declaration ` +
        "this parser silently dropped shrinks the denominator, so no role list from this run " +
        "can be trusted",
    );
  }
  return { roles, problems };
}

/** (count, detail) — an INDEPENDENT `fun interface` census, from ast-grep's Kotlin AST.
 *
 *  The regex above cannot cross-check itself (§24: two hand-authored lists agreeing with each
 *  other is not a check against reality), so the external denominator comes from the parser CI
 *  already depends on for gate:rules. The rule matches `class_declaration` nodes whose text BEGINS
 *  with optional modifiers and then `fun interface` — anchored, because an unanchored regex also
 *  matches every enclosing class that merely CONTAINS a nested `fun interface` (measured
 *  2026-09-17: 202 unanchored vs 200 anchored, the two extras being GrokAuthJson and
 *  KimiRefreshedTokens, which each wrap one).
 *
 *  Returns (-1, reason) when ast-grep cannot answer, so the caller decides whether that is fatal. */
export function denominator(root: string): { count: number; detail: string } {
  const which = spawnSync("sh", ["-c", "command -v ast-grep"], { encoding: "utf8" });
  if (which.status !== 0 || !which.stdout.trim()) return { count: -1, detail: "ast-grep is not on PATH" };
  const rule =
    "id: role-registry-denominator\n" +
    "language: kotlin\n" +
    "severity: hint\n" +
    "message: fun interface\n" +
    "files:\n" +
    "  - gateway/*/src/main/**/*.kt\n" +
    "rule:\n" +
    "  kind: class_declaration\n" +
    "  regex: '^((public|internal|private|protected|expect|actual|@\\w+)\\s+)*fun\\s+interface\\s'\n";
  let stdout = "";
  let code = 0;
  let stderr = "";
  try {
    const done = spawnSync("ast-grep", ["scan", "--inline-rules", rule, "--json=compact", join(root, "gateway")], {
      encoding: "utf8",
      timeout: 300000,
      maxBuffer: 256 * 1024 * 1024,
    });
    stdout = done.stdout ?? "";
    stderr = done.stderr ?? "";
    code = done.status ?? 0;
  } catch (exc) {
    return { count: -1, detail: `ast-grep could not be run (${exc})` };
  }
  let rows: unknown[];
  try {
    rows = JSON.parse(stdout || "[]") as unknown[];
  } catch {
    return {
      count: -1,
      detail: `ast-grep produced no parseable JSON (exit ${code}): ${stderr.slice(0, 200)}`,
    };
  }
  return { count: rows.length, detail: `ast-grep ${rows.length} declaration(s)` };
}

/** signature -> roles, for every signature carried by 2+ DISTINCT names. */
function sharedGroups(roles: Role[]): Map<string, Role[]> {
  const bySignature = new Map<string, Role[]>();
  for (const role of roles) {
    const list = bySignature.get(role.signature);
    if (list === undefined) bySignature.set(role.signature, [role]);
    else list.push(role);
  }
  const out = new Map<string, Role[]>();
  for (const [signature, members] of bySignature) {
    if (new Set(members.map((m) => m.name)).size > 1) out.set(signature, members);
  }
  return out;
}

/** signature -> entry, plus problems. A config that cannot be read is a failure, never a skip. */
function loadConfig(path: string): { entries: Map<string, Record<string, unknown>>; problems: string[] } {
  if (!existsSync(path)) {
    return {
      entries: new Map(),
      problems: [
        `${path}: the disposition file is missing — with no dispositions every shared ` +
          "signature is an absence, so this cannot pass",
      ],
    };
  }
  let doc: Record<string, unknown>;
  try {
    doc = Bun.TOML.parse(readFileSync(path, "utf8")) as Record<string, unknown>;
  } catch (exc) {
    return {
      entries: new Map(),
      problems: [`${path}: unparseable TOML (${exc}) — a disposition nobody can read is not one`],
    };
  }
  const entries = new Map<string, Record<string, unknown>>();
  const problems: string[] = [];
  const groups = (doc.groups ?? []) as Record<string, unknown>[];
  for (let index = 0; index < groups.length; index += 1) {
    const entry = groups[index];
    const signature = entry.signature;
    if (typeof signature !== "string" || !signature.trim()) {
      problems.push(`${path}: groups[${index}] has no \`signature\``);
      continue;
    }
    if (entries.has(signature)) {
      problems.push(
        `${path}: two entries dispose \`${signature}\` — one signature, one disposition, or ` +
          "the second is dead text nobody reviews",
      );
      continue;
    }
    entries.set(signature, entry);
  }
  return { entries, problems };
}

function audit(root: string, configRel = CONFIG_REL): string[] {
  const { roles, problems: collectProblems } = collect(root);
  const problems = [...collectProblems];
  if (roles.length === 0) {
    problems.push(
      `parsed 0 \`fun interface\` declarations under ${SOURCE_GLOB} — refusing to pass ` +
        "vacuously, because a green over an empty denominator is what this wall exists to " +
        "prevent",
    );
    return problems;
  }

  const { entries, problems: configProblems } = loadConfig(join(root, configRel));
  problems.push(...configProblems);
  const groups = sharedGroups(roles);

  for (const signature of [...groups.keys()].sort()) {
    const members = groups.get(signature) as Role[];
    const names = [...new Set(members.map((m) => m.name))].sort();
    const where = new Map(members.map((m) => [m.name, m.at]));
    const entry = entries.get(signature);
    if (entry === undefined) {
      for (const name of names) {
        const siblings = names.filter((n) => n !== name).join(", ");
        problems.push(
          `NO DISPOSITION: ${name} (${where.get(name)}) shares the signature ${signature} with ` +
            `${siblings} and no entry in ${configRel} says they are distinct roles. Either ` +
            `reconcile the duplicate onto one interface, or add a [[groups]] entry with ` +
            `signature = "${signature}" and a written reason.`,
        );
      }
      continue;
    }
    const reason = entry.reason;
    if (typeof reason !== "string" || !reason.trim()) {
      problems.push(
        `${configRel}: the entry for ${signature} carries no reason — a disposition ` +
          "without a written reason is an absence wearing a label, and accounts for " +
          `${names.join(", ")} in name only`,
      );
      continue;
    }
    if (typeof entry.dated !== "string" || !String(entry.dated ?? "").trim()) {
      problems.push(
        `${configRel}: the entry for ${signature} carries no \`dated\` — an undated ` +
          "disposition cannot be aged out or reviewed",
      );
    }
    const listed = entry.names;
    if (!Array.isArray(listed) || !listed.every((n) => typeof n === "string")) {
      problems.push(`${configRel}: the entry for ${signature} has no \`names\` list`);
      continue;
    }
    for (const name of names) {
      if (!(listed as string[]).includes(name)) {
        const siblings = names.filter((n) => n !== name).join(", ");
        problems.push(
          `NO DISPOSITION: ${name} (${where.get(name)}) shares the signature ${signature} with ` +
            `${siblings}. The entry in ${configRel} disposes that signature but does not ` +
            `list ${name}: add it to \`names\` with the reason extended to cover it, or ` +
            "reconcile it onto the interface that already holds this role.",
        );
      }
    }
    for (const name of [...new Set(listed as string[])].sort()) {
      if (!names.includes(name)) {
        problems.push(
          `STALE DISPOSITION: ${configRel} lists ${name} under ${signature}, but no ` +
            "interface of that name carries that signature any more. A disposition may not " +
            "outlive its subject — drop the name (and the entry, if it is the last one).",
        );
      }
    }
  }

  for (const signature of [...entries.keys()].sort()) {
    if (!groups.has(signature)) {
      problems.push(
        `STALE DISPOSITION: ${configRel} disposes ${signature}, but that signature is no ` +
          "longer shared by two or more names in the tree. Remove the entry — a list that " +
          "keeps entries nobody can reach is how an allowlist stops being reviewed.",
        );
    }
  }
  return problems;
}

// ── report ────────────────────────────────────────────────────────────────────────────────────────

function report(root: string, configRel = CONFIG_REL): void {
  const { roles, problems } = collect(root);
  const groups = sharedGroups(roles);
  const { entries } = loadConfig(join(root, configRel));
  const signatures = new Set(roles.map((r) => r.signature));
  const repeated = new Map<string, Role[]>();
  for (const role of roles) {
    const list = repeated.get(role.name);
    if (list === undefined) repeated.set(role.name, [role]);
    else list.push(role);
  }
  process.stdout.write(
    `role-registry: ${roles.length} \`fun interface\` declaration(s) over ${signatures.size} ` +
      `signature(s); ${groups.size} signature(s) shared by 2+ names; ${entries.size} disposition(s)\n`,
  );
  for (const problem of problems) process.stdout.write(`  UNTRUSTED: ${problem}\n`);
  const ordered = [...groups.entries()].sort((a, b) => {
    const an = new Set(a[1].map((m) => m.name)).size;
    const bn = new Set(b[1].map((m) => m.name)).size;
    if (an !== bn) return bn - an;
    return a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0;
  });
  for (const [signature, members] of ordered) {
    const names = [...new Set(members.map((m) => m.name))].sort();
    const entry = entries.get(signature);
    const state =
      entry && String(entry.reason ?? "").trim() ? "DISPOSED" : "NO DISPOSITION";
    process.stdout.write(`  ${signature}   [${names.length} names]  ${state}\n`);
    const sorted = [...members].sort((a, b) =>
      a.name === b.name ? (a.path < b.path ? -1 : 1) : a.name < b.name ? -1 : 1,
    );
    for (const member of sorted) {
      const listedNames = (entry?.names as string[] | undefined) ?? [];
      const mark = entry && listedNames.includes(member.name) ? " " : "!";
      process.stdout.write(`    ${mark} ${member.name.padEnd(32)} ${member.at}  (${member.method})\n`);
    }
  }
  const sameName = [...repeated.entries()].filter(([, members]) => members.length > 1);
  if (sameName.length > 0) {
    process.stdout.write(
      `  (not graded: ${sameName.length} name(s) declared more than once — the SEPARATION law ` +
        "makes a per-vendor seam one role per vendor)\n",
    );
    for (const [name, members] of [...sameName].sort((a, b) => (a[0] < b[0] ? -1 : 1))) {
      const bits = [...members]
        .sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0))
        .map((m) => `${m.at} ${m.signature}`);
      process.stdout.write(`    = ${name}: ${bits.join(", ")}\n`);
    }
  }
}

// ── selftest ──────────────────────────────────────────────────────────────────────────────────────

const MODULE = "gateway/core/src/main/kotlin/splice/core";

const COMPLIANT_SOURCE = `package splice.core

/**
 * A KDoc that says \`fun interface Decoy\` in prose — the comment blanker must keep this out of the
 * denominator, and the parser-drift guard counts occurrences in the SAME blanked view.
 */
public fun interface ClientGone {
    public operator fun invoke(): Boolean
}

public fun interface ClientFrameEmitted {
    public operator fun invoke(): Boolean
}

/** A suspend seam of the "same" shape: proven NOT to join the group above. */
public fun interface Ticker {
    public suspend fun awaitTick(intervalMs: Long): Boolean
}

public fun interface PidAlive {
    public operator fun invoke(pid: Long): Boolean
}

/** One abstract method beside two DEFAULTED ones and two nested types — the shape that reads as
 *  seven \`fun\`s to a naive count (AccountCredentialIdentitySource). */
public fun interface IdentitySource {
    public fun identity(): String?

    public fun presence(): Presence = Presence.UNKNOWN

    public fun evidence(): Evidence {
        val id = identity()
        return Evidence(id)
    }

    public enum class Presence { PRESENT, UNKNOWN }

    public data class Evidence(public val id: String?) {
        public fun describe(): String = id ?: "none"
    }
}
`;

// Two generic seams whose type parameters are spelled differently: positional normalisation must
// put them in ONE group, or a rename hides a duplicate.
const GENERIC_SOURCE = `package splice.core

public fun interface CoalescedWork<T> {
    public suspend operator fun invoke(): T
}

public fun interface MaterializedRequest<R> {
    public suspend operator fun invoke(): R
}
`;

const CONFIG_OK = `[[groups]]
signature = "()->Boolean"
dated = "2026-09-17"
names = ["ClientFrameEmitted", "ClientGone"]
reason = "Read a few lines apart on the same retry path and driving OPPOSITE decisions."
`;

const CONFIG_OK_GENERIC =
  CONFIG_OK +
  `
[[groups]]
signature = "suspend ()->#1"
dated = "2026-09-17"
names = ["CoalescedWork", "MaterializedRequest"]
reason = "One coalesces concurrent callers onto a single in-flight computation; the other materialises a request body once per turn."
`;

const CONFIG_BLANK_REASON = CONFIG_OK.replace(
  'reason = "Read a few lines apart on the same retry path and driving OPPOSITE decisions."',
  'reason = "   "',
);

const CONFIG_NO_DATE = CONFIG_OK.replace('dated = "2026-09-17"\n', "");

const CONFIG_STALE_NAME = CONFIG_OK.replace(
  'names = ["ClientFrameEmitted", "ClientGone"]',
  'names = ["ClientFrameEmitted", "ClientGone", "ClientVanished"]',
);

const CONFIG_STALE_ENTRY =
  CONFIG_OK +
  `
[[groups]]
signature = "(Zork)->Zork"
dated = "2026-09-17"
names = ["Gone", "Went"]
reason = "A signature no interface in the tree carries."
`;

// The synthetic duplicate the row requires: a THIRD `() -> Boolean` name the config does not list.
const SYNTHETIC_DUPLICATE = `
public fun interface ClientHungUp {
    public operator fun invoke(): Boolean
}
`;

// A `fun interface` the parser cannot account for: the drift guard must refuse the run.
const DRIFT_SOURCE =
  COMPLIANT_SOURCE +
  `
public fun interface Broken
`;

function writeTree(root: string, sources: Record<string, string>, config: string | null): void {
  for (const [name, text] of Object.entries(sources)) {
    const path = join(root, MODULE, name);
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, text, "utf8");
  }
  const configPath = join(root, CONFIG_REL);
  mkdirSync(dirname(configPath), { recursive: true });
  if (config === null) {
    if (existsSync(configPath)) rmSync(configPath);
  } else {
    writeFileSync(configPath, config, "utf8");
  }
}

function freshRoot(label: string): string {
  const root = join(tmpdir(), `role-registry-${process.pid}-${Math.random().toString(36).slice(2)}`, label);
  mkdirSync(root, { recursive: true });
  return root;
}

function selftest(): number {
  const failures: string[] = [];

  const cases: { label: string; sources: Record<string, string>; config: string | null; wantRed: boolean; mustName?: string }[] = [];

  const runCase = (c: (typeof cases)[number]): void => {
    const root = freshRoot("tree");
    try {
      writeTree(root, c.sources, c.config);
      const hits = audit(root);
      if (c.wantRed && hits.length === 0) failures.push(`${c.label}: must be RED, got GREEN`);
      else if (!c.wantRed && hits.length > 0) failures.push(`${c.label}: must be GREEN, got: ${pyReprList(hits)}`);
      else if (c.wantRed && c.mustName && !hits.some((hit) => hit.includes(c.mustName as string))) {
        failures.push(
          `${c.label}: RED but not BY NAME (${pyRepr(c.mustName)} absent): ${pyReprList(hits)}`,
        );
      }
    } finally {
      rmSync(dirname(root), { recursive: true, force: true });
    }
  };

  // CONTROL: the compliant tree. Two roles sharing a shape, dispositioned with a reason; a
  // suspend sibling and a PidAlive sibling that must NOT join them; and an interface with
  // defaulted methods and nested types that must parse as ONE abstract method.
  cases.push({
    label: "control: dispositioned group, suspend and arity siblings apart",
    sources: { "Ports.kt": COMPLIANT_SOURCE },
    config: CONFIG_OK,
    wantRed: false,
  });
  // THE SYNTHETIC DUPLICATE the row requires: a third name in a dispositioned group.
  cases.push({
    label: "GROWTH: a third ()->Boolean name the disposition does not list",
    sources: { "Ports.kt": COMPLIANT_SOURCE + SYNTHETIC_DUPLICATE },
    config: CONFIG_OK,
    wantRed: true,
    mustName: "ClientHungUp",
  });
  cases.push({
    label: "NO DISPOSITION: a shared signature with no entry",
    sources: { "Ports.kt": COMPLIANT_SOURCE },
    config: "",
    wantRed: true,
    mustName: "ClientGone",
  });
  cases.push({
    label: "NO DISPOSITION: the config file is missing entirely",
    sources: { "Ports.kt": COMPLIANT_SOURCE },
    config: null,
    wantRed: true,
    mustName: "missing",
  });
  cases.push({
    label: "UNREASONED: a disposition whose reason is whitespace",
    sources: { "Ports.kt": COMPLIANT_SOURCE },
    config: CONFIG_BLANK_REASON,
    wantRed: true,
    mustName: "no reason",
  });
  cases.push({
    label: "UNDATED: a disposition with no `dated`",
    sources: { "Ports.kt": COMPLIANT_SOURCE },
    config: CONFIG_NO_DATE,
    wantRed: true,
    mustName: "no `dated`",
  });
  cases.push({
    label: "STALE: a name in `names` that no interface carries",
    sources: { "Ports.kt": COMPLIANT_SOURCE },
    config: CONFIG_STALE_NAME,
    wantRed: true,
    mustName: "ClientVanished",
  });
  cases.push({
    label: "STALE: an entry for a signature nothing shares",
    sources: { "Ports.kt": COMPLIANT_SOURCE },
    config: CONFIG_STALE_ENTRY,
    wantRed: true,
    mustName: "(Zork)->Zork",
  });
  cases.push({
    label: "DUPLICATE ENTRY: one signature disposed twice",
    sources: { "Ports.kt": COMPLIANT_SOURCE },
    config: CONFIG_OK + CONFIG_OK,
    wantRed: true,
    mustName: "two entries dispose",
  });
  cases.push({
    label: "VACUOUS: zero interfaces must not pass",
    sources: { "Empty.kt": "package splice.core\n\npublic class Nothing\n" },
    config: CONFIG_OK,
    wantRed: true,
    mustName: "refusing to pass vacuously",
  });
  cases.push({
    label: "UNTRUSTED: a `fun interface` with no body at all",
    sources: { "Ports.kt": DRIFT_SOURCE },
    config: CONFIG_OK,
    wantRed: true,
    mustName: "disagree",
  });

  for (const c of cases) runCase(c);

  // THE BORING CASE: exactly one interface, no shared signature. GREEN, and the count is visible.
  {
    const root = freshRoot("boring");
    try {
      writeTree(
        root,
        {
          "One.kt":
            "package splice.core\n\npublic fun interface Only {\n" +
            "    public operator fun invoke(): Boolean\n}\n",
        },
        CONFIG_OK,
      );
      const hits = audit(root);
      // The lone interface is green on its own account; the shipped CONFIG_OK is now stale.
      if (!hits.some((hit) => hit.includes("STALE DISPOSITION"))) {
        failures.push(
          `boring case: a config entry for a vanished group must be STALE, got: ${pyReprList(hits)}`,
        );
      }
      const { roles, problems } = collect(root);
      if (problems.length > 0 || roles.length !== 1 || sharedGroups(roles).size !== 0) {
        failures.push(
          `boring case: expected exactly 1 role and 0 shared signatures, got ` +
            `${roles.length} role(s), ${sharedGroups(roles).size} shared, problems=${pyReprList(problems)}`,
        );
      }
      writeTree(root, {}, "");
      if (audit(root).length > 0) {
        failures.push(
          `boring case: one interface and an empty config must be GREEN, got: ${pyReprList(audit(root))}`,
        );
      }
    } finally {
      rmSync(dirname(root), { recursive: true, force: true });
    }
  }

  // GENERIC NORMALISATION: <T> and <R> are ONE group, so a rename cannot hide a duplicate.
  {
    const root = freshRoot("generic");
    try {
      writeTree(root, { "Generic.kt": GENERIC_SOURCE }, "");
      const { roles } = collect(root);
      const groups = [...sharedGroups(roles).keys()];
      if (groups.length !== 1 || groups[0] !== "suspend ()->#1") {
        failures.push(
          `generic normalisation: <T> and <R> must normalise into ONE group, got ${pyReprList(groups)}`,
        );
      }
      writeTree(root, { "Generic.kt": GENERIC_SOURCE }, CONFIG_OK_GENERIC.replace(CONFIG_OK, ""));
      if (audit(root).length > 0) {
        failures.push(
          `generic normalisation: the dispositioned twin must be GREEN, got: ${pyReprList(audit(root))}`,
        );
      }
    } finally {
      rmSync(dirname(root), { recursive: true, force: true });
    }
  }

  // SUSPEND IS PART OF THE SIGNATURE: measured, not asserted.
  {
    const root = freshRoot("suspend");
    try {
      writeTree(root, { "Ports.kt": COMPLIANT_SOURCE }, "");
      const { roles } = collect(root);
      const byName = new Map(roles.map((r) => [r.name, r.signature]));
      if (byName.get("Ticker") === byName.get("PidAlive")) {
        failures.push(
          "suspend must separate Ticker from PidAlive; both normalised to " + `${byName.get("Ticker")}`,
        );
      }
      if (byName.get("IdentitySource") !== "()->String?") {
        failures.push(
          "an interface with defaulted methods and nested types must yield its ONE abstract " +
            `method, got ${pyRepr(byName.get("IdentitySource") ?? "")}`,
        );
      }
    } finally {
      rmSync(dirname(root), { recursive: true, force: true });
    }
  }

  if (failures.length > 0) {
    process.stdout.write("role-registry SELFTEST FAIL:\n");
    for (const failure of failures) process.stdout.write("  " + failure + "\n");
    return 1;
  }
  process.stdout.write(
    "role-registry SELFTEST OK — a dispositioned group is green; a third name joining it, an " +
      "undispositioned group, a missing config, a whitespace reason, a missing date, a stale name, " +
      "a stale entry, a doubled entry, zero interfaces and an unaccounted declaration are all red " +
      "by name; suspend and arity keep distinct seams apart; <T> and <R> normalise into one group; " +
      "the boring one-interface tree is green with count 1\n",
  );
  return 0;
}

/** Python's repr() of a string. */
function pyRepr(value: string): string {
  return `'${value.replace(/\\/g, "\\\\").replace(/'/g, "\\'")}'`;
}

/** Python's repr() of a list of strings: `['a', 'b']`. */
function pyReprList(items: string[]): string {
  return `[${items.map(pyRepr).join(", ")}]`;
}

const USAGE = `usage: role-registry [check | report] [<root>] [--config=<path>] [--selftest]
  check      gate leg: every shared signature must be dispositioned in writing (a bare run does this)
  report     the whole census, for writing or reviewing a disposition
  --selftest red-green proof, out of tree
  --config=  the disposition file (default: ${CONFIG_REL})
`;

function main(argv: string[]): number {
  if (argv.includes("--selftest")) return selftest();
  let root = ROOT;
  let configRel = CONFIG_REL;
  const positional = argv.filter((a) => !a.startsWith("-") && a !== "check" && a !== "report");
  if (positional.length > 0) root = positional[0];
  for (const arg of argv) {
    if (arg.startsWith("--config=")) configRel = arg.split("=").slice(1).join("=");
    else if (arg.startsWith("-") && arg !== "--selftest") {
      // An unknown flag is misuse, never a silent fall-through to the gate.
      if (!["check", "report"].includes(arg)) {
        process.stderr.write(`role-registry: unrecognised argument '${arg}'\n${USAGE}`);
        return 1;
      }
    }
  }
  if (!existsSync(root)) {
    process.stderr.write(`role-registry: tree ${root} missing\n`);
    return 1;
  }
  const resolved = realpathSync(root);
  if (argv.includes("report")) {
    report(resolved, configRel);
    return 0;
  }
  const problems = audit(resolved, configRel);
  if (problems.length > 0) {
    const { roles } = collect(resolved);
    process.stdout.write("role-registry RED:\n");
    for (const problem of problems) process.stdout.write("  " + problem + "\n");
    process.stdout.write(
      `  (census: ${roles.length} \`fun interface\` declaration(s), ` +
        `${sharedGroups(roles).size} signature(s) shared by 2+ names)\n`,
    );
    return 1;
  }
  const { roles } = collect(resolved);
  process.stdout.write(
    `role-registry GREEN: ${roles.length} \`fun interface\` declaration(s); every one of the ` +
      `${sharedGroups(roles).size} shared signature(s) is accounted for in ${configRel} with a ` +
      "written reason\n",
  );
  return 0;
}

// The port of Python's `if __name__ == "__main__"` guard. checks/role-registry-selftest.sh imports
// this module to compare the regex census against ast-grep's AST census, and an unguarded exit()
// would make the module unimportable — the checker would run and kill the importer.
if (import.meta.main) process.exit(main(process.argv.slice(2)));
