// `release promote` — OPEN THE PROMOTION PR (main -> prod). Port of checks/promote.sh; the header
// below is that script's, because it is the mechanism's only written statement.
//
// THE MECHANISM IS GITHUB-NATIVE: merging the main -> prod PR is the promotion. The push that merge
// produces fires release.yml on prod, which derives the version FROM THE PROMOTED CODE (the launch
// shim's SPLICE_GATEWAY_VERSION — the same marker `release accept` pins against the jar), re-runs
// the full gate, builds, attests, and creates the vX.Y.Z tag at the promoted commit when the draft
// release publishes. Nothing local touches prod, and no command "does the release".
//
// Merge the promotion PR with a MERGE COMMIT, never squash: squashing collapses all of main's
// promoted history into one alien commit on prod and breaks the next promotion's diff.
//
// The forgot-to-bump case is guarded twice, both server-side: promotion-check.yml fails the PR
// BEFORE merge when the version's tag already exists, and release.yml's resolve step refuses again
// at build time. The preflight here is a courtesy third look, not the net.
import { readSync } from "node:fs";
import { SHIM_RELATIVE } from "../lib/shim.ts";

export const usage =
  "promote                              open the promotion PR (main -> prod); merging it IS the release";

/** stdin, one line, EOF-tolerant — `read -r answer || answer=""`. */
export interface PromoteIo {
  readonly prompt: (text: string) => string;
}

function askOnce(text: string): string {
  process.stdout.write(text);
  const byte = Buffer.alloc(1);
  let line = "";
  for (;;) {
    let got = 0;
    try {
      got = readSync(0, byte, 0, 1, null);
    } catch {
      return line;
    }
    if (got === 0) return line; // EOF: the shell's `|| answer=""`, an ordinary mismatch abort
    const char = byte.toString("utf8");
    if (char === "\n") return line;
    line += char;
  }
}

interface RunResult {
  readonly ok: boolean;
  readonly stdout: string;
  readonly stderr: string;
}

function run(cwd: string, argv: readonly string[]): RunResult {
  // `env` is passed EXPLICITLY: Bun.spawnSync with no env gives the child the environment this
  // process STARTED with, so a PATH the caller set (a test's fake gh, an operator's shim) would be
  // invisible to git and gh while `Bun.which` — asked with the same PATH — saw it.
  try {
    const proc = Bun.spawnSync([...argv], { cwd, env: { ...process.env }, stdout: "pipe", stderr: "pipe" });
    return { ok: proc.exitCode === 0, stdout: proc.stdout.toString().trim(), stderr: proc.stderr.toString().trim() };
  } catch (error) {
    // A command that is not on PATH throws here; the shell printed "command not found" and carried
    // the failure in the status. Same shape, so every caller's own refusal is what gets reported.
    return { ok: false, stdout: "", stderr: `promote: ${argv[0]} could not be run (${String(error)})` };
  }
}

function fail(...lines: readonly string[]): number {
  for (const line of lines) console.error(line);
  return 1;
}

