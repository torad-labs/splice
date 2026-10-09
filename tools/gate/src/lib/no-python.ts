/**
 * THE RULE: this repo has no Python. Tooling is bun/TypeScript.
 *
 * ONE library, TWO lifecycles (restructure plan §4.2): `gate no-python` is the gate leg and
 * `gate no-python --guard` is the PreToolUse hook (.claude/settings.json), and both run the
 * predicates below rather than a second implementation of the same idea. Two hand-written copies
 * of one rule drift, and then agree with each other while disagreeing with the tree — the failure
 * this whole wall family is named for. The previous homes were checks/no-python.ts (the wall) and
 * checks/no-python-write-guard.ts (the hook), which imported the wall's predicates; PR 5 folded
 * them into this file with their selftests as test/no-python.test.ts.
 *
 * WHY THIS FILE EXISTS AND THE RULE ALONE DID NOT WORK. The rule was stated, repeatedly, and
 * drifted every time — because nothing failed when a session added another .py, and because the
 * tree taught the opposite of the rule. On 2026-09-18 it held 96 Python files and 35,166 Python
 * lines against ZERO .ts outside webui/. A session that reads "match the surrounding style" and
 * then looks at the surrounding style learns Python. The clearest evidence of the drift WAS
 * .dev/web-console/idle-watch.py, whose own docstring recorded that it was "vendored from
 * grailseeker-bot .dev/campaigns/idle-watch.ts ... ported to python" — a TypeScript original,
 * deliberately converted the wrong way. M1-88 ported it back on 2026-09-18 and its burndown line
 * burned off with it; the scar is kept because the wall is the reason it got fixed.
 *
 * So the rule is a WALL, in the idiom the rest of the gate uses:
 *
 *   · A .py FAILS. Any tracked Python file is a hard error naming the file. This is the leg that
 *     stops the drift.
 *   · A FILE THAT RUNS OR INSTALLS PYTHON FAILS, judged by structure (invokesPython): a shebang, a
 *     command in a script, workflow or Dockerfile, a process spawn, a launcher config, a live ledger
 *     verify=. A sentence that names the interpreter is not an invocation.
 *   · AN UNTRACKED .py FAILS. The legs above read `git ls-files` and are therefore blind to the
 *     scratch script that has not been added yet — which is the state every tracked .py passed
 *     through on its way in.
 *   · THERE IS NO LIST. Until 2026-10-09 a dated burn-down carried the debt that predated the wall
 *     and a git-history ratchet kept it from growing; it reached zero that day and was deleted with
 *     the ratchet. Every census is graded against zero, so nothing can be added to make the gate pass.
 *
 * The denominator comes from `git ls-files`, never from a list (campaign law 24). Every census takes
 * the repository root explicitly so the test arms can grade fixture repositories in-process.
 */
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { isAbsolute, join, relative, resolve } from "node:path";

/** A census that could not run. Exit 2, never a pass: the gate leg propagates it and the guard
 *  fails OPEN on it (see the command). */
export class WallError extends Error {
  constructor(message: string, readonly exit = 2) {
    super(message);
  }
}

function gitLs(root: string, ...pathspec: string[]): string[] {
  const r = spawnSync("git", ["ls-files", ...pathspec], { cwd: root, encoding: "utf8" });
  if (r.status !== 0) {
    throw new WallError(`no-python: git ls-files failed (${r.stderr.trim()}) — refusing to report a pass for a census that did not run`);
  }
  return r.stdout.split("\n").map((s) => s.trim()).filter(Boolean).sort();
}

const at = (root: string, f: string) => join(root, f);

/** The tracked files whose invocations are literal command lines: shell scripts, .mjs, package.json
 *  scripts and CI's workflows, whose `run:` lines call the gate's scripts too (V4-297: neither caller
 *  census read them). One list, so the dangling and runtime censuses read the same surface. */
const CALLERS = ["*.sh", "*.mjs", "package.json", ".github/workflows/*.yml", ".github/workflows/*.yaml"];

