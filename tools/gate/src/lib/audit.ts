// THE DEPENDENCY AUDIT, WITH ITS RETRY — checks/oss/verify-OSS-I.sh:9-18 until PR 6.
//
// The audit is a network call to registry.npmjs.org's advisory endpoint, which has hung for five
// minutes and then answered 503 mid-gate (PR #122's first run, 2026-09-04). Three attempts, each
// capped at one minute, absorb an outage of that shape in under four minutes; a real critical
// advisory still fails all three and the leg stays red. `bun audit` reads bun.lock — the only
// lockfile since restructure PR 1 deleted package-lock.json — and, like npm's, exits non-zero only
// for an advisory at or above --audit-level.
//
// `timeout 60` is the kill timer below, not a program: a hung request must lose the attempt, not
// the gate's next hour. A signalled child reports as a shell would (status.ts), so a timed-out
// attempt is a failed attempt and never a silent pass.
import { exitStatusOf } from "./status.ts";

export const ATTEMPTS = 3;
export const ATTEMPT_TIMEOUT_MS = 60_000;
/** `sleep $((attempt * 20))`: 20s after the first failure, 40s after the second. */
export const BACKOFF_STEP_MS = 20_000;
export const AUDIT_ARGS = ["audit", "--audit-level=critical"] as const;

export interface AuditOptions {
  readonly cwd: string;
  /** `GATE_AUDIT_COMMAND` names an executable to run INSTEAD of `bun`, with the same arguments —
   *  the seam the tests fake the registry through, the way the slot's tests fake gradlew. */
  readonly command?: readonly string[];
  readonly timeoutMs?: number;
  readonly backoffStepMs?: number;
}

export function auditCommand(env: Record<string, string | undefined> = Bun.env): readonly string[] {
  return [env.GATE_AUDIT_COMMAND || "bun", ...AUDIT_ARGS];
}

export function backoffStep(env: Record<string, string | undefined> = Bun.env): number {
  const override = Number(env.GATE_AUDIT_BACKOFF_MS ?? "");
  return Number.isFinite(override) && override >= 0 ? override : BACKOFF_STEP_MS;
}

/** 0 when an attempt passed, 1 when all of them failed. Never throws: the verdict IS the exit code. */
export async function runAudit(options: AuditOptions): Promise<number> {
  const command = options.command ?? auditCommand();
  const timeoutMs = options.timeoutMs ?? ATTEMPT_TIMEOUT_MS;
  const step = options.backoffStepMs ?? backoffStep();

  // THREE, fixed: the refusal below says "three attempts" and a verdict must never be reported in
  // a count the caller chose.
  for (let attempt = 1; attempt <= ATTEMPTS; attempt++) {
    const child = Bun.spawn([...command], { cwd: options.cwd, stdio: ["ignore", "inherit", "inherit"] });
    const timer = setTimeout(() => child.kill("SIGTERM"), timeoutMs);
    await child.exited;
    clearTimeout(timer);
    if (exitStatusOf(child) === 0) return 0;
    // The shell printed this line and slept even after the LAST attempt, which is a minute of
    // sleeping on a verdict that is already decided — and a "retrying" line that then never does.
    if (attempt === ATTEMPTS) break;
    console.error(`bun audit attempt ${attempt} failed — retrying in ${(attempt * step) / 1000}s`);
    await Bun.sleep(attempt * step);
  }
  console.error("bun audit failed on three attempts");
  return 1;
}
