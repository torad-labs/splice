// `gate audit` — `bun audit --audit-level=critical`, three attempts, one minute each
// (checks/oss/verify-OSS-I.sh's first leg until PR 6). The retry and its reasons live in
// src/lib/audit.ts; this is the boundary.
import { runAudit } from "../lib/audit.ts";
import { layout } from "../lib/repo.ts";

export const usage = "audit                                bun audit --audit-level=critical, three attempts capped at a minute each";

export async function audit(argv: readonly string[]): Promise<number> {
  if (argv.length > 0) {
    console.error(`gate audit: takes no arguments (got ${argv.join(" ")}) — the audit is one fixed invocation`);
    return 2;
  }
  return runAudit({ cwd: layout().repoRoot });
}