/** Tracked .py files THAT ACTUALLY EXIST.
 *
 *  The existsSync filter is not belt-and-braces; without it this census reports a green that is
 *  structurally the two-lists-agreeing failure. Found by splice-builder2 on 2026-09-18: it had
 *  deleted inf_02_every_law_walled.py, the burn-down still carried the line, and this wall read a
 *  matching 87 / 87 — `git ls-files` enumerates what the INDEX tracks, and a file deleted in the
 *  worktree but not yet staged is still tracked (`git status` calls it ` D`). Filtering to what
 *  exists makes the deletion visible the moment it happens: the file leaves `measured`, its line
 *  becomes STALE, and the wall says "remove the line" by name. */
function tracked(root: string): string[] {
  return gitLs(root, "*.py").filter((f) => existsSync(at(root, f)));
}

/** EXCLUDED WITH A WRITTEN REASON, which is a disposition and not a hole (law 24). These files
 *  exist to TALK about Python: the wall, its command and its test arms. Their
 *  prose necessarily contains the word, and counting them would make the wall permanently report
 *  itself. Nothing else is exempt — a file that merely explains a python command is drift and IS
 *  counted, because prose is what teaches the next session which language this repo writes
 *  tooling in. The test arms must FEED the guard the violating text, which is why they are here. */
export const SELF = new Set([
  "tools/gate/src/lib/no-python.ts",
  "tools/gate/src/commands/no-python.ts",
  "tools/gate/test/no-python.test.ts",
]);

