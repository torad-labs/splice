// `gate title [<title>]` — the local preflight for a PR title, before the PR exists.
//
// A port of checks/pr-title.sh (retired in PR 5). Two modes, and the difference is the point:
//   - an EXPLICIT title is validated anywhere, CI included;
//   - the NO-ARG mode judges HEAD's subject, which is only meaningful LOCALLY. In Actions there are
//     exactly two contexts and neither has an authored subject worth judging (both failures were
//     SHIPPED, not hypothesised): a PR run checks out the synthetic merge ref, whose subject is
//     "Merge <sha> into <sha>" and whose tip is grafted PARENTLESS by fetch-depth:1, so no merge
//     detection can fire; and a push-to-main run sees the just-landed SQUASH commit, whose subject
//     GitHub derives from the COMMIT rather than the validated PR title, so failing there is
//     retroactive noise about history nobody can amend. So in Actions the no-arg mode defers to the
//     org gate, and a local merge tip (2+ parents) is skipped as carrying nothing authored.
import { TYPES, conventionalType } from "../lib/conventional.ts";
import { layout } from "../lib/repo.ts";

export const usage = "title [<title>]                      validate a PR title (or HEAD's subject) against the one type list";

export interface TitleOptions {
  /** where `git` runs for the no-arg mode; the repository root by default */
  readonly cwd?: string;
  readonly env?: Record<string, string | undefined>;
}

export async function title(argv: readonly string[], options: TitleOptions = {}): Promise<number> {
  if (argv.length > 1) {
    console.error(`gate title: at most one argument, the title (got ${argv.length})`);
    return 2;
  }
  const env = options.env ?? Bun.env;
  const cwd = options.cwd ?? layout().repoRoot;
  let proposed = argv[0] ?? "";

  if (!proposed) {
    if (env.GITHUB_ACTIONS === "true") {
      console.log("  pr title: CI run, skipped (the org gate validates the PR title; this check is the local preflight)");
      return 0;
    }
    // `--parents` prints "<sha> <parent>..."; more than 2 words is 2+ parents. NB: adding `--count`
    // silently defeats it — it replaces the output with a bare "1" (shipped that way once).
    if (git(cwd, ["rev-list", "--parents", "-n1", "HEAD"]).split(/\s+/).filter(Boolean).length > 2) {
      console.log("  pr title: merge commit, skipped (nothing authored to judge)");
      return 0;
    }
    proposed = git(cwd, ["log", "-1", "--format=%s"]).trim();
  }

  if (!proposed) {
    console.error("pr-title: no title given and no commit to read");
    return 1;
  }

  const type = conventionalType(proposed);
  if (type) {
    console.log(`  pr title: valid (${type}) — ${proposed}`);
    return 0;
  }
  console.error(
    "pr-title: INVALID conventional type.\n\n" +
      `  got:      ${proposed}\n` +
      "  expected: type: subject   (or  type(scope)!: subject )\n" +
      `  types:    ${TYPES.split("|").join(", ")}\n\n` +
      "This is the list the ORG gate enforces, and it is the only one that counts.\n" +
      "Do NOT infer the convention from `git log` — most of this repo's history predates the\n" +
      "gate and uses types (tracker, upstream, auth, walls, ...) that fail it.",
  );
  return 1;
}

/** git's stdout, or "" when the command fails (no repository, no commits) — the script's `2>/dev/null`. */
function git(cwd: string, args: readonly string[]): string {
  const proc = Bun.spawnSync(["git", ...args], { cwd, stdout: "pipe", stderr: "pipe" });
  return proc.exitCode === 0 ? proc.stdout.toString() : "";
}
