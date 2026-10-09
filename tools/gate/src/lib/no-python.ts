/**
 * THE RULE: this repo has no Python. Tooling is bun/TypeScript.
 *
 * ONE library, TWO lifecycles (restructure plan §4.2): `gate no-python` is the gate leg and
 * `gate no-python --guard` is the PreToolUse hook (.claude/settings.json), and both run the
 * predicates below rather than a second implementation of the same idea. Two hand-written copies
 * of one rule drift, and then agree with each other while disagreeing with the tree — the failure
 * this whole wall family is named for.
 *
 * WHY THIS FILE EXISTS AND THE RULE ALONE DID NOT WORK. The rule was stated, repeatedly, and
 * drifted every time — because nothing failed when a session added another .py, and because the
 * tree taught the opposite of the rule. On 2026-09-18 it held 96 Python files and 35,166 Python
 * lines against ZERO .ts outside webui/. A session that reads "match the surrounding style" and
 * then looks at the surrounding style learns Python.
 *
 * So the rule is a WALL, in the idiom the rest of the gate uses:
 *
 *   · A .py FAILS, tracked or not. `git ls-files` alone is blind to the scratch script that has not
 *     been added yet, which is the state every tracked .py passed through on its way in.
 *   · A FILE THAT RUNS OR INSTALLS THE INTERPRETER FAILS, judged by STRUCTURE and not by text:
 *     a shebang; the commands of a shell script, a workflow step, a Dockerfile instruction, a
 *     package script (no-python-shell.ts); the spawn calls of a TypeScript, Kotlin
 *     or Java source (no-python-sources.ts); a launcher entry whose command is the interpreter. A
 *     sentence, a comment, a label or an `echo` that names it is not an invocation.
 *   · THERE IS NO LIST AND NO EXEMPTION. Every census is graded against zero, and this library, its
 *     command and its tests are judged like any other file: a law that exempts its own declaration
 *     site is a law that can be broken from there. Their pattern strings are not invocations, so they
 *     need no allowance.
 *
 * The denominator comes from git, never from a list (global rules §24): ONE domain for every kind of
 * check, the tracked files plus the untracked files that are not ignored, enumerated NUL-separated so
 * that no path is ever quoted. A path that cannot be read fails the wall by name; nothing is skipped.
 * Every census takes the repository root explicitly so the test arms can grade fixture roots in-process.
 */
import { spawnSync } from "node:child_process";
import { existsSync, lstatSync, readFileSync } from "node:fs";
import { basename, isAbsolute, join, relative, resolve } from "node:path";
import { layout } from "./repo.ts";
import {
  analyzeShell,
  analyzeWords,
  type Call,
  type ShellWord,
} from "./no-python-shell.ts";
import { commandsIn, sourceFactsJvm, sourceFactsTs, type SourceFacts } from "./no-python-sources.ts";

/** A census that could not run. Exit 2, never a pass: the gate leg propagates it and the guard
 *  fails OPEN on it (see the command). */
export class WallError extends Error {
  constructor(message: string, readonly exit = 2) {
    super(message);
  }
}

const at = (root: string, f: string) => join(root, f);

// ─── the denominator ─────────────────────────────────────────────────────────────────────────────

function lsZ(root: string, ...args: string[]): string[] {
  const r = spawnSync("git", ["ls-files", "-z", ...args], { cwd: root, encoding: "utf8", maxBuffer: 1 << 28 });
  if (r.status !== 0) {
    throw new WallError(`no-python: git ls-files failed (${(r.stderr ?? "").trim()}) — refusing to report a pass for a census that did not run`);
  }
  return r.stdout.split("\0").filter(Boolean);
}

export interface Enumeration {
  /** tracked and present in the worktree */
  readonly tracked: readonly string[];
  /** untracked and not ignored */
  readonly untracked: readonly string[];
  /** both: the one domain every check reads */
  readonly all: readonly string[];
}

