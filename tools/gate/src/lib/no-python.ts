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
 *   · A NEW .py fails. Any tracked Python file not in the allowlist is a hard error naming the
 *     file. This is the leg that stops the drift.
 *   · A STALE entry fails. An allowlist line whose file is gone or converted is a hard error, so
 *     the list can only shrink and never silently holds room for a file to come back into.
 *   · AN UNTRACKED .py fails, with no allowlist at all. Both legs above read `git ls-files` and
 *     are therefore blind to the scratch script that has not been added yet — which is the state
 *     every tracked .py passed through on its way in.
 *   · IT CANNOT BE SATISFIED BY WEAKENING. Adding to the allowlist to make the gate pass is the
 *     violation, not the remedy — the allowlist is a dated burn-down of what already existed.
 *
 * The denominator comes from `git ls-files`, never from the allowlist itself (campaign law 24): a
 * list checked against itself cannot fail for anything absent from it. Every census takes the
 * repository root explicitly so the test arms can grade fixture repositories in-process.
 */
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { isAbsolute, join, relative, resolve } from "node:path";

export const ALLOW = "tools/gate/config/python-burndown.json";

type PendingTool = { tool: string; row: string; reason: string; recorded: string; callers: string[] };
export type Burndown = { recorded: string; law: string; files: string[]; invokers?: string[]; pendingTools?: PendingTool[] };

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
 *  exist to TALK about Python: the wall, its command, its test arms and the burn-down list. Their
 *  prose necessarily contains the word, and counting them would make the wall permanently report
 *  itself. Nothing else is exempt — a file that merely explains a python command is drift and IS
 *  counted, because prose is what teaches the next session which language this repo writes
 *  tooling in. The test arms must FEED the guard the violating text, which is why they are here. */
export const SELF = new Set([
  ALLOW,
  "tools/gate/src/lib/no-python.ts",
  "tools/gate/src/commands/no-python.ts",
  "tools/gate/test/no-python.test.ts",
]);

/** Does this text RUN or NAME python?
 *
 *  The wall's own NAME is not a python reference, and on 2026-09-18 that distinction was the
 *  difference between a green gate and a red one: console/.impeccable/review/ink/sweep-d7.mjs is
 *  the PORT AWAY FROM PYTHON, and the only lowercase `python` anywhere in it is the phrase "the
 *  no-python rule". A hyphen is not a word character, so `\bpython\b` matched inside the rule's
 *  own name and charged the file as an invoker — the wall failing the act of COMPLYING with it.
 *
 *  THIS IS A NARROWING, SO IT IS MEASURED RATHER THAN ARGUED: across all 79 charged files,
 *  excluding the literal `no-python` dropped EXACTLY ONE, the false positive; the other 78 keep
 *  their charge. It cannot become a dodge either: a file that actually invokes `python3` still
 *  matches on that token no matter how often it also writes "no-python". The same narrowing is why
 *  this verb, this file and its config are named `no-python`: a verb named `python` would make
 *  every file that spells the command an invoker.
 *
 *  THE SAME NARROWING, A SECOND TIME, FOR THE SAME REASON (restructure PR 5, splice-lead's
 *  measurement). The Python-semantics compat layer the ported harnesses run on is
 *  `tools/e2e/src/compat/python-{http,json,values}.ts`: three TypeScript modules that exist so this
 *  repo does NOT shell into Python, named after the thing they replace. `\bpython\b` matched inside
 *  each module's own NAME and charged all sixteen files that import or copy one — the wall failing
 *  the act of complying with it, with the same useless remedy (rename the port away from Python
 *  after the port away from Python). Measured over the whole tree at PR 5's tip: stripping these
 *  three names drops EXACTLY the 16 importers and keeps every burn-down invoker charged. It cannot
 *  become a dodge: a file that actually runs `python3` still matches on that token however often
 *  it also names python-json. Red-green: test/no-python.test.ts, "a .ts that only IMPORTS a compat
 *  module" (green) beside "imports a compat module AND shells into python3" (red).
 *
 *  A THIRD TIME, 2026-09-22 (LAYOUT-01): this wall's own config is python-burndown.json, and the
 *  restructure census lists every source path, that one included. Measured over every tracked file:
 *  stripping the name drops EXACTLY ONE charge, the census (41 -> 40), and every invoker keeps its
 *  charge. Red-green: "a census that only NAMES the burn-down config" (green) beside "names the
 *  burn-down config AND shells into python3" (red). */
