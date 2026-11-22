// Personal-folder guard for tools/desktop-sandbox (docs/specs/desktop-full.md, outcome 8).
// Resolves symlinks along every existing ancestor before any directory is created so a sandbox root that points
// into the person's own Claude Desktop, Claude Code, splice, or keyring data is refused without writing a byte.

import { existsSync, realpathSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, isAbsolute, join, relative, resolve } from "node:path";

/** Resolve a target path through symlinks even when its leaf folders do not exist yet. */
export function canonicalPath(target: string): string {
  const abs = resolve(target);
  const tail: string[] = [];
  let cursor = abs;
  while (!existsSync(cursor)) {
    const parent = dirname(cursor);
    if (parent === cursor) break;
    tail.unshift(cursor.slice(parent.length).replace(/^\/+/, ""));
    cursor = parent;
  }
  const base = existsSync(cursor) ? realpathSync(cursor) : cursor;
  return tail.length ? join(base, ...tail) : base;
}

/** True when `candidate` is identical to or nested inside `dir`, or `dir` is inside `candidate`. */
function overlaps(candidate: string, dir: string): boolean {
  const rel1 = relative(dir, candidate);
  if (rel1 === "" || (!rel1.startsWith("..") && !isAbsolute(rel1))) return true;
  const rel2 = relative(candidate, dir);
  if (rel2 === "" || (!rel2.startsWith("..") && !isAbsolute(rel2))) return true;
  return false;
}

/** Folders owned by the person that the sandbox may never resolve into or enclose. */
export function protectedPersonalPaths(env: Record<string, string | undefined> = process.env): string[] {
  const homes = new Set<string>();
  if (env.HOME) homes.add(env.HOME);
  homes.add(homedir());

  const configs = new Set<string>();
  if (env.XDG_CONFIG_HOME) configs.add(env.XDG_CONFIG_HOME);
  for (const h of homes) configs.add(join(h, ".config"));

  const blocked = new Set<string>();
  for (const h of homes) {
    blocked.add(canonicalPath(join(h, ".claude")));
    blocked.add(canonicalPath(join(h, ".splice")));
    blocked.add(canonicalPath(join(h, ".local", "share", "keyrings")));
  }
  for (const c of configs) {
    blocked.add(canonicalPath(join(c, "Claude")));
    blocked.add(canonicalPath(join(c, "Claude-3p")));
    blocked.add(canonicalPath(join(c, "splice")));
  }
  if (env.CLAUDE_CONFIG_DIR) blocked.add(canonicalPath(env.CLAUDE_CONFIG_DIR));
  if (env.SPLICE_STATE_DIR) blocked.add(canonicalPath(env.SPLICE_STATE_DIR));
  if (env.SPLICE_CONFIG_DIR) blocked.add(canonicalPath(env.SPLICE_CONFIG_DIR));
  return [...blocked];
}

/**
 * Validate a requested sandbox root before creating any files.
 * Throws an Error mentioning "refused" if the resolved root overlaps any protected personal folder or $HOME itself.
 */
export function assertSafeSandboxRoot(root: string, env: Record<string, string | undefined> = process.env): string {
  if (!root || !root.trim()) {
    throw new Error("sandbox refused: --root must not be empty");
  }
  const resolved = canonicalPath(root);
  if (resolved === "/") {
    throw new Error("sandbox refused: root cannot be /");
  }
  const homes = new Set<string>([canonicalPath(homedir())]);
  if (env.HOME) homes.add(canonicalPath(env.HOME));
  for (const h of homes) {
    if (resolved === h) {
      throw new Error(`sandbox refused: root resolves to personal home ${h}`);
    }
  }
  for (const blocked of protectedPersonalPaths(env)) {
    if (overlaps(resolved, blocked)) {
      throw new Error(`sandbox refused: root ${resolved} overlaps protected personal path ${blocked}`);
    }
  }
  return resolved;
}
