#!/usr/bin/env bun
/**
 * V4-30 — the conventional-type list lives in checks/pr-title.sh, once.
 *
 * THE CLASS. A second copy of the conventional-commit type vocabulary drifts.
 * This repo shipped .github/workflows/pr-title.yml allowing two types the org
 * gate rejects. The workflow is deleted; the org gate is the authority;
 * checks/pr-title.sh mirrors it. Restating the list in prose is how a stale
 * copy outlives that deletion and how a contributor titles the 0.4.0 PR with
 * a type that cannot merge.
 *
 * SCOPE. Every text file in the tree except checks/pr-title.sh, which is the
 * single allowed copy. Docs, templates, agents files, checkers, tests: if it
 * is in the tree and is text, it is in scope.
 *
 * DENOMINATOR. Files are enumerated by git ls-files from the check root
 * (tracked content plus the index, so a staged new file is in). The file
 * list is not an allowlist this file types and is not the working-tree
 * walk: untracked scratch, editor backups, and gitignored sidecars cannot
 * red the gate. A second copy has to be tracked to reach main. When there
 * is no git repo the checker falls back to a tree walk so hermetic
 * selftests still run. The type vocabulary itself is parsed from the
 * TYPES assignment in checks/pr-title.sh; this checker does not restate it.
 *
 * DISPOSITION. A file either contains a run of three or more conventional
 * types in sequence, in which case it FAILs by name with the remedy, or it
 * does not. Absence is not a disposition. There is no exemption table; a
 * second copy that happens to be correct is still the divergence mechanism.
 *
 * NOT CAUGHT, and why.
 *
 *   Two-type phrases (feat vs fix). The class is a restated vocabulary, not
 *   a comparison. What would catch a two-type copy that later grows a third
 *   type: this wall, on the third type.
 *
 *   Types that are not in the org list (release, codex, harden, verify) on
 *   their own. Those are not conventional types under the parsed TYPES
 *   assignment. A list of them becomes this wall's class the moment three
 *   org types sit in the same run. What would catch a docs line that names
 *   only the two rejected types: a wall on those two strings as a pair.
 *
 *   git log history. Commit subjects are not a restated list. What would
 *   catch inferring the convention from history: checks/pr-title.sh itself,
 *   which already warns not to.
 *
 *   Campaign ledgers under .dev/campaigns. They quote the defect being
 *   fixed. They are not a title vocabulary a contributor reads. What would
 *   catch a CONTRIBUTING copy: this wall, on that file.
 *
 *   Recorded captures under .dev/research. Mutating a capture to please a
 *   docs wall would falsify evidence. What would catch a guide that embeds
 *   the same list: this wall, on that guide.
 *
 * Usage:
 *     bun checks/config/one-conventional-type-list.ts check <root>
 *
 * THERE IS NO --selftest FLAG HERE, unlike its siblings in checks/config: this checker's oracle is
 * checks/one-conventional-type-list-selftest.sh, which drives `check` against fixture trees. The
 * usage line above is the whole interface, and a bare or malformed invocation is exit 2 with that
 * line on stderr — there is no default mode to mis-invoke.
 */
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative, resolve } from "node:path";

const SOURCE = "checks/pr-title.sh";
const TYPES_LINE = /^TYPES='([^']+)'/m;
const SKIP_DIRS = new Set([".git", "node_modules", "build", ".gradle", "dist", "out", "__pycache__", ".venv"]);
const SKIP_PREFIXES = [".dev/campaigns/", ".dev/research/"];
const SKIP_SUFFIXES = new Set([
  ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".jar", ".class", ".so", ".dylib",
  ".zip", ".gz", ".pdf", ".woff", ".woff2", ".ttf", ".eot", ".wasm",
]);

function refuse(message: string): never {
  process.stderr.write(message + "\n");
  process.exit(1);
}

function parseTypes(root: string): string[] {
  const path = join(root, SOURCE);
  if (!existsSync(path) || !statSync(path).isFile()) {
    refuse(`${SOURCE} missing — refusing to pass vacuously`);
  }
  const match = TYPES_LINE.exec(readFileSync(path, "utf8"));
  if (match === null || !match[1]) refuse(`${SOURCE} has no TYPES assignment — refusing to pass vacuously`);
  const types = match[1].split("|").filter((part) => part !== "");
  if (types.length < 3) refuse(`${SOURCE} TYPES has fewer than 3 entries — refusing to pass vacuously`);
  return types;
}

