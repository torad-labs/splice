// NEW: LAYOUT-01's closing instrument — is capabilities.tsv a COMPLETE account of the product tree?
//
// The census froze every tracked product/build/tool/doc file on 2026-09-22 and gave each a
// disposition. This checker holds it against the tree as it is now, with the denominator taken
// from `git ls-files`, never from the census itself: a check whose denominator is the list it
// checks cannot fail for what the list omits.
//
// SCOPE: every tracked path under a top-level DIRECTORY whose name does not start with a dot.
// Excluded by name: the dot roots (.dev campaign ledgers, .github workflows, .claude hook wiring)
// and root-level files (build entry points, licences, the lockfile). Neither is product layout,
// and neither moved in LAYOUT-01. A new product root is therefore in scope the day it appears.
//
// DISPOSITIONS, and what each claims about the tree:
//   retain    the source path is still tracked, where it was
//   relocate  the destination is tracked and the source is gone (the move happened)
//   created   the path is tracked and had no census row (a split, or a file added after the freeze)
//   retire    the source is gone and nothing replaces it
// `pending` is a failure: the migration is complete only when no row is left undecided.
//
// Usage: bun .dev/restructure/census.ts [--selftest]   (exit 0 complete, 1 findings, 2 misuse)
// --json prints the verdict as one JSON document, {"findings":[{"message","paths"}]}, where paths lists every path a finding
// is about. The pre-commit hook judges a commit by those paths, never by the sentence, because a path may hold a space or a colon.
//        bun .dev/restructure/census.ts [--root <dir>] [--rows <file>]
//        bun .dev/restructure/census.ts [--root <dir>] --rev <commit>
// --root and --rows let the pre-commit hook judge a commit's bytes: the checkout whose index is read (git's index
// environment carries through), and the capabilities rows to judge, which the hook takes from that index.
// --rev judges one commit: its tree is the denominator and its tree's capabilities.tsv the rows. The pre-push leg
// runs it with HEAD, so a push is judged by what it pushes, never by another seat's staged or uncommitted files.
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";

const DISPOSITIONS = new Set(["retain", "relocate", "created", "retire"]);

export interface Row {
  source: string;
  disposition: string;
  destination: string;
  reason: string;
}

export function inScope(path: string): boolean {
  return path.includes("/") && !path.startsWith(".");
}

/** One way the census and the tree disagree. `message` is for people. `paths` is what a judge matches: every path the finding
 *  is about, carried as data, because a path may hold a space or a colon and so cannot be recovered from the sentence. */
export interface Finding {
  readonly message: string;
  readonly paths: readonly string[];
}

/** Every way the census and the tree disagree, each naming the paths it is about. */
export function findings(rows: Row[], tracked: Set<string>): Finding[] {
  const out: Finding[] = [];
  const flag = (message: string, ...paths: string[]) => out.push({ message, paths });
  const claims = new Map<string, number>();
  const claim = (path: string) => claims.set(path, (claims.get(path) ?? 0) + 1);
  for (const row of rows) {
    const { source, disposition, destination } = row;
    if (!DISPOSITIONS.has(disposition)) {
      flag(`${disposition === "pending" ? "pending" : `unknown disposition '${disposition}'`}: ${source}`, source);
      continue;
    }
    if (row.reason.trim() === "") flag(`blank reason: ${source}`, source);
    if (disposition === "retain" || disposition === "created") {
      if (!tracked.has(source)) flag(`${disposition} but not tracked: ${source}`, source);
      claim(source);
    } else if (disposition === "relocate") {
      if (!tracked.has(destination)) flag(`relocated to an untracked destination: ${source} -> ${destination}`, source, destination);
      if (source !== destination && tracked.has(source)) flag(`relocated but the source is still tracked: ${source}`, source);
      claim(destination);
    } else if (tracked.has(source)) {
      flag(`retired but still tracked: ${source}`, source);
    }
  }
  for (const [path, count] of claims) {
    if (count > 1) flag(`claimed by ${count} rows: ${path}`, path);
    if (!inScope(path)) flag(`claimed outside the census scope: ${path}`, path);
  }
  for (const path of [...tracked].filter(inScope).sort()) {
    if (!claims.has(path)) flag(`unclaimed: ${path}`, path);
  }
  return out;
}

