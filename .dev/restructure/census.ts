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

/** Every way the census and the tree disagree, each naming the path it is about. */
export function findings(rows: Row[], tracked: Set<string>): string[] {
  const out: string[] = [];
  const claims = new Map<string, number>();
  const claim = (path: string) => claims.set(path, (claims.get(path) ?? 0) + 1);
  for (const row of rows) {
    const { source, disposition, destination } = row;
    if (!DISPOSITIONS.has(disposition)) {
      out.push(`${disposition === "pending" ? "pending" : `unknown disposition '${disposition}'`}: ${source}`);
      continue;
    }
    if (row.reason.trim() === "") out.push(`blank reason: ${source}`);
    if (disposition === "retain" || disposition === "created") {
      if (!tracked.has(source)) out.push(`${disposition} but not tracked: ${source}`);
      claim(source);
    } else if (disposition === "relocate") {
      if (!tracked.has(destination)) out.push(`relocated to an untracked destination: ${source} -> ${destination}`);
      if (source !== destination && tracked.has(source)) out.push(`relocated but the source is still tracked: ${source}`);
      claim(destination);
    } else if (tracked.has(source)) {
      out.push(`retired but still tracked: ${source}`);
    }
  }
  for (const [path, count] of claims) {
    if (count > 1) out.push(`claimed by ${count} rows: ${path}`);
    if (!inScope(path)) out.push(`claimed outside the census scope: ${path}`);
  }
  for (const path of [...tracked].filter(inScope).sort()) {
    if (!claims.has(path)) out.push(`unclaimed: ${path}`);
  }
  return out;
}

export function parse(text: string): Row[] {
  const [header, ...lines] = text.split("\n").filter((line) => line !== "");
  if (header !== "source\tsha256\tdisposition\tdestination\treason") throw new Error(`unexpected header: ${header}`);
  return lines.map((line, index) => {
    const cells = line.split("\t");
    if (cells.length !== 5) throw new Error(`line ${index + 2}: ${cells.length} cells, expected 5`);
    const [source = "", , disposition = "", destination = "", reason = ""] = cells;
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
    console.log(`FAIL a complete census passes: ${clean.join("; ")}`);
  }
  for (const [name, rows, tree, expected] of cases) {
    const got = findings(rows, tree);
    if (!got.includes(expected)) {
      failed++;
      console.log(`FAIL ${name}: expected "${expected}", got ${JSON.stringify(got)}`);
    }
  }
  console.log(`census selftest: ${cases.length + 1 - failed}/${cases.length + 1} cases`);
  return failed === 0 ? 0 : 1;
}

function main(argv: string[]): number {
  if (argv.includes("--selftest")) return selftest();
  if (argv.length > 0) {
    console.error("usage: bun .dev/restructure/census.ts [--selftest]");
    return 2;
  }
  const root = join(dirname(import.meta.path), "..", "..");
  const listed = Bun.spawnSync(["git", "-C", root, "ls-files", "-z"], { stdout: "pipe", stderr: "pipe" });
  if (listed.exitCode !== 0) {
    console.error(`git ls-files failed: ${listed.stderr.toString()}`);
    return 2;
  }
  const tracked = new Set(listed.stdout.toString().split("\0").filter((path) => path !== ""));
  const rows = parse(readFileSync(join(root, ".dev", "restructure", "capabilities.tsv"), "utf8"));
  const found = findings(rows, tracked);
  for (const line of found) console.log(line);
  const counts = new Map<string, number>();
  for (const row of rows) counts.set(row.disposition, (counts.get(row.disposition) ?? 0) + 1);
  const scoped = [...tracked].filter(inScope).length;
  const summary = [...counts].map(([word, count]) => `${count} ${word}`).join(", ");
  console.log(`census: ${rows.length} rows (${summary}) over ${scoped} tracked product paths — ${found.length} finding(s)`);
  return found.length === 0 ? 0 : 1;
}

if (import.meta.main) process.exit(main(process.argv.slice(2)));