/** Tracked plus untracked-not-ignored, minus what the worktree deleted without staging it. */
export function enumerate(root: string): Enumeration {
  const deleted = new Set(lsZ(root, "--deleted"));
  const tracked = lsZ(root, "--cached").filter((f) => !deleted.has(f)).sort();
  const untracked = lsZ(root, "--others", "--exclude-standard").sort();
  return { tracked, untracked, all: [...tracked, ...untracked].sort() };
}

/** The text of a regular file; null for what is not one (a symlink, a directory, a submodule). A file that
 *  is one and cannot be read fails the wall by name. */
function readText(root: string, f: string): string | null {
  const p = at(root, f);
  try {
    if (!lstatSync(p).isFile()) return null;
    return readFileSync(p, "utf8");
  } catch (e) {
    throw new WallError(`no-python: cannot read ${f}: ${e instanceof Error ? e.message : e}`);
  }
}

// ─── what a file is ──────────────────────────────────────────────────────────────────────────────

const SHEBANG_INTERPRETER = /^#!.*\b(?:python|pypy)/;
const SHEBANG_SHELL = /^#!\s*(?:\S*\/)?(?:env\s+(?:-\S+\s+)*)?(?:ba|z|da|k|a)?sh\b/;

type Kind = "shell" | "yaml" | "docker" | "package" | "json" | "script" | "jvm" | "other";

function kindOf(path: string, text: string): Kind {
  const base = basename(path);
  const first = text.split("\n", 1)[0] ?? "";
  if (base === "package.json") return "package";
  if (/^Dockerfile/i.test(base) || /\.dockerfile$/i.test(base)) return "docker";
  if (/\.ya?ml$/.test(base)) return "yaml";
  if (/\.jsonc?$/.test(base)) return "json";
  if (/\.(?:ts|tsx|mts|cts|js|jsx|mjs|cjs)$/.test(base)) return "script";
  if (/\.(?:kt|kts|java)$/.test(base)) return "jvm";
  if (/\.(?:sh|bash|zsh|mk)$/.test(base) || /^Makefile/.test(base) || SHEBANG_SHELL.test(first)) return "shell";
  return "other";
}

// ─── extraction: the commands a file would run ───────────────────────────────────────────────────

interface Extract {
  interpreter: boolean;
  shell: string[];
  argv: ShellWord[][];
}

const word = (value: string): ShellWord => ({ raw: JSON.stringify(value), value });