export function parse(text: string): Row[] {
  const [header, ...lines] = text.split("\n").filter((line) => line !== "");
  if (header !== "source\tdisposition\tdestination\treason") throw new Error(`unexpected header: ${header}`);
  return lines.map((line, index) => {
    const cells = line.split("\t");
    if (cells.length !== 4) throw new Error(`line ${index + 2}: ${cells.length} cells, expected 4`);
    const [source = "", disposition = "", destination = "", reason = ""] = cells;
    return { source, disposition, destination, reason };
  });
}

/** Red/green over synthetic trees: every finding class fires on its own defect, and a complete
 *  census passes. A checker never shown to fail proves nothing when it passes. */
function selftest(): number {
  const tracked = new Set(["app/Main.kt", "features/a/A.kt", "core/C.kt", "README.md", ".dev/x.ts"]);
  const complete: Row[] = [
    { source: "app/Main.kt", disposition: "retain", destination: "", reason: "composition" },
    { source: "app/A.kt", disposition: "relocate", destination: "features/a/A.kt", reason: "capability" },
    { source: "core/C.kt", disposition: "created", destination: "", reason: "added by abc" },
    { source: "daemon/B.kt", disposition: "retire", destination: "", reason: "module dissolved" },
  ];
  const cases: [string, Row[], Set<string>, string][] = [
    ["a missing row leaves its file unclaimed", complete.slice(1), tracked, "unclaimed: app/Main.kt"],
    ["pending fails", [...complete.slice(1), { ...complete[0]!, disposition: "pending" }], tracked, "pending: app/Main.kt"],
    ["a blank reason fails", [{ ...complete[0]!, reason: " " }, ...complete.slice(1)], tracked, "blank reason: app/Main.kt"],
    ["an unknown word fails", [{ ...complete[0]!, disposition: "keep" }, ...complete.slice(1)], tracked, "unknown disposition 'keep': app/Main.kt"],
    ["a move whose source remains fails", complete, new Set([...tracked, "app/A.kt"]), "relocated but the source is still tracked: app/A.kt"],
    ["a move to nowhere fails", complete, new Set([...tracked].filter((p) => p !== "features/a/A.kt")), "relocated to an untracked destination: app/A.kt -> features/a/A.kt"],
    ["a retired file still present fails", complete, new Set([...tracked, "daemon/B.kt"]), "retired but still tracked: daemon/B.kt"],
    ["a double claim fails", [...complete, { ...complete[2]! }], tracked, "claimed by 2 rows: core/C.kt"],
    ["a new product root is in scope", complete, new Set([...tracked, "plugins/P.kt"]), "unclaimed: plugins/P.kt"],
  ];
  let failed = 0;
  const clean = findings(complete, tracked);
  if (clean.length !== 0) {
    failed++;
    console.log(`FAIL a complete census passes: ${clean.map((finding) => finding.message).join("; ")}`);
  }
  for (const [name, rows, tree, expected] of cases) {
    const got = findings(rows, tree).map((finding) => finding.message);
    if (!got.includes(expected)) {
      failed++;
      console.log(`FAIL ${name}: expected "${expected}", got ${JSON.stringify(got)}`);
    }
  }
  // A name holding a space and a colon comes back whole, as data, whatever its sentence says.
  const spelled = "tools/gate/a b:c.ts";
  const odd = findings([], new Set([spelled]));
  if (odd.length !== 1 || odd[0]?.paths.join("|") !== spelled) {
    failed++;
    console.log(`FAIL a path with a space and a colon is carried whole: got ${JSON.stringify(odd)}`);
  }
  console.log(`census selftest: ${cases.length + 2 - failed}/${cases.length + 2} cases`);
  return failed === 0 ? 0 : 1;
}