const GRAMMAR_IMPORT = /import\s+\w+\s+from\s+['"]highlight\.js\/lib\/languages\/python['"];?/;

/** A highlighter file names Python to COLOUR it: the grammar import, its registry entry and its map value. Only a file that
 *  imports the grammar is read this way, and only those three shapes go; anything that could run (a quoted argument behind
 *  `(` or `[`, `python3`, a command line) stays in the text. */
function displayOnly(text: string): string {
  if (!GRAMMAR_IMPORT.test(text)) return text;
  return text
    .replace(new RegExp(GRAMMAR_IMPORT.source, "g"), "")
    .replace(/(?<=[{,]\s*)python(?=\s*[,}])/g, "")
    .replace(/(?<=:\s*)'python'/g, "''");
}

// ─── invocations by STRUCTURE (lead ruling 2026-10-09) ─────────────────────────────────────────
// A MENTION is not an invocation. The census used to charge any tracked file whose text spelled the
// word, so ledger notes, CHANGELOG lines, an svg screenshot and research prose all counted as Python
// the repo "runs", and the only way to lower the number was to rewrite history. The shapes that DO
// run or install Python, by file kind:
//   · a shebang naming it, in any file;
//   · shell scripts, workflows, Dockerfiles, Makefiles and package.json scripts: python/python3/pip as
//     a COMMAND (start of a command, after `;` `&` `|` `(` `$(`, a keyword such as `run:`/`RUN`/`then`,
//     leading VAR=val words), a package-manager install of it, a `FROM python` image, `setup-python`;
//   · Kotlin, TS, JS, Java sources: a process spawn (ProcessBuilder, spawn*, exec*, Bun.$, Runtime.exec)
//     in a file that also holds a quoted `python`/`python3`/`pip` argument or `python3 …` command string;
//   · a launcher config whose "command" is the interpreter (an MCP server entry), in sources and json;
//   · a LIVE ledger row's verify= field (todo / in_flight), read like a shell line.
// Comment lines never count, and neither do markdown, svg, prose json and other toml.

const COMMAND_WORDS = "run|RUN|CMD|ENTRYPOINT|exec|sudo|time|xargs|then|do|else|env|nohup|npx|command|which|type";
const SHELL_COMMAND = new RegExp(
  `(?:^|[;&|(\`]|\\$\\(|\\b(?:${COMMAND_WORDS})\\b:?|^\\s*-)\\s*(?:[A-Za-z_]\\w*=\\S*\\s+)*(?:python3?|pip3?)(?![\\w.-])`,
);
const PACKAGE_INSTALL = /\b(?:apt(?:-get)?|apk|dnf|yum|brew|pacman|zypper)\b[^#\n]*\binstall\b[^#\n]*\b(?:python3?|py3?-\w+|pip3?)(?![\w.-])/;
const PYTHON_IMAGE = /^\s*FROM\s+\S*python/i;
const SETUP_PYTHON = /\bsetup-python\b/;
const SHEBANG = /^#!.*\bpython/;

const SHELL_KIND = /(?:\.(?:sh|bash|zsh|mk|ya?ml)|(?:^|\/)(?:Dockerfile[^/]*|Makefile))$/;
const SOURCE_KIND = /\.(?:kt|kts|java|ts|tsx|mts|cts|js|mjs|cjs)$/;
const SPAWN_API = /\b(?:ProcessBuilder|Runtime\.getRuntime|spawn|spawnSync|exec|execSync|execFile|execFileSync|Bun\.spawn|Bun\.spawnSync)\s*\(|\bBun\.\$|\$`/;
const QUOTED_COMMAND = /["'`](?:python3?|pip3?)(?:\s[^"'`]*)?["'`]|\$`\s*(?:python3?|pip3?)\s/;

/** A server/launcher config whose command IS the interpreter (an MCP server entry, a task runner). */
const CONFIG_COMMAND = /"command"\s*:\s*"(?:python3?|pip3?)(?:\s[^"]*)?"/;

const commentLine = (line: string) => /^\s*(?:#|\/\/|\*|\/\*)/.test(line);

function shellLineInvokes(line: string): boolean {
  if (commentLine(line)) return false;
  return SHELL_COMMAND.test(line) || PACKAGE_INSTALL.test(line) || PYTHON_IMAGE.test(line) || SETUP_PYTHON.test(line);
}

/** package.json: only the values of "scripts" are commands. An unparseable file is read line by line. */
function packageScriptsInvoke(text: string): boolean {
  try {
    const scripts = (JSON.parse(text) as { scripts?: Record<string, unknown> }).scripts ?? {};
    return Object.values(scripts).some((v) => typeof v === "string" && v.split(/\n|&&|;|\|\|?/).some((c) => shellLineInvokes(c.trim())));
  } catch {
    return text.split("\n").some(shellLineInvokes);
  }
}

/** A ledger: the verify= field of each row that has not run yet (a fragment with no row header is one row). */
function ledgerVerifyInvokes(text: string): boolean {
  const blocks = text.includes("[[items]]") ? text.split(/^\[\[items\]\]$/m).slice(1) : [text];
  return blocks.some((block) => {
    const status = block.match(/^status\s*=\s*"([^"]+)"/m)?.[1] ?? "todo";
    if (status !== "todo" && status !== "in_flight") return false;
    const quoted = block.match(/^verify\s*=\s*(?:"""([\s\S]*?)"""|"((?:[^"\\]|\\.)*)")/m);
    const verify = quoted ? (quoted[1] ?? quoted[2] ?? "") : "";
    return verify.split(/\\n|\n|&&|;|\|\|?/).some((c) => shellLineInvokes(c.trim()));
  });
}

/** Does this file RUN or install Python? [path] picks the reading; [text] is what would be in the file. */
export function invokesPython(path: string, text: string): boolean {
  if (SHEBANG.test(text.split("\n", 1)[0] ?? "")) return true;
  if (/(?:^|\/)package\.json$/.test(path)) return packageScriptsInvoke(text);
  if (/(?:^|\/)\.dev\/campaigns\/[^/]+\.toml$/.test(path) || (path.endsWith(".toml") && text.includes("[[items]]"))) {
    return ledgerVerifyInvokes(text);
  }
  if (SHELL_KIND.test(path)) return text.split("\n").some(shellLineInvokes);
  if (SOURCE_KIND.test(path)) {
    const code = displayOnly(text).split("\n").filter((l) => !commentLine(l));
    return code.some((l) => CONFIG_COMMAND.test(l)) || (code.some((l) => SPAWN_API.test(l)) && code.some((l) => QUOTED_COMMAND.test(l)));
  }
  if (path.endsWith(".json")) return text.split("\n").some((l) => CONFIG_COMMAND.test(l));
  return false;
}

/** A caller line that runs a file with the WRONG RUNTIME for its extension: `python3 wall.ts`, or
 *  `bun wall.py`. Always a defect — the interpreter will not run the file and the leg dies at run
 *  time with a syntax error, not a missing file.
 *
 *  FOUND BY splice-builder2 ON 2026-09-18, FROM ITS OWN SLIP: converting mock_chat.py it edited
 *  inside.sh, replaced the FILENAME and left the INTERPRETER, shipping `python3 .../mock_chat.ts`.
 *  The dangling census grades call sites against EXISTENCE, and mock_chat.ts exists; the ledger
 *  runtime check is scoped to verify= fields because over raw text four of five hits are notes
 *  quoting a command. So the scope here is NON-COMMENT lines only — measured at the time of
 *  writing: 0 live mismatched invocations, so it lands with nothing grandfathered.
 *
 *  THE OPTIONAL QUOTE IS NOT A DETAIL — builder2's actual slip is `python3 "$HERE/mock_chat.ts"`,
 *  quoted because the path is interpolated. A first cut requiring the path to follow the
 *  interpreter directly read 0/0 [GATED] on a tree containing that very line. A wall proven
 *  against a tidied-up version of the defect is a wall proven against nothing.
 *
 *  LIMIT OF THE INSTRUMENT: it reads command lines, so `spawnSync("python3", ["x.ts"])` in a .ts
 *  file is invisible to it. That is a different shape and needs a different reading. */
const MISMATCH = /(?:^|[\s;&|("'`])(python3?|bun)\s+["']?((?:[A-Za-z0-9_.${}/-]*\/)?[A-Za-z0-9_.${}-]+\.(py|ts))\b/g;

export function mismatchedRuntimes(text: string): string[] {
  const out: string[] = [];
  for (const line of text.split("\n")) {
    const bare = line.trimStart();
    // A comment is prose. builder2's measurement is the whole reason this arm can exist at all.
    if (bare.startsWith("#") || bare.startsWith("//") || bare.startsWith("*") || bare.startsWith("/*")) continue;
    for (const [, runtime, target, ext] of line.matchAll(MISMATCH)) {
      const runsPython = runtime!.startsWith("python");
      if (runsPython === (ext === "py")) continue;
      out.push(`${runtime} ${target} (${ext === "ts" ? "a .ts run by python" : "a .py run by bun"})`);
    }
  }
  return out;
}

/** THE SEVENTH CENSUS: every tracked caller read for a mismatched runtime. Same caller surface as
 *  the dangling census ([CALLERS]), because that is the surface whose invocations are literal
 *  command lines. */
function runtimeMismatch(root: string): string[] {
  const out: string[] = [];
  for (const caller of gitLs(root, ...CALLERS)) {
    if (SELF.has(caller)) continue;
    let text: string;
    try {
      text = readFileSync(at(root, caller), "utf8");
    } catch {
      continue;
    }
    for (const hit of mismatchedRuntimes(text)) out.push(`${caller}: ${hit}`);
  }
  return out.sort();
}

/** THE SECOND CENSUS: files that RUN or install Python, by structure (invokesPython). Counting files named
 *  `.py` is not counting PYTHON: measured 2026-09-18, 29 tracked .sh files invoked python3 167 times while the
 *  first census read a triumphant 90. A mention is not an invocation (lead ruling 2026-10-09); `.py` files are
 *  left to the first census. */
function invokers(root: string): string[] {
  return gitLs(root)
    .filter((f) => !f.endsWith(".py") && !SELF.has(f))
    .filter((f) => {
      try {
        return invokesPython(f, readFileSync(at(root, f), "utf8"));
      } catch {
        return false; // a binary or unreadable blob invokes nothing
      }
    })
    .sort();
}

/** THE THIRD CENSUS: Python that is not in git at all. `git ls-files` sees what SHIPS, and Python
 *  arrives as a scratch script written in the worktree and added later (console/.m1-34.py, 196
 *  untracked lines, twenty minutes after this wall landed). No allowlist: an untracked file is
 *  newer than the list by definition. --exclude-standard is load-bearing: a vendored dependency's
 *  Python (node_modules/flatted/python/flatted.py) is not charged to the author. */
function untracked(root: string): string[] {
  return gitLs(root, "--others", "--exclude-standard", "*.py");
}

/** THE FOURTH CENSUS: a CALLER that outlived the file it calls. The wall was green on 2026-09-18
 *  while the gate of record was red: a converted script's second gate.sh invocation still read
 *  `python3 ...py --selftest` and failed with "No such file or directory". Both the builder and
 *  the orchestrator had verified the FILES against each other and neither the WIRING. No
 *  allowlist. Paths resolve from the REPO ROOT, the house convention; interpolated paths are
 *  invisible, a limit of the instrument and not a pass. */
// V4-297: the optional quote, as in MISMATCH — `bun "tools/gone.ts"` read as no call at all.
const INVOCATION = /(?:python3|bun)\s+["']?([A-Za-z0-9_./-]+\.(?:py|ts))/g;

function dangling(root: string): string[] {
  const out = new Set<string>();
  for (const caller of gitLs(root, ...CALLERS)) {
    let text: string;
    try {
      text = readFileSync(at(root, caller), "utf8");
    } catch {
      continue; // a path git tracks but the worktree lacks is the stale arm's business
    }
    for (const [, target] of text.matchAll(INVOCATION)) {
      if (!existsSync(at(root, target!))) out.add(`${caller} -> ${target}`);
    }
  }
  // THE FIFTH SURFACE: a REGISTRY row whose wall= names a file that is gone. There are TWO
  // registries (wall_registry.toml keyed id=, law_registry.toml keyed tag=) and a wall may appear
  // in either, so this reads every tracked .toml. An EMPTY wall= is the registry's own spelling for
  // "no wall yet" and reads RED in the campaign gate rather than here.
  for (const registry of gitLs(root, "*.toml")) {
    let text: string;
    try {
      text = readFileSync(at(root, registry), "utf8");
    } catch {
      continue;
    }
    for (const [, target] of text.matchAll(/^\s*wall\s*=\s*"([^"]+)"/gm)) {
      if (!existsSync(at(root, target!))) out.add(`${registry} -> ${target} (registry wall= names a missing file)`);
    }
  }
  return [...out].sort();
}

/** THE FIFTH CENSUS: a ledger instruction that names a file the burn-down is about to delete.
 *  ONLY ROWS THAT WILL ACTUALLY RUN ARE GRADED: a done/verified row's verify is a historical
 *  record of the gate that ran; a todo/in_flight row's is an instruction. Read as TEXT, not through
 *  the CLI: the CLI is the only WRITE channel, and a wall that booted the write path to take a
 *  reading would be a checker with a side effect. */
function staleVerifies(root: string): string[] {
  const out: string[] = [];
  for (const ledger of gitLs(root, ".dev/campaigns/*.toml")) {
    let text: string;
    try {
      text = readFileSync(at(root, ledger), "utf8");
    } catch {
      continue;
    }
    for (const block of text.split(/^\[\[items\]\]$/m).slice(1)) {
      const status = block.match(/^status\s*=\s*"([^"]+)"/m)?.[1] ?? "";
      if (status !== "todo" && status !== "in_flight") continue;
      const id = block.match(/^id\s*=\s*"([^"]+)"/m)?.[1] ?? "(unidentified row)";
      const quoted = block.match(/^verify\s*=\s*(?:"""([\s\S]*?)"""|"((?:[^"\\]|\\.)*)")/m);
      const verify = quoted ? (quoted[1] ?? quoted[2] ?? "") : "";
      // .py ONLY: a live row may legitimately name a .ts that does not exist yet, because the row
      // is what CREATES it (declare-then-earn). A missing .py can never be that.
      for (const target of new Set(verify.match(/[A-Za-z0-9_./-]+\.py\b/g) ?? [])) {
        if (!existsSync(at(root, target))) out.push(`${ledger} ${id} [${status}] -> ${target} (file is gone)`);
      }
      // A verify names a RUNTIME as well as a path; scoped to verify= fields because over raw
      // ledger text four of five hits are NOTES quoting a command.
      for (const [, runtime, target] of verify.matchAll(/(python3?|bun)\s+([A-Za-z0-9_./-]+\.(?:py|ts))/g)) {
        const wrong = runtime!.startsWith("python") ? target!.endsWith(".ts") : target!.endsWith(".py");
        if (wrong) out.push(`${ledger} ${id} [${status}] -> ${runtime} ${target} (wrong runtime for that extension)`);
      }
      // THE FOURTH CALLER SURFACE: files= on live rows, literal paths only (globs cannot be
      // existence-checked: a glob matching nothing is a legitimate fence for work not yet done).
      const files = block.match(/^files\s*=\s*\[([\s\S]*?)\]/m);
      for (const [, entry] of (files?.[1] ?? "").matchAll(/"([^"]+)"/g)) {
        if (entry!.includes("*") || !entry!.endsWith(".py") || existsSync(at(root, entry!))) continue;
        out.push(`${ledger} ${id} [${status}] -> ${entry} (files= fence names a file that is gone)`);
      }
    }
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

/** The whole wall over `root`. Throws WallError for a census that could not run. Every census is graded
 *  against ZERO: the burn-down that once carried this repo's Python debt reached it on 2026-10-09 and was deleted
 *  with its ratchet, so there is no list left to grow. */
export function wall(root: string): WallReport {
  const censuses: [label: string, found: string[], help: string][] = [
    [
      "tracked .py files",
      tracked(root),
      "This repo is bun/TypeScript; write it as .ts and run it with bun.",
    ],
    [
      "files that RUN or install python",
      invokers(root),
      "Convert the call to bun. A file that shells into python3 is Python this repo still runs.",
    ],
    [
      "UNTRACKED .py in the worktree",
      untracked(root),
      "Move a throwaway to a scratch directory OUTSIDE the worktree, or write it as .ts. `git ls-files` cannot see it, " +
        "which is how every tracked .py got here in the first place.",
    ],
    [
      "call sites naming a missing file",
      dangling(root),
      "A conversion deleted the file and left a caller pointing at it. Repoint the call at the .ts, and grep for the " +
        "stem: a converted script usually has more than one call site.",
    ],
    [
      "live ledger verify= gone missing",
      staleVerifies(root),
      "Rows that have NOT run yet carry a verify naming a script that does not exist. Repoint it with the manifest " +
        "CLI's edit-verify, in the SAME commit that converted the script.",
    ],
    [
      "callers running the WRONG runtime",
      runtimeMismatch(root),
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
    summary: "OK: no-python wall holds — no tracked .py, nothing that runs or installs python, no untracked .py",
  };
}

// ─── the write-time half ───────────────────────────────────────────────────────────────────────
//
// WHY A GATE LEG WAS NOT ENOUGH, measured rather than argued. On 2026-09-18 a webui commit
// (1b56f197) added a fresh `python3` subprocess to density.mjs and the branch went red — but not at
// write time, and not at commit time. It went red HOURS LATER, the next time somebody happened to
// run the wall. A rule that fails late is a rule the tree teaches against in the meantime, because
// the file sits there for hours being an example. The operator's ruling: "it should be a PreToolUse
// hook that refuses the write and returns with a message."
//
// WHAT IT REFUSES, and nothing more: any .py file written into the repo; a write whose TEXT runs or installs
// python (invokesPython, judged on what the call would put into the file); and a caller line running a file
// with the wrong runtime.

/** The caller surface whose invocations are literal command lines — the same one the wall's
 *  seventh census reads, so the two halves are the same rule and not two readings of it. */
const CALLER = /(?:\.sh|\.mjs|package\.json|^\.github\/workflows\/[^/]+\.ya?ml)$/;

type Event = { tool_name?: string; tool_input?: Record<string, unknown> };

/** The text this tool call would PUT INTO the file — never the file's current contents. An Edit
 *  is charged on `new_string` alone so that touching an unrelated line of a file that already
 *  mentions python is not refused; the gate leg owns the whole-file verdict. */
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
  if (SELF.has(rel)) return null;

  if (rel.endsWith(".py")) {
    return (
      `REFUSED — this repo has no Python; tooling is bun/TypeScript.\n\n` +
      `  ${rel} is a .py file.\n\n` +
      `Write it as .ts and run it with bun. If this is a throwaway, put it in a scratch directory outside the\n` +
      `worktree instead.`
    );
  }

  const text = proposedText(tool, input);
  const crossed = CALLER.test(rel) ? mismatchedRuntimes(text) : [];
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

  if (invokesPython(rel, text)) {
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