function dockerExtract(text: string, out: Extract): void {
  const lines: string[] = [];
  let carry = "";
  for (const line of text.split("\n")) {
    if (!carry && /^\s*#/.test(line)) continue;
    const joined = carry + line;
    if (/\\\s*$/.test(joined)) carry = joined.replace(/\\\s*$/, " ");
    else {
      lines.push(joined);
      carry = "";
    }
  }
  if (carry) lines.push(carry);
  for (const line of lines) {
    const m = /^\s*(?:ONBUILD\s+)?([A-Za-z]+)\s+([\s\S]*)$/.exec(line);
    if (!m) continue;
    const instruction = m[1]!.toUpperCase();
    let rest = m[2]!.trim();
    if (instruction === "FROM") {
      const image = rest.split(/\s+/).find((w) => !w.startsWith("--")) ?? "";
      const name = image.split("/").pop()!.split(/[:@]/)[0]!;
      if (/^(?:python|pypy)\d*$/.test(name)) out.interpreter = true;
      continue;
    }
    if (instruction === "HEALTHCHECK") rest = rest.replace(/^(?:--\S+\s+)*CMD\s+/i, "");
    else if (instruction !== "RUN" && instruction !== "CMD" && instruction !== "ENTRYPOINT") continue;
    rest = rest.replace(/^(?:--\S+\s+)+/, ""); // RUN --mount=... --network=...
    if (rest.startsWith("[")) {
      try {
        const argv = JSON.parse(rest);
        if (Array.isArray(argv) && argv.every((a) => typeof a === "string")) {
          out.argv.push(argv.map(word));
          continue;
        }
      } catch {
        // `[ -f x ]` is a shell test, not an exec form
      }
    }
    out.shell.push(rest);
  }
}

/** Walk a parsed YAML tree: `run` values are shell, `uses` names an action. */
function yamlExtract(node: unknown, out: Extract): void {
  if (Array.isArray(node)) {
    for (const child of node) yamlExtract(child, out);
  } else if (node && typeof node === "object") {
    for (const [key, value] of Object.entries(node)) {
      if (key === "run" && typeof value === "string") out.shell.push(value);
      else if (key === "uses" && typeof value === "string" && /(?:^|\/)setup-python(?:@|$)/.test(value)) out.interpreter = true;
      else yamlExtract(value, out);
    }
  }
}


/** What the shell-and-spawn layer can read from one file, before any ast-grep scan. */
function extract(path: string, text: string, lenient: boolean): Extract {
  const out: Extract = { interpreter: SHEBANG_INTERPRETER.test(text.split("\n", 1)[0] ?? ""), shell: [], argv: [] };
  const fail = (e: unknown): never => {
    throw new WallError(`no-python: cannot parse ${path}: ${e instanceof Error ? e.message : e}`);
  };
  switch (kindOf(path, text)) {
    case "shell":
      out.shell.push(text);
      break;
    case "docker":
      dockerExtract(text, out);
      break;
    case "yaml":
      try {
        yamlExtract(Bun.YAML.parse(text), out);
      } catch (e) {
        if (!lenient) fail(e);
        for (const m of text.matchAll(/^\s*(?:-\s+)?run:\s*(.+)$/gm)) {
          try {
            const value = Bun.YAML.parse(m[1]!);
            if (typeof value === "string") out.shell.push(value);
          } catch {
            out.shell.push(m[1]!);
          }
        }
      }
      break;
    case "package": {
      let scripts: unknown;
      try {
        scripts = (JSON.parse(text) as { scripts?: unknown }).scripts;
      } catch (e) {
        if (!lenient) fail(e);
        for (const m of text.matchAll(/^\s*"[^"]+"\s*:\s*("(?:[^"\\]|\\.)*")\s*,?\s*$/gm)) out.shell.push(JSON.parse(m[1]!));
      }
      if (scripts && typeof scripts === "object") {
        for (const v of Object.values(scripts)) if (typeof v === "string") out.shell.push(v);
      }
      break;
    }
    case "json":
      try {
        for (const command of commandsIn(Bun.JSONC.parse(text))) out.argv.push([word(command)]);
      } catch (e) {
        if (!lenient) fail(e);
      }
      break;
    case "script": {
      const s: SourceFacts = sourceFactsTs(path, text);
      out.argv.push(...s.argv);
      out.shell.push(...s.shell);
      break;
    }
    default:
      break;
  }
  return out;
}

// ─── facts per file ──────────────────────────────────────────────────────────────────────────────

export interface FileFacts {
  /** the file runs or installs the interpreter */
  readonly interpreter: boolean;
  /** the script files its command lines run (`bun x.ts`, the interpreter on a script) */
  readonly calls: readonly Call[];
}

export interface Source {
  readonly path: string;
  readonly text: string;
}

/** Only a source file that names the interpreter or bun at all can spawn them; this skips the rest before the scan. */
const MENTIONS = /python|\bpip|pypy|\bbun\b/i;

/** Facts for a batch of files, with ONE shell scan per nesting level and one scan per JVM language. */
export function factsOf(root: string, sources: readonly Source[], lenient = false): Map<string, FileFacts> {
  const extracted = sources.map((s) => ({ path: s.path, ...extract(s.path, s.text, lenient) }));
  const jvm = sources.filter((s) => kindOf(s.path, s.text) === "jvm" && MENTIONS.test(s.text));
  const jvmFacts = sourceFactsJvm(root, jvm);
  for (const e of extracted) {
    const j = jvmFacts.get(e.path);
    if (j) {
      e.argv.push(...j.argv);
      e.shell.push(...j.shell);
    }
  }
  const snippets: { owner: number; text: string }[] = [];
  const interpreter = extracted.map((e) => e.interpreter);
  const calls: Call[][] = extracted.map(() => []);
  extracted.forEach((e, owner) => {
    for (const text of e.shell) snippets.push({ owner, text });
    for (const argv of e.argv) {
      const facts = analyzeWords(argv);
      if (facts.interpreter) interpreter[owner] = true;
      calls[owner]!.push(...facts.calls);
      for (const text of facts.nested) snippets.push({ owner, text });
    }
  });
  const shell = analyzeShell(root, snippets.map((s) => s.text), snippets.map((s) => extracted[s.owner]!.path));
  shell.forEach((facts, k) => {
    const owner = snippets[k]!.owner;
    if (facts.interpreter) interpreter[owner] = true;
    calls[owner]!.push(...facts.calls);
  });
  return new Map(extracted.map((e, i) => [e.path, { interpreter: interpreter[i]!, calls: calls[i]! }]));
}

/** Does this file RUN or install the interpreter? [path] picks the reading; [text] is what would be in the file. */
export function invokesPython(path: string, text: string, root: string = layout().repoRoot): boolean {
  return factsOf(root, [{ path, text }], true).get(path)!.interpreter;
}

/** Calls whose interpreter does not match the script's extension: the interpreter on a .ts, bun on a .py. */
export function mismatchedCalls(calls: readonly Call[]): string[] {
  const out: string[] = [];
  for (const { runtime, target } of calls) {
    if (runtime === "py" && target.endsWith(".ts")) out.push(`python3 ${target} (a .ts run by the interpreter)`);
    if (runtime === "bun" && target.endsWith(".py")) out.push(`bun ${target} (a .py run by bun)`);
  }
  return out;
}

/** The surface whose command lines are literal: shell scripts, workflows, package scripts and .mjs. */
function isCaller(path: string, text: string): boolean {
  const kind = kindOf(path, text);
  return kind === "shell" || kind === "package" || /\.mjs$/.test(path) || (kind === "yaml" && /^\.github\/workflows\//.test(path));
}

/** Facts for every file in the one domain, read once. */
interface Scan {
  readonly root: string;
  readonly enumeration: Enumeration;
  readonly texts: ReadonlyMap<string, string>;
  readonly facts: ReadonlyMap<string, FileFacts>;
}

function scan(root: string): Scan {
  const enumeration = enumerate(root);
  const texts = new Map<string, string>();
  for (const f of enumeration.all) {
    const text = readText(root, f);
    if (text !== null) texts.set(f, text);
  }
  const facts = factsOf(root, [...texts].map(([path, text]) => ({ path, text })));
  return { root, enumeration, texts, facts };
}

// ─── the censuses ────────────────────────────────────────────────────────────────────────────────

/** THE FIRST CENSUS: .py files that exist in the worktree, tracked. */
function trackedPy(s: Scan): string[] {
  return s.enumeration.tracked.filter((f) => f.endsWith(".py"));
}

/** THE THIRD CENSUS: Python that is not in git at all (not ignored: a vendored dependency is not the author's). */
function untrackedPy(s: Scan): string[] {
  return s.enumeration.untracked.filter((f) => f.endsWith(".py"));
}

/** THE SECOND CENSUS: every file in the one domain that runs or installs the interpreter. `.py` files are the
 *  file censuses' business. */
function invokers(s: Scan): string[] {
  return [...s.facts].filter(([f, facts]) => !f.endsWith(".py") && facts.interpreter).map(([f]) => f).sort();
}

const interpolated = (target: string) => /[$`{}*]/.test(target) || isAbsolute(target);

/** THE FOURTH CENSUS: a CALLER that outlived the file it calls. The wall was green on 2026-09-18 while the gate of
 *  record was red: a converted script's second invocation still read `python3 ...py --selftest` and failed with
 *  "No such file or directory". Paths resolve from the repo root; an interpolated path cannot be checked, a limit of
 *  the instrument and not a pass. */
function dangling(s: Scan): string[] {
  const out = new Set<string>();
  for (const [caller, facts] of s.facts) {
    if (!isCaller(caller, s.texts.get(caller)!)) continue;
    for (const { target } of facts.calls) {
      if (!interpolated(target) && !existsSync(at(s.root, target))) out.add(`${caller} -> ${target}`);
    }
  }
  // A REGISTRY row whose wall= names a file that is gone: every tracked .toml, parsed.
  for (const [registry, text] of s.texts) {
    if (!registry.endsWith(".toml")) continue;
    let doc: unknown;
    try {
      doc = Bun.TOML.parse(text);
    } catch (e) {
      throw new WallError(`no-python: cannot parse ${registry}: ${e instanceof Error ? e.message : e}`);
    }
    for (const target of wallFields(doc)) {
      if (!existsSync(at(s.root, target))) out.add(`${registry} -> ${target} (registry wall= names a missing file)`);
    }
  }
  return [...out].sort();
}

function wallFields(node: unknown): string[] {
  if (Array.isArray(node)) return node.flatMap(wallFields);
  if (!node || typeof node !== "object") return [];
  return Object.entries(node).flatMap(([k, v]) => (k === "wall" && typeof v === "string" && v ? [v] : wallFields(v)));
}

/** THE SEVENTH CENSUS: every caller read for a mismatched runtime, on the same surface as the dangling census. */
function runtimeMismatch(s: Scan): string[] {
  const out: string[] = [];
  for (const [caller, facts] of s.facts) {
    if (!isCaller(caller, s.texts.get(caller)!)) continue;
    for (const hit of mismatchedCalls(facts.calls)) out.push(`${caller}: ${hit}`);
  }
  return out.sort();
}

export interface WallReport {
  /** the census table, printed on every run */
  readonly lines: readonly string[];
  readonly problems: readonly string[];
  /** the closing line for a clean run */
  readonly summary: string;
}

/** The whole wall over `root`. Throws WallError for a census that could not run. Every census is graded against
 *  ZERO: the burn-down that once carried this repo's debt reached it on 2026-10-09 and was deleted with its ratchet. */
export function wall(root: string): WallReport {
  const s = scan(root);
  const censuses: [label: string, found: string[], help: string][] = [
    ["tracked .py files", trackedPy(s), "This repo is bun/TypeScript; write it as .ts and run it with bun."],
    [
      "files that RUN or install python",
      invokers(s),
      "Convert the call to bun. A file that shells into python3 is Python this repo still runs.",
    ],
    [
      "UNTRACKED .py in the worktree",
      untrackedPy(s),
      "Move a throwaway to a scratch directory OUTSIDE the worktree, or write it as .ts. `git ls-files` cannot see it, " +
        "which is how every tracked .py got here in the first place.",
    ],
    [
      "call sites naming a missing file",
      dangling(s),
      "A conversion deleted the file and left a caller pointing at it. Repoint the call at the .ts, and grep for the " +
        "stem: a converted script usually has more than one call site.",
    ],
    [
      "callers running the WRONG runtime",
      runtimeMismatch(s),
      "A half-finished conversion: the filename moved and the interpreter did not. Change the interpreter to match " +
        "the extension.",
    ],
  ];
  const n = (v: number) => String(v).padStart(4);
  const lines = [
    "NO-PYTHON WALL — this repo has no Python",
    ...censuses.map(([label, found]) => `  ${label.padEnd(35)}measured ${n(found.length)}   allowed ${n(0)}   [GATED]`),
  ];
  const problems = censuses
    .filter(([, found]) => found.length > 0)
    .map(([label, found, help]) => `${label.toUpperCase()}: ${found.length}. ${help}\n    ${found.join("\n    ")}`);
  return {
    lines,
    problems,
    summary: "OK: no-python wall holds — no .py, nothing that runs or installs python, tracked or not",
  };
}

// ─── the write-time half ───────────────────────────────────────────────────────────────────────────
//
// WHY A GATE LEG WAS NOT ENOUGH, measured rather than argued. On 2026-09-18 a webui commit (1b56f197) added a fresh
// python3 subprocess to density.mjs and the branch went red — but not at write time, and not at commit time. It went
// red HOURS LATER, the next time somebody happened to run the wall. A rule that fails late is a rule the tree teaches
// against in the meantime. The operator's ruling: "it should be a PreToolUse hook that refuses the write and returns
// with a message."
//
// WHAT IT REFUSES, and nothing more: any .py file written into the repo; a write whose TEXT runs or installs the
// interpreter (invokesPython, judged on what the call would put into the file); and a caller line running a file with
// the wrong runtime.

type Event = { tool_name?: string; tool_input?: Record<string, unknown> };

/** The text this tool call would PUT INTO the file — never the file's current contents. An Edit is charged on
 *  `new_string` alone so that touching an unrelated line of a file that already mentions python is not refused; the
 *  gate leg owns the whole-file verdict. */
function proposedText(tool: string, input: Record<string, unknown>): string {
  if (tool === "Write") return String(input.content ?? "");
  if (tool === "Edit") return String(input.new_string ?? "");
  if (tool === "MultiEdit") {
    const edits = Array.isArray(input.edits) ? input.edits : [];
    return edits.map((e) => String((e as Record<string, unknown>)?.new_string ?? "")).join("\n");
  }
  return "";
}

/** The block reason for a PreToolUse event, or null to allow the write. */
export function guardVerdict(root: string, data: Event): string | null {
  const tool = data.tool_name ?? "";
  if (tool !== "Write" && tool !== "Edit" && tool !== "MultiEdit") return null;
  const input = data.tool_input ?? {};
  const filePath = String(input.file_path ?? "");
  if (!filePath) return null;

  const rel = relative(root, isAbsolute(filePath) ? filePath : resolve(root, filePath));
  // Outside this repo (a scratch directory, another worktree) is not this wall's business.
  if (!rel || rel.startsWith("..")) return null;

  if (rel.endsWith(".py")) {
    return (
      `REFUSED — this repo has no Python; tooling is bun/TypeScript.\n\n` +
      `  ${rel} is a .py file.\n\n` +
      `Write it as .ts and run it with bun. If this is a throwaway, put it in a scratch directory outside the\n` +
      `worktree instead.`
    );
  }

  const text = proposedText(tool, input);
  const facts = factsOf(root, [{ path: rel, text }], true).get(rel)!;
  const crossed = isCaller(rel, text) ? mismatchedCalls(facts.calls) : [];
  if (crossed.length) {
    return (
      `REFUSED — wrong runtime for the file's extension.\n\n` +
      `  ${rel}\n    ` +
      crossed.join("\n    ") +
      `\n\nThis is a half-finished conversion: the filename moved and the interpreter did not. It would\n` +
      `not fail here — the path resolves and the file exists — it would fail later at run time with a syntax\n` +
      `error that reads like a broken script rather than a broken call. Change the interpreter to match the\n` +
      `extension.`
    );
  }

  if (facts.interpreter) {
    return (
      `REFUSED — this repo has no Python; tooling is bun/TypeScript.\n\n` +
      `  This write makes ${rel} run or install python.\n\n` +
      `If it SHELLS OUT: do the work in bun instead. The last file to do this decoded a PNG through\n` +
      `a subprocess; zlib and forty lines of filter cases replaced it, byte-identical over seven\n` +
      `frames. A comment, a README or a docstring that merely names the interpreter is not refused.`
    );
  }
  return null;
}