const escapeRe = (s: string): string => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

function runPattern(types: string[]): RegExp {
  const alt = types.map(escapeRe).join("|");
  return new RegExp(`(?<![A-Za-z])(?:${alt})(?:[\\s·,|/]+(?:${alt})){2,}(?![A-Za-z])`, "m");
}

function gitListed(root: string): string[] | null {
  const out = spawnSync("git", ["-C", root, "ls-files", "-z"], { stdio: ["ignore", "pipe", "ignore"] });
  if (out.error !== undefined || out.status !== 0) return null;
  const names = out.stdout.toString("utf8").split("\0").filter((n) => n !== "");
  return names.length > 0 ? names : null;
}

function accept(relPath: string, path: string): boolean {
  if (!existsSync(path) || !statSync(path).isFile()) return false;
  if (relPath.split("/").some((part) => SKIP_DIRS.has(part))) return false;
  const dot = relPath.lastIndexOf(".");
  const suffix = dot >= 0 ? relPath.slice(dot).toLowerCase() : "";
  if (SKIP_SUFFIXES.has(suffix)) return false;
  if (SKIP_PREFIXES.some((prefix) => relPath.startsWith(prefix))) return false;
  return true;
}

function iterTextFiles(root: string): string[] {
  const listed = gitListed(root);
  const files: string[] = [];
  if (listed === null) {
    const walk = (dir: string): void => {
      for (const entry of readdirSync(dir, { withFileTypes: true })) {
        const full = join(dir, entry.name);
        if (entry.isDirectory()) walk(full);
        else if (entry.isFile()) {
          const relPath = relative(root, full).split("\\").join("/");
          if (accept(relPath, full)) files.push(full);
        }
      }
    };
    walk(root);
    return files;
  }
  for (const relPath of listed) {
    const path = join(root, relPath);
    if (accept(relPath, path)) files.push(path);
  }
  return files;
}

const rel = (root: string, path: string): string => relative(root, path).split("\\").join("/");

/** Strip backticks so a middot-separated fenced list is one run. */
function hitsIn(text: string, pattern: RegExp): number[] {
  const stripped = text.split("`").join(" ");
  const out: number[] = [];
  // A FRESH regex per file: a `g` regex carries lastIndex between calls, and reusing one here
  // would silently skip the first match of every second file.
  const scan = new RegExp(pattern.source, pattern.flags.includes("g") ? pattern.flags : pattern.flags + "g");
  let match = scan.exec(stripped);
  while (match !== null) {
    out.push(match.index);
    match = scan.exec(stripped);
  }
  return out;
}

function check(root: string): number {
  const types = parseTypes(root);
  const pattern = runPattern(types);
  const source = resolve(root, SOURCE);
  let scanned = 0;
  const failures: string[] = [];
  for (const path of iterTextFiles(root)) {
    if (resolve(path) === source) continue;
    let text: string;
    try {
      text = readFileSync(path, "utf8");
    } catch {
      continue;
    }
    scanned += 1;
    if (hitsIn(text, pattern).length > 0) {
      const name = rel(root, path);
      failures.push(
        `${name} restates 3+ conventional types; the list lives once in ${SOURCE}. ` +
          `Remedy: delete the copy and point readers at bash ${SOURCE} ` +
          `"feat(scope): subject"`,
      );
    }
  }
  if (scanned === 0) {
    process.stderr.write("one-conventional-type-list: scanned 0 files — refusing to pass vacuously\n");
    return 1;
  }
  if (failures.length > 0) {
    process.stdout.write("ONE CONVENTIONAL TYPE LIST RED — a second copy is the divergence mechanism:\n");
    for (const failure of failures) process.stdout.write("  " + failure + "\n");
    return 1;
  }
  process.stdout.write(
    `ONE CONVENTIONAL TYPE LIST GREEN: ${scanned} files, no restated vocabulary; ` +
      `the list lives once in ${SOURCE}\n`,
  );
  return 0;
}

function main(argv: string[]): number {
  if (argv.length !== 2 || argv[0] !== "check") {
    process.stderr.write("usage: one-conventional-type-list.ts check <root>\n");
    return 2;
  }
  return check(resolve(argv[1]));
}

process.exit(main(process.argv.slice(2)));
