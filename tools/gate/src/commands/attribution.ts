// `gate attribution` — no commit HEAD reaches carries a Claude attribution line.
//
// splice is public. On 2026-09-24 the operator had every branch and tag rewritten to strip the
// `Claude-Session: https://claude.ai/code/session_…` trailers, the `Co-Authored-By: … <noreply@anthropic.com>`
// lines and the "Generated with [Claude Code]" lines from 1,385 commit messages ("NONE OF THE COMMITS
// SHOULD HAVE THE CLAUDE SESSION. It's not safe."). This leg keeps it that way, and it scans ALL of
// HEAD's history rather than the PR's own commits for a reason: a branch cut from the pre-rewrite
// history carries the old attributed commits back in, and only a whole-history scan sees them.
//
// CI checks out the PR merge ref one commit deep, parentless (see title.ts), so there is no history
// to scan until it is fetched: the leg asks for HEAD's full ancestry by sha, blobless (commit objects
// only, a few MB). A history it cannot read FAILS the leg — an unscanned history is not a clean one.
import { layout } from "../lib/repo.ts";

export const usage = "attribution                          refuse a Claude attribution line in any commit HEAD reaches";

/** The four shapes the rewrite removed, matched per line. A commit that MENTIONS a trailer in prose
 *  (no line starting with it, no Anthropic address) is not attribution and passes. */
export const ATTRIBUTION: readonly RegExp[] = [
  /^\s*co-authored-by:.*noreply@anthropic\.com/im,
  /^\s*claude-session:/im,
  /claude\.ai\/code\/session_/i,
  /generated with \[claude code\]/i,
];

export interface AttributionOptions {
  /** where `git` runs; the repository root by default */
  readonly cwd?: string;
}

export interface Offender {
  readonly sha: string;
  readonly subject: string;
  readonly line: string;
}

export async function attribution(argv: readonly string[], options: AttributionOptions = {}): Promise<number> {
  if (argv.length > 0) {
    console.error(`gate attribution: takes no arguments (got ${argv.length})`);
    return 2;
  }
  const cwd = options.cwd ?? layout().repoRoot;
  if (git(cwd, ["rev-parse", "--is-shallow-repository"]).stdout.trim() === "true") {
    const head = git(cwd, ["rev-parse", "HEAD"]).stdout.trim();
    const deepen = git(cwd, ["fetch", "--quiet", "--unshallow", "--filter=blob:none", "origin", head]);
    if (deepen.code !== 0) {
      console.error(`attribution: this clone is shallow and its history could not be fetched, so it was not scanned.\n${deepen.stderr}`);
      return 1;
    }
  }
  const log = git(cwd, ["log", "--format=%H%x1f%s%x1f%B%x1e", "HEAD"]);
  if (log.code !== 0) {
    console.error(`attribution: could not read HEAD's history.\n${log.stderr}`);
    return 1;
  }
  const offenders = scan(log.stdout);
  const commits = log.stdout.split("\x1e").filter((record) => record.trim()).length;
  if (offenders.length === 0) {
    console.log(`  attribution: ${commits} commit(s) scanned, none carries a Claude attribution line`);
    return 0;
  }
  console.error(
    `attribution: ${offenders.length} commit(s) HEAD reaches carry a Claude attribution line. splice is public; ` +
      "commit messages carry no Claude-Session trailer, no claude.ai session URL, no Co-Authored-By for " +
      "noreply@anthropic.com and no \"Generated with [Claude Code]\". Reword these commits before pushing. " +
      "If they are pre-rewrite history, the branch was cut from the old tip: move it onto origin/feat/v0.4.0.\n",
  );
  for (const o of offenders.slice(0, 20)) console.error(`  ${o.sha.slice(0, 12)} ${o.subject}\n      ${o.line}`);
  if (offenders.length > 20) console.error(`  … and ${offenders.length - 20} more`);
  return 1;
}

/** Every commit in a `%H%x1f%s%x1f%B%x1e` log whose message carries an attribution line. */
export function scan(log: string): Offender[] {
  const offenders: Offender[] = [];
  for (const record of log.split("\x1e")) {
    const [sha, subject, body] = record.replace(/^\n/, "").split("\x1f");
    if (!sha || body === undefined) continue;
    const line = body.split("\n").find((l) => ATTRIBUTION.some((pattern) => pattern.test(l)));
    if (line !== undefined) offenders.push({ sha, subject: subject ?? "", line: line.trim() });
  }
  return offenders;
}

function git(cwd: string, args: readonly string[]): { code: number; stdout: string; stderr: string } {
  const proc = Bun.spawnSync(["git", ...args], { cwd, stdout: "pipe", stderr: "pipe" });
  return { code: proc.exitCode ?? 1, stdout: proc.stdout.toString(), stderr: proc.stderr.toString() };
}
