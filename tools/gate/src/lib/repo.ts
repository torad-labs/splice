// Where the repository is, and where its Gradle build root is — both discovered, never hardcoded.
//
// checks/gradle-slot.sh:24-25 resolves both by position: ROOT is the script's parent and the build
// root is spelled out there. That spelling stopped being true when the build root moved to the
// repository root (restructure PR 2), and a lock file that silently changes path is two gradles in
// one project dir — the incident the slot exists to prevent. So the build root is the directory that
// OWNS settings.gradle.kts, found by looking.
import { existsSync, readdirSync, statSync } from "node:fs";
import { dirname, join, resolve } from "node:path";

export interface Layout {
  /** the repository working tree root (the directory holding `.git`) */
  readonly repoRoot: string;
  /** the directory holding the outermost `settings.gradle.kts` — since PR 2, `repoRoot` itself */
  readonly buildRoot: string;
}

/** Walk up from `from` until a `.git` entry appears. In a worktree `.git` is a FILE, not a dir. */
export function findRepoRoot(from: string): string {
  let dir = resolve(from);
  for (;;) {
    if (existsSync(join(dir, ".git"))) return dir;
    const up = dirname(dir);
    if (up === dir) throw new Error(`gate: no .git found above ${from} — cannot locate the repository root`);
    dir = up;
  }
}

const SKIP = new Set(["node_modules", ".git", "build", ".gradle", "dist", ".cache"]);

/**
 * The SHALLOWEST `settings.gradle.kts` at or under `repoRoot` (depth <= 2) is the build root:
 * `build-logic/settings.gradle.kts` is an INCLUDED build and must never win over the root's own
 * `settings.gradle.kts`. Two candidates at the same depth is an ambiguity we refuse to guess at —
 * it names both and stops.
 */
export function findBuildRoot(repoRoot: string, maxDepth = 2): string {
  for (let depth = 0; depth <= maxDepth; depth++) {
    const hits = dirsAtDepth(repoRoot, depth).filter((d) => existsSync(join(d, "settings.gradle.kts")));
    if (hits.length === 1) return hits[0]!;
    if (hits.length > 1) {
      throw new Error(
        `gate: ${hits.length} settings.gradle.kts at the same depth under ${repoRoot} — ` +
          `cannot choose a build root between ${hits.join(" and ")}`,
      );
    }
  }
  throw new Error(`gate: no settings.gradle.kts within ${maxDepth} levels of ${repoRoot}`);
}

function dirsAtDepth(root: string, depth: number): string[] {
  if (depth === 0) return [root];
  return dirsAtDepth(root, depth - 1).flatMap((dir) => {
    let entries: string[];
    try {
      entries = readdirSync(dir);
    } catch {
      return [];
    }
    return entries
      .filter((name) => !SKIP.has(name) && !name.startsWith("."))
      .map((name) => join(dir, name))
      .filter((p) => {
        try {
          return statSync(p).isDirectory();
        } catch {
          return false;
        }
      });
  });
}

/** Resolve both roots. `from` defaults to this file, so the answer does not depend on the cwd. */
export function layout(from: string = import.meta.dir): Layout {
  const repoRoot = findRepoRoot(from);
  return { repoRoot, buildRoot: findBuildRoot(repoRoot) };
}