export async function promote(
  argv: readonly string[],
  repoRoot: string,
  io: PromoteIo = { prompt: askOnce },
): Promise<number> {
  if (argv.length > 0) return fail(`release promote: takes no arguments (got ${argv.join(" ")})`);

  run(repoRoot, ["git", "fetch", "-q", "origin"]);

  // Pin the SHA the version is read from — everything below (display, confirmation, push) refers to
  // THIS commit, so what the operator confirms is exactly what ships (review of #86: the first-
  // promotion push resolved origin/main at push time, after an interactive prompt of unbounded
  // duration, so main advancing mid-prompt could silently change what got promoted).
  const mainSha = run(repoRoot, ["git", "rev-parse", "origin/main"]).stdout;
  const shim = run(repoRoot, ["git", "show", `${mainSha}:${SHIM_RELATIVE}`]).stdout;
  const version = /^const SPLICE_GATEWAY_VERSION = "([^"]+)";$/m.exec(shim)?.[1] ?? "";
  if (version === "") return fail("promote: could not read SPLICE_GATEWAY_VERSION from origin/main");

  if (run(repoRoot, ["git", "ls-remote", "--exit-code", "--tags", "origin", `refs/tags/v${version}`]).ok) {
    return fail(
      `promote: tag v${version} already exists — bump the version on main first (Versions.kt,`,
      `         ${SHIM_RELATIVE}, package.json move together).`,
    );
  }

  // prod is created on first use; a PR needs the base ref to exist. Seeding it at main's tip is a
  // no-op promotion (same tree, no release fires until the NEXT prod push differs... it does fire —
  // a push event is a push event). So seed from the CURRENT PROD-LESS state only via the API ref
  // create, which is a push of main's tip and WILL fire release.yml once, releasing v<version>.
  // That is the correct first promotion, stated rather than hidden.
  if (!run(repoRoot, ["git", "ls-remote", "--exit-code", "origin", "refs/heads/prod"]).ok) {
    console.log("promote: prod does not exist yet. Creating it IS the first promotion:");
    console.log(`         release.yml will fire and publish v${version} from ${mainSha}.`);
    if (Bun.env.PROMOTE_YES !== "1") {
      const answer = io.prompt(`Type the version to confirm the FIRST promotion (${version}): `);
      if (answer !== version) return fail(`promote: aborted (typed '${answer === "" ? "<eof>" : answer}')`);
    }
    // Freshness check AT the point of creation: the confirmation prompt is unbounded, so require
    // that main still points at the confirmed SHA before pushing it. The push itself uses the
    // PINNED sha — never a re-resolved ref — so a race can only abort, never promote something the
    // operator did not see.
    run(repoRoot, ["git", "fetch", "-q", "origin"]);
    const now = run(repoRoot, ["git", "rev-parse", "origin/main"]).stdout;
    if (now !== mainSha) {
      return fail(
        `promote: main moved while you were confirming (now ${run(repoRoot, ["git", "rev-parse", "--short", "origin/main"]).stdout},`,
        `         confirmed ${mainSha}). Nothing pushed — re-run to promote the new tip.`,
      );
    }
    const pushed = run(repoRoot, ["git", "push", "origin", `${mainSha}:refs/heads/prod`]);
    if (!pushed.ok) return fail(pushed.stderr || "promote: pushing prod failed");
    console.log(`promote: prod created at ${mainSha} — release.yml is publishing v${version}.`);
    return 0;
  }

  // Everything below needs the GitHub CLI. Guarded EXPLICITLY: under `set -e` a failing command
  // substitution killed the script with no message at all — the sandbox test found exactly that
  // (a broken gh made promote.sh exit 1 in complete silence).
  if (!Bun.which("gh", { PATH: process.env.PATH ?? "" })) {
    return fail("promote: the GitHub CLI (gh) is required to open the promotion PR — https://cli.github.com");
  }
  const existing = run(repoRoot, [
    "gh", "pr", "list", "--base", "prod", "--head", "main", "--state", "open", "--json", "url", "--jq", ".[0].url // empty",
  ]);
  if (!existing.ok) return fail("promote: gh failed listing PRs — check 'gh auth status'");
  if (existing.stdout !== "") {
    console.log(`promote: a promotion PR is already open — merge it (merge commit, not squash): ${existing.stdout}`);
    return 0;
  }

  const body =
    `Merging this PR IS the promotion: the resulting push to prod fires release.yml, which re-gates, ` +
    `builds, attests, and publishes **v${version}** with the tag created at the promoted commit.\n\n` +
    "Merge with a **merge commit**, not squash.";
  const created = run(repoRoot, [
    "gh", "pr", "create", "--base", "prod", "--head", "main",
    "--title", `chore(release): promote main to prod (v${version})`,
    "--body", body,
  ]);
  if (!created.ok) return fail(created.stderr || "promote: gh failed creating the promotion PR");
  if (created.stdout !== "") console.log(created.stdout);
  console.log(`promote: promotion PR opened — merging it releases v${version}.`);
  return 0;
}