export function namesPython(text: string): boolean {
  const named = ["no-python", "python-http", "python-json", "python-values", "python-burndown"];
  return /\bpython3?\b/.test(named.reduce((t, name) => t.replaceAll(name, ""), text));
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
 *  the dangling census — tracked .sh, .mjs and package.json — because that is the surface whose
 *  invocations are literal command lines. */
function runtimeMismatch(root: string): string[] {
  const out: string[] = [];
  for (const caller of gitLs(root, "*.sh", "*.mjs", "package.json")) {
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

/** THE THIRD DISPOSITION: a file that names python ONLY because it calls a tool that is still
 *  Python and is owned by an open conversion row.
 *
 *  FORCED BY A REAL COLLISION, 2026-09-18: converting the 21 hook scripts to .ts made four of them
 *  new invokers purely because they named the ledger CLI while it was still Python. Adding four
 *  burn-down lines is refused by the growth census; rewording the prose to advertise a command that
 *  does not exist is the class of lie this wall was built to catch.
 *
 *  An entry excuses a caller only while ALL THREE hold, each checked against the tree: the named
 *  tool is still on the burn-down's `files` list; the tool still EXISTS; and stripping that tool's
 *  own invocations from the caller leaves NO python behind. An entry whose tool is gone, or was
 *  never debt, is STALE and reds the wall by name until it is deleted. */
export function pendingStrip(text: string, tool: string): string {
  return text
    .split("\n")
    .filter((line) => {
      if (line.includes(tool)) return false; // this line is about the pending tool
      if (!namesPython(line)) return true; // nothing to excuse
      // An interpreter token with no .py path of its own is the spawn-through-a-variable form
      // (`spawnSync("python3", [manifest, "laws"])`). Naming ANY OTHER .py keeps the charge.
      return /[A-Za-z0-9_./$-]+\.py\b/.test(line);
    })
    .join("\n");
}

/** Entries that no longer describe the tree. Graded from the SOURCE — the tool's existence and
 *  the burn-down's own files list — never from the entry's say-so. */
function stalePending(root: string, list: Burndown): string[] {
  const debt = new Set(list.files);
  const out: string[] = [];
  for (const p of list.pendingTools ?? []) {
    if (!existsSync(at(root, p.tool))) {
      out.push(`${p.tool} (${p.row}): the tool is GONE, so every exclusion this entry granted has expired — delete the entry`);
      continue;
    }
    if (!debt.has(p.tool)) {
      out.push(`${p.tool} (${p.row}): not on the burn-down's files list, so this entry excuses calls to something the wall never tracked as debt`);
      continue;
    }
    for (const f of p.callers) {
      if (!existsSync(at(root, f))) {
        out.push(`${p.tool} (${p.row}): names caller ${f}, which does not exist — an exclusion for a file that is gone`);
      } else if (!namesPython(readFileSync(at(root, f), "utf8"))) {
        out.push(`${p.tool} (${p.row}): names caller ${f}, which no longer mentions python at all — drop it from callers`);
      }
    }
  }
  return out.sort();
}

const livePending = (root: string, list: Burndown) =>
  (list.pendingTools ?? []).filter((p) => existsSync(at(root, p.tool)) && list.files.includes(p.tool));

/** THE SECOND CENSUS, and the wall was a lie without it. Counting files named `.py` is not
 *  counting PYTHON: measured 2026-09-18, 29 tracked .sh files invoked python3 167 times while the
 *  first census read a triumphant 90. A mention counts — a README saying `python3 checks/foo.py`
 *  is a live instruction to the next session. `.py` files are excluded only because the first
 *  census already owns them. */
function invokers(root: string, list: Burndown): string[] {
  const live = livePending(root, list);
  return gitLs(root)
    .filter((f) => !f.endsWith(".py") && !SELF.has(f))
    .filter((f) => {
      try {
        const text = readFileSync(at(root, f), "utf8");
        if (!namesPython(text)) return false;
        for (const p of live) {
          if (!p.callers.includes(f)) continue; // an entry excuses only the files it NAMES
          if (!namesPython(pendingStrip(text, p.tool))) return false;
        }
        return true;
      } catch {
        return false; // a binary or unreadable blob invokes nothing
      }
    })
    .sort();
}

/** Every file an entry currently excuses, printed on every run so the exclusion is never silent. */
function excused(root: string, list: Burndown): string[] {
  const out: string[] = [];
  for (const p of livePending(root, list)) {
    for (const f of p.callers) if (existsSync(at(root, f))) out.push(`${f} -> ${p.tool} (${p.row})`);
  }
  return out.sort();
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
const INVOCATION = /(?:python3|bun)\s+([A-Za-z0-9_./-]+\.(?:py|ts))/g;

function dangling(root: string): string[] {
  const out = new Set<string>();
  for (const caller of gitLs(root, "*.sh", "*.mjs", "package.json")) {
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

/** The burn-down's history, oldest first, with the path the list had at each revision.
 *
 *  `--follow`, because PR 5 moved the list from checks/config/ to tools/gate/config/ and a plain
 *  `git log -- <path>` would start history at the move — making the moved list its own birth and
 *  the ratchet below a comparison of the list against itself. `--name-only` carries the path each
 *  revision knew the file by, which is what `git show <rev>:<path>` needs. Measured 2026-09-21:
 *  `--follow` with `--reverse` stops at the rename, so the walk is newest-first and reversed here. */
function listHistory(root: string): { rev: string; path: string }[] {
  const log = spawnSync("git", ["log", "--follow", "--format=%H", "--name-only", "--", ALLOW], { cwd: root, encoding: "utf8" });
  if (log.status !== 0) return [];
  const history: { rev: string; path: string }[] = [];
  let rev: string | undefined;
  for (const raw of log.stdout.split("\n")) {
    const line = raw.trim();
    if (!line) continue;
    if (/^[0-9a-f]{40}$/.test(line)) {
      rev = line;
      continue;
    }
    if (rev) {
      history.push({ rev, path: line });
      rev = undefined;
    }
  }
  return history.reverse();
}

/** THE SIXTH CENSUS: the burn-down may only SHRINK, and until now that was only prose.
 *
 *  FOUND BY MUTATION-TESTING THIS WALL END TO END on 2026-09-18: write a new .py (RED, untracked
 *  census); `git add` it (RED, NEW PYTHON census); add its path to the burn-down (GREEN). Step 3
 *  is the CHEAPEST of the three moves, so it is the one a session under pressure reaches for.
 *
 *  THE DENOMINATOR IS GIT, NOT THE FILE: the baseline is the list AS FIRST COMMITTED, so the
 *  comparison is against a record no working copy can edit. Current must be a SUBSET of birth, in
 *  both `files` and `invokers`. EACH ARRAY GETS ITS OWN BIRTH — `invokers` was added days after
 *  `files`, and a ratchet whose baseline predates the thing it measures reports the measurement
 *  itself as the violation.
 *
 *  ONE EXCEPTION, AND GIT IS ITS WITNESS: an `invokers` entry whose file git records as RENAMED
 *  since the birth revision is the same file at a new path. `files` gets no such exception: a
 *  renamed .py is Python reorganised rather than converted. The rename check runs against the
 *  WORKING TREE, not HEAD: every other census measures the tree, and a checker whose verdict
 *  depends on whether the work is committed yet is not measuring the work (restructure PR 3
 *  charged two moved invokers as growth in the commit that moved them). */
function burndownGrowth(root: string, now: Burndown): string[] {
  const history = listHistory(root);
  if (history.length === 0) return []; // no history here (a fresh fixture tree): the censuses above still gate.
  const out: string[] = [];
  for (const key of ["files", "invokers"] as const) {
    const today = now[key] ?? [];
    if (!today.length) continue;
    let base: string[] | undefined;
    let baseRev = "";
    for (const { rev, path } of history) {
      const shown = spawnSync("git", ["show", `${rev}:${path}`], { cwd: root, encoding: "utf8" });
      if (shown.status !== 0) continue;
      let v: Burndown;
      try {
        v = JSON.parse(shown.stdout) as Burndown;
      } catch {
        continue; // a revision nobody can parse cannot be a baseline; keep walking forward.
      }
      const arr = v[key];
      if (Array.isArray(arr) && arr.length) {
        base = arr;
        baseRev = rev;
        break;
      }
    }
    if (!base) continue; // this key has never been committed with content: nothing to ratchet against yet.
    const was = new Set(base);
    const before = key === "invokers" ? renamedSince(root, baseRev) : new Map<string, string>();
    for (const entry of today) {
      const known = was.has(entry) || was.has(before.get(entry) ?? "");
      if (!known) out.push(`${key}: ${entry} (not in the list as first recorded at ${baseRev.slice(0, 8)})`);
    }
  }
  return out.sort();
}

/** today's path -> the path git says it was renamed FROM, for every rename between [rev] and the
 *  WORKING TREE. */
function renamedSince(root: string, rev: string): Map<string, string> {
  const r = spawnSync("git", ["diff", "--name-status", "-M", "--diff-filter=R", rev], { cwd: root, encoding: "utf8" });
  const out = new Map<string, string>();
  if (r.status !== 0) return out;
  for (const line of r.stdout.split("\n")) {
    const [status, from, to] = line.split("\t");
    if (status?.startsWith("R") && from && to) out.set(to, from);
  }
  return out;
}

export function burndown(root: string): Burndown {
  const path = at(root, ALLOW);
  if (!existsSync(path)) throw new WallError(`no-python: ${ALLOW} missing — the wall has no burn-down list to grade against`);
  try {
    return JSON.parse(readFileSync(path, "utf8")) as Burndown;
  } catch (e) {
    throw new WallError(`no-python: ${ALLOW} is not valid JSON (${e}) — a list nobody can parse grades nothing`);
  }
}

/** One census graded against its own list, both directions. Returns the problems. */
function grade(label: string, measured: string[], allowed: string[], newHelp: string): string[] {
  const set = new Set(allowed);
  const added = measured.filter((f) => !set.has(f));
  const stale = allowed.filter((f) => !measured.includes(f)).sort();
  const out: string[] = [];
  if (added.length) {
    out.push(`NEW PYTHON (${label}): ${added.length} file(s) not in the burn-down list. ${newHelp}\n    ` + added.join("\n    "));
  }
  if (stale.length) {
    out.push(
      `STALE (${label}): ${stale.length} burn-down entry(ies) name a file that no longer offends — gone, ` +
        `or already converted. Remove the line — a list held above the measured surface is unearned room ` +
        `for Python to come back into:\n    ` + stale.join("\n    "),
    );
  }
  return out;
}

export interface WallReport {
  /** the census table, printed on every run */
  readonly lines: readonly string[];
  readonly problems: readonly string[];
  /** the closing line for a clean run */
  readonly summary: string;
}

/** The whole wall over `root`. Throws WallError for a census that could not run. */
export function wall(root: string): WallReport {
  const measured = tracked(root);
  const list = burndown(root);
  const problems: string[] = [];

  const runners = invokers(root, list);
  const expired = stalePending(root, list);
  const excusedNow = excused(root, list);
  const allowedInvokers = list.invokers ?? [];
  const scratch = untracked(root);
  const broken = dangling(root);
  const willRun = staleVerifies(root);
  const grown = burndownGrowth(root, list);
  const mismatched = runtimeMismatch(root);

  const n = (v: number) => String(v).padStart(4);
  const lines = [
    `NO-PYTHON WALL — burn-down recorded ${list.recorded || "(none)"}`,
    `  tracked .py files                  measured ${n(measured.length)}   allowed ${n(list.files.length)}   [GATED]`,
    `  files that RUN or name python      measured ${n(runners.length)}   allowed ${n(allowedInvokers.length)}   [GATED]`,
    `  UNTRACKED .py in the worktree      measured ${n(scratch.length)}   allowed ${n(0)}   [GATED]`,
    `  call sites naming a missing file   measured ${n(broken.length)}   allowed ${n(0)}   [GATED]`,
    `  live ledger verify= gone missing   measured ${n(willRun.length)}   allowed ${n(0)}   [GATED]`,
    `  burn-down lines ADDED since birth  measured ${n(grown.length)}   allowed ${n(0)}   [GATED]`,
    `  callers running the WRONG runtime  measured ${n(mismatched.length)}   allowed ${n(0)}   [GATED]`,
    `  EXPIRED pending-tool exclusions    measured ${n(expired.length)}   allowed ${n(0)}   [GATED]`,
  ];
  if (excusedNow.length) {
    // Printed every run, never silent: an exclusion nobody reads is an allowlist.
    lines.push(`  excused while their tool is Python  ${excusedNow.length}`);
    for (const e of excusedNow) lines.push(`      ${e}`);
  }

  if (expired.length) {
    problems.push(
      `PENDING-TOOL EXCLUSION OUTLIVED ITS REASON: ${expired.length} entry(ies) in ${ALLOW} excuse callers of a ` +
        `tool that is no longer Python debt. An exclusion is only honest while the thing it points at is still ` +
        `there — the moment the owning row lands, every caller it excused must be charged again, and the entry ` +
        `has to go in the same commit that converted the tool:\n    ` + expired.join("\n    "),
    );
  }
  if (mismatched.length) {
    problems.push(
      `WRONG RUNTIME: ${mismatched.length} live call site(s) run a file with the interpreter for the other ` +
        `language. This is what a half-finished conversion looks like — the filename was updated and the ` +
        `interpreter was not — and it survives every other census on this wall, because the path resolves and ` +
        `the file exists. It fails at run time with a syntax error, which reads like a broken script rather ` +
        `than a broken call. Fix the interpreter, not the filename:\n    ` + mismatched.join("\n    "),
    );
  }
  if (grown.length) {
    problems.push(
      `THE BURN-DOWN GREW: ${grown.length} entry(ies) are in ${ALLOW} that were not there when it was first ` +
        `recorded. This list may only SHRINK. Adding a line is how a new .py gets past every other census on ` +
        `this wall — it is the cheapest way to a green gate and therefore the one that gets taken — so it is ` +
        `graded against the list AS FIRST COMMITTED in git, which no working copy can edit. Delete the line and ` +
        `convert the file to .ts. Moving a .py to a new path is refused here too: that is Python being ` +
        `reorganised rather than converted:\n    ` + grown.join("\n    "),
    );
  }
  if (willRun.length) {
    problems.push(
      `LEDGER VERIFY NAMES A MISSING FILE: ${willRun.length} row(s) that have NOT run yet carry a verify command ` +
        `naming a script that does not exist. Unlike a done/verified row — whose verify is a record of what ran, ` +
        `and is deliberately not graded here — these are instructions, and each one will fail the moment someone ` +
        `runs the row. Repoint it with the manifest CLI's edit-verify, in the SAME commit that converted the ` +
        `script, because the gap between the two is where this defect lives:\n    ` + willRun.join("\n    "),
    );
  }
  if (broken.length) {
    problems.push(
      `DANGLING INVOCATION: ${broken.length} call site(s) name a script that does not exist. A conversion ` +
        `deleted the file and left a caller pointing at it, so the leg fails at run time with "No such file or ` +
        `directory" while every census above reports a clean burn-down. Repoint the call at the .ts — and grep ` +
        `for the stem before you report, because a converted script usually has more than one call site and the ` +
        `one you remember is not the one that breaks:\n    ` + broken.join("\n    "),
    );
  }
  if (scratch.length) {
    problems.push(
      `UNTRACKED PYTHON: ${scratch.length} file(s) written into the worktree but never added. The two censuses ` +
        `above read \`git ls-files\` and cannot see these, which is how every tracked .py in the burn-down got ` +
        `here in the first place. Move it to a scratch directory OUTSIDE the worktree — a throwaway does not ` +
        `belong in the tree whatever its language — or write it as .ts if it is going to be kept. Adding it to ` +
        `${ALLOW} is not available: that list is a dated record of what already existed, and this file is newer ` +
        `than the list by definition:\n    ` + scratch.join("\n    "),
    );
  }
  problems.push(
    ...grade(
      "file",
      measured,
      list.files,
      `This repo is bun/TypeScript; write it as .ts and run it with bun. Do NOT add the file to ${ALLOW} — that ` +
        `list is a dated record of what already existed, and growing it is the violation this wall exists to catch.`,
    ),
    ...grade(
      "invocation",
      runners,
      allowedInvokers,
      `A file that shells into python3, or documents a python3 command, is Python this repo still runs and still ` +
        `teaches. Convert the call to bun; if it is prose, update the prose. Do NOT add a line to ${ALLOW}.`,
    ),
  );

  return {
    lines,
    problems,
    summary:
      `OK: no-python wall holds — ${measured.length} tracked .py file(s) and ${runners.length} file(s) that run or ` +
      `name python, both exactly the ${list.recorded} burn-down, nothing listed has already been converted, and no ` +
      `untracked .py is sitting in the worktree waiting to be added`,
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
// WHAT IT REFUSES, and nothing more: a NEW .py file (one not already carried as debt); a write whose
// TEXT runs or names python, into a file not already listed as an invoker; and, before the invoker
// exemption, a caller line running a file with the wrong runtime. An EXISTING .py stays writable:
// the burn-down rows are conversions in flight, and a guard that blocked edits to the debt would
// block the work that removes it.

/** The caller surface whose invocations are literal command lines — the same one the wall's
 *  seventh census reads, so the two halves are the same rule and not two readings of it. */
const CALLER = /(?:\.sh|\.mjs|package\.json)$/;

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

/** The block reason for a PreToolUse event, or null to allow the write. Throws when the list
 *  cannot be read — the command turns that into a fail-OPEN allow with a message. */
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

  const list = burndown(root);
  const files = new Set(list.files ?? []);
  const invokerSet = new Set(list.invokers ?? []);

  if (rel.endsWith(".py")) {
    if (files.has(rel)) return null; // existing debt: conversions have to be able to edit it
    return (
      `REFUSED — this repo has no Python; tooling is bun/TypeScript.\n\n` +
      `  ${rel} is a NEW .py file.\n\n` +
      `Write it as .ts and run it with bun. Adding it to ${ALLOW} is NOT available: that list is a\n` +
      `dated burn-down of what already existed, it may only shrink, and \`gate no-python\` grades\n` +
      `it against its own first commit in git — so a line added there fails the build by name.\n` +
      `If this is a throwaway, put it in a scratch directory outside the worktree instead.`
    );
  }

  // BEFORE the invoker exemption below, because the file this actually happens to is a LISTED
  // invoker: being on the invokers list earns an exemption from naming python; it never earns an
  // exemption from naming it in front of a .ts.
  const crossed = CALLER.test(rel) ? mismatchedRuntimes(proposedText(tool, input)) : [];
  if (crossed.length) {
    return (
      `REFUSED — wrong runtime for the file's extension.\n\n` +
      `  ${rel}\n    ` +
      crossed.join("\n    ") +
      `\n\nThis is a half-finished conversion: the filename moved and the interpreter did not. It would\n` +
      `not fail here — the path resolves and the file exists, so every census on the no-python wall\n` +
      `stays green — it would fail later at run time with a syntax error that reads like a broken\n` +
      `script rather than a broken call. Change the interpreter to match the extension.`
    );
  }

  // The same third disposition the wall's invoker census applies, or the two halves disagree about
  // the same file. Only entries whose tool still exists AND is still burn-down debt strip anything.
  let charged = proposedText(tool, input);
  for (const p of list.pendingTools ?? []) {
    if (!existsSync(at(root, p.tool)) || !(list.files ?? []).includes(p.tool)) continue;
    if (p.callers.includes(rel)) charged = pendingStrip(charged, p.tool);
  }
  if (namesPython(charged) && !invokerSet.has(rel)) {
    return (
      `REFUSED — this repo has no Python; tooling is bun/TypeScript.\n\n` +
      `  ${rel} is not a listed invoker, and this write makes it run or name python.\n\n` +
      `If it SHELLS OUT: do the work in bun instead. The last file to do this decoded a PNG through\n` +
      `a subprocess; zlib and forty lines of filter cases replaced it, byte-identical over seven\n` +
      `frames. If it is PROSE — a comment, a README, a command in a docstring — reword it. A\n` +
      `sentence naming the interpreter is what teaches the next session which language this repo\n` +
      `writes tooling in, and that is how every file on the burn-down got there.\n` +
      `Adding a line to ${ALLOW} is not the remedy; it is the violation the wall exists to catch.`
    );
  }
  return null;
}
