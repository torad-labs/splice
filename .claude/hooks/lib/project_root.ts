/** Where the repository is, for the hook modules: CLAUDE_PROJECT_DIR first (validated), then a walk
 *  up from each start to the directory holding sgconfig.yml + .claude/.
 *
 *  This was the surviving export of lib/astgrep_gate.ts (campaign kotlin-hardening D2, ported from
 *  proof-main's orchestrator/_lib.py). Its other exports — a second proposed-content mirror scan,
 *  GATE_LANG_EXTS, targetFilePath, proposedFileContent, astgrepScanProposed — had no consumer once
 *  the write-time wall lived in one place (tools/gate/src/lib/hook.ts, PR 5), and a second
 *  implementation of a wall is the drift the same-checker-twice rule exists to prevent. */
import { existsSync, statSync } from "node:fs";
import { dirname, join, resolve as pathResolve } from "node:path";

export function findProjectRoot(...starts: (string | null | undefined)[]): string | null {
  const candidates: (string | null | undefined)[] = [process.env.CLAUDE_PROJECT_DIR, ...starts];
  for (const start of candidates) {
    if (!start) continue;
    let p: string;
    try {
      p = pathResolve(start);
    } catch {
      continue;
    }
    try {
      if (statSync(p).isFile()) p = dirname(p);
    } catch {
      // a path that does not exist is still a legal walk start
    }
    for (let cur = p; ; cur = dirname(cur)) {
      // splice adaptation 2026-07-16: root marker is sgconfig.yml
      // (it sits at the repository root, where ast-grep resolves every glob from)
      if (existsSync(join(cur, "sgconfig.yml")) && statSync(join(cur, ".claude")).isDirectory()) {
        return cur;
      }
      const parent = dirname(cur);
      if (parent === cur) break;
    }
  }
  return null;
}