const USAGE = "usage: bun .dev/restructure/census.ts [--selftest] | [--root <dir>] [--rows <file>] | [--root <dir>] --rev <commit>, each with [--json]";

/** What to judge. The checkout's index and rows (the default, and pre-commit's commit bytes), or one commit's tree and
 *  rows (`--rev`: a push judges its tip, so another seat's staged file or uncommitted row never blocks it). */
type Judged = { kind: "index"; root: string; rows: string } | { kind: "rev"; root: string; rev: string };

/** Each flag at most once. `--rev` takes no `--rows`: a commit's rows are the ones in its own tree. `--json` takes no value. */
function options(argv: string[]): { judged: Judged; json: boolean } | undefined {
  const given = new Map<string, string>();
  let json = false;
  for (let i = 0; i < argv.length; i++) {
    const flag = argv[i] ?? "";
    if (flag === "--json") {
      if (json) return undefined;
      json = true;
      continue;
    }
    const value = argv[++i];
    if (!["--root", "--rows", "--rev"].includes(flag) || value === undefined || given.has(flag)) return undefined;
    given.set(flag, value);
  }
  const root = given.get("--root") ?? join(dirname(import.meta.path), "..", "..");
  const rev = given.get("--rev");
  if (rev !== undefined) return given.has("--rows") ? undefined : { judged: { kind: "rev", root, rev }, json };
  const rows = given.get("--rows") ?? join(root, ".dev", "restructure", "capabilities.tsv");
  return { judged: { kind: "index", root, rows }, json };
}

const ROWS_IN_TREE = ".dev/restructure/capabilities.tsv";

function main(argv: string[]): number {
  if (argv.includes("--selftest")) return selftest();
  const parsed = options(argv);
  if (parsed === undefined) {
    console.error(USAGE);
    return 2;
  }
  const { judged: picked, json } = parsed;
  const { root } = picked;
  let tracked: Set<string>;
  let rowsText: string;
  if (picked.kind === "rev") {
    const tree = Bun.spawnSync(["git", "-C", root, "ls-tree", "-r", "--name-only", "-z", picked.rev], { stdout: "pipe", stderr: "pipe" });
    const blob = Bun.spawnSync(["git", "-C", root, "show", `${picked.rev}:${ROWS_IN_TREE}`], { stdout: "pipe", stderr: "pipe" });
    if (tree.exitCode !== 0 || blob.exitCode !== 0) {
      console.error(`git could not read ${picked.rev}: ${tree.stderr.toString()}${blob.stderr.toString()}`);
      return 2;
    }
    tracked = new Set(tree.stdout.toString().split("\0").filter((path) => path !== ""));
    rowsText = blob.stdout.toString("utf8");
  } else {
    const listed = Bun.spawnSync(["git", "-C", root, "ls-files", "-z"], { stdout: "pipe", stderr: "pipe" });
    if (listed.exitCode !== 0) {
      console.error(`git ls-files failed: ${listed.stderr.toString()}`);
      return 2;
    }
    tracked = new Set(listed.stdout.toString().split("\0").filter((path) => path !== ""));
    rowsText = readFileSync(picked.rows, "utf8");
  }
  const rows = parse(rowsText);
  const found = findings(rows, tracked);
  if (json) {
    // One document: a judge reads every finding's paths as data and never recovers them from a sentence.
    console.log(JSON.stringify({ findings: found }));
    return found.length === 0 ? 0 : 1;
  }
  for (const finding of found) console.log(finding.message);
  const counts = new Map<string, number>();
  for (const row of rows) counts.set(row.disposition, (counts.get(row.disposition) ?? 0) + 1);
  const scoped = [...tracked].filter(inScope).length;
  const summary = [...counts].map(([word, count]) => `${count} ${word}`).join(", ");
  console.log(`census: ${rows.length} rows (${summary}) over ${scoped} tracked product paths — ${found.length} finding(s)`);
  return found.length === 0 ? 0 : 1;
}

if (import.meta.main) process.exit(main(process.argv.slice(2)));
