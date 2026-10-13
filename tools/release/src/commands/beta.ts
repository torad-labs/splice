// `release beta <version>` — a beta cut on this machine while GitHub Actions is locked (Marcos, 2026-10-13:
// local betas, no attestation). One command, idempotent up to the publish:
//
//   1. bump    the launcher's SPLICE_GATEWAY_VERSION, core's GATEWAY_VERSION and package.json to <version>,
//              committed by path in the shared checkout (skipped when the three already say it);
//   2. push    the bump through the pre-push gate when origin does not have it yet (the caller's env, e.g.
//              BUILDGATE_TIMEOUT, reaches the hook);
//   3. build   the reproducible jar and the staged bundle in a throwaway tree under /tmp at the pushed bump,
//              through `gate slot` like every gradle run here, with release.yml's steps: stageRelease with the
//              tag threaded in, `release accept`, and the hub pair checked against mcp-hub.sha256;
//   4. publish `gh release create v<version> --prerelease` with release.yml's asset set and notes that say
//              the build was local and unattested.
//
// The MCP hub pair is never linked in the tree: Kotlin/Native writes each source file's absolute path into the
// binary, so a tree build cannot hash to the qualified pair (lead-install.sh). The kept folder under
// artifacts/mcp-hub whose two binaries hash to the pins is placed where stageRelease reads them, and the link
// tasks are excluded.
//
// PROVENANCE.md gains its local-build section in the tree before the jar is built, because the jar embeds it and
// `release accept` requires the staged copy to equal the embedded one byte for byte. The repository's
// docs/PROVENANCE.md is not changed.
import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { releaseNotes } from "../lib/changelog.ts";

export const usage =
  "beta <X.Y.Z-beta.N>                  cut a local, unattested beta: bump, push, build in /tmp, accept, gh release create --prerelease";

const LAUNCHER = "app/src/main/dist/bin/splice-launch";
const VERSIONS = "core/src/main/kotlin/splice/core/Versions.kt";
const PACKAGE = "package.json";
const PINS = "tools/release/mcp-hub.sha256";
const HUB_KEPT = "../artifacts/mcp-hub";
const HUB_STAGE = "integrations/mcp-hub/build/bin/linuxX64";
const HUB = ["holder", "front"] as const;
const ASSETS = [
  "splice.jar", "splice-launch", "install.sh", "splice-mcp-holder", "splice-mcp-front", "LICENSE",
  "THIRD_PARTY_NOTICES.md", "THIRD_PARTY_LICENSES.txt", "PROVENANCE.md", "bom.cdx.json",
  "dependency-licenses.json", "sha256sums.txt",
];
const BETA = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)-beta\.(0|[1-9]\d*)$/;

/** The three version markers and how each is spelled; a pattern that matches nothing is a refusal. */
const MARKERS: readonly (readonly [string, RegExp, (v: string) => string])[] = [
  [LAUNCHER, /^SPLICE_GATEWAY_VERSION="[^"]*"$/m, (v) => `SPLICE_GATEWAY_VERSION="${v}"`],
  [VERSIONS, /^public const val GATEWAY_VERSION: String = "[^"]*"$/m, (v) => `public const val GATEWAY_VERSION: String = "${v}"`],
  [PACKAGE, /^ {2}"version": "[^"]*",$/m, (v) => `  "version": "${v}",`],
];

class Refusal extends Error {}

function run(argv: string[], cwd: string, env: Record<string, string | undefined> = Bun.env, quiet = true): string {
  const child = Bun.spawnSync(argv, { cwd, env, stdin: "ignore", stdout: quiet ? "pipe" : "inherit", stderr: quiet ? "pipe" : "inherit" });
  if (child.exitCode !== 0) {
    const said = quiet ? `\n${child.stderr?.toString() ?? ""}${child.stdout?.toString() ?? ""}`.trimEnd() : "";
    throw new Refusal(`release beta: ${argv.join(" ")} exited ${child.exitCode ?? child.signalCode}${said}`);
  }
  return quiet ? (child.stdout?.toString() ?? "").trim() : "";
}

function sha256(path: string): string {
  return new Bun.CryptoHasher("sha256").update(readFileSync(path)).digest("hex");
}

/** Step 1: rewrite the three markers and commit them by path, or say they already carry the version. */
function bump(repoRoot: string, version: string): void {
  const rewritten = MARKERS.map(([path, pattern, spell]) => {
    const text = readFileSync(join(repoRoot, path), "utf8");
    if (!pattern.test(text)) throw new Refusal(`release beta: ${path} has no version marker matching ${pattern}`);
    return [path, text, text.replace(pattern, spell(version))] as const;
  });
  if (rewritten.every(([, before, after]) => before === after)) {
    console.log(`release beta: the version markers already say ${version}`);
    return;
  }
  const paths = rewritten.map(([path]) => path);
  if (run(["git", "status", "--porcelain", "--", ...paths], repoRoot) !== "") {
    throw new Refusal(`release beta: uncommitted edits in ${paths.join(", ")}; the bump commits only its own lines`);
  }
  for (const [path, , after] of rewritten) writeFileSync(join(repoRoot, path), after);
  run(["git", "commit", "-m", `chore(release): v${version}, the launcher, Versions and package.json say ${version} (splice-platform)`, "--", ...paths], repoRoot, Bun.env, false);
}

/** The kept hub folder whose two binaries hash to their pins, as lead-install.sh selects it. */
function qualifiedHub(repoRoot: string): string {
  const pins = new Map(
    readFileSync(join(repoRoot, PINS), "utf8").split("\n").filter(Boolean).map((line) => {
      const [hash, name] = line.split(/\s+/);
      return [name, hash] as const;
    }),
  );
  const kept = join(repoRoot, HUB_KEPT);
  const folder = (existsSync(kept) ? readdirSync(kept) : []).map((name) => join(kept, name)).find((dir) =>
    HUB.every((b) => {
      const file = join(dir, `splice-mcp-${b}`);
      return existsSync(file) && pins.get(`splice-mcp-${b}`) === sha256(file);
    }),
  );
  if (!folder) throw new Refusal(`release beta: no kept hub pair under ${kept} hashes to ${PINS}`);
  return folder;
}

function provenanceSection(sha: string, version: string): string {
  return [
    "",
    `## This build: v${version}`,
    "",
    `Built locally from ${sha} without GitHub attestation, because Actions is locked. The tag v${version} points at`,
    `that commit. The build ran \`bun tools/release beta ${version}\` in a throwaway`,
    "tree, which appended this section before building, so the jar embeds it. No Sigstore attestation exists for",
    "these assets: `gh attestation verify` fails on them, and install.sh, run with a signed-in gh, refuses them.",
    "Each asset is bound to the release only by sha256sums.txt.",
    "",
  ].join("\n");
}

/** The version's own CHANGELOG section under the local-build line. The release tests require that section, so a
 *  version without one is refused before anything is bumped or pushed. */
function notesFor(repoRoot: string, version: string, sha: string): string {
  const section = releaseNotes(readFileSync(join(repoRoot, "CHANGELOG.md"), "utf8"), version);
  if (section instanceof Error) throw new Refusal(`release beta: ${section.message}; add it before cutting`);
  return [
    `**Built locally from ${sha} without GitHub attestation, because Actions is locked.** The tag points at that commit.`,
    "`gh attestation verify` fails on these assets, so install.sh refuses them",
    "while gh is signed in. Install with gh signed out, or download splice.jar and splice-launch, check them against",
    "sha256sums.txt, and run `SPLICE_JAR=./splice.jar ./install.sh`.",
    "",
    section,
  ].join("\n");
}

export async function beta(argv: string[], repoRoot: string): Promise<number> {
  const [version, ...rest] = argv;
  if (!version || rest.length || !BETA.test(version)) {
    console.error(`usage: bun tools/release ${usage}`);
    return 2;
  }
  const tag = `v${version}`;
  const tree = `/tmp/splice-beta-${version}`;
  try {
    if (Bun.spawnSync(["gh", "release", "view", tag], { cwd: repoRoot, stdout: "ignore", stderr: "ignore" }).exitCode === 0) {
      throw new Refusal(`release beta: ${tag} is already published; a re-release deletes it first`);
    }
    const branch = run(["git", "rev-parse", "--abbrev-ref", "HEAD"], repoRoot);
    run(["git", "fetch", "origin", branch], repoRoot);
    if (run(["git", "rev-parse", "HEAD"], repoRoot) !== run(["git", "rev-parse", "FETCH_HEAD"], repoRoot)) {
      const ahead = Bun.spawnSync(["git", "merge-base", "--is-ancestor", "FETCH_HEAD", "HEAD"], { cwd: repoRoot }).exitCode === 0;
      if (!ahead) throw new Refusal(`release beta: ${branch} has diverged from origin; the beta cuts from the pushed tip`);
    }

    notesFor(repoRoot, version, "");
    bump(repoRoot, version);
    const bumpSha = run(["git", "rev-parse", "HEAD"], repoRoot);
    run(["git", "fetch", "origin", branch], repoRoot);
    if (Bun.spawnSync(["git", "merge-base", "--is-ancestor", bumpSha, "FETCH_HEAD"], { cwd: repoRoot }).exitCode !== 0) {
      console.log(`release beta: pushing ${bumpSha.slice(0, 9)} to origin/${branch} through the pre-push gate`);
      run(["git", "push", "origin", `HEAD:${branch}`], repoRoot, Bun.env, false);
    }

    const hub = qualifiedHub(repoRoot);
    if (existsSync(tree)) run(["git", "worktree", "remove", "--force", tree], repoRoot);
    run(["git", "worktree", "add", "--detach", tree, bumpSha], repoRoot);
    writeFileSync(join(tree, "docs/PROVENANCE.md"), readFileSync(join(tree, "docs/PROVENANCE.md"), "utf8") + provenanceSection(bumpSha.slice(0, 9), version));
    for (const b of HUB) {
      const dir = join(tree, HUB_STAGE, `${b}ReleaseExecutable`);
      mkdirSync(dir, { recursive: true });
      copyFileSync(join(hub, `splice-mcp-${b}`), join(dir, `splice-mcp-${b}.kexe`));
    }

    const env = { ...Bun.env, SPLICE_RELEASE_TAG: tag, SPLICE_EXPECTED_VERSION: version };
    run(["bun", "tools/gate", "slot", `beta-${version}`, "--", ":app:shadowJar", ":app:stageRelease",
      "-x", ":integrations-mcp-hub:linkHolderReleaseExecutableLinuxX64",
      "-x", ":integrations-mcp-hub:linkFrontReleaseExecutableLinuxX64"], tree, env, false);
    run(["bun", "tools/release", "accept"], tree, env, false);
    const pinned = readFileSync(join(repoRoot, PINS), "utf8");
    for (const b of HUB) {
      const got = sha256(join(tree, "dist", `splice-mcp-${b}`));
      if (!pinned.includes(`${got} splice-mcp-${b}`)) throw new Refusal(`release beta: dist/splice-mcp-${b} is ${got}, not its pin in ${PINS}`);
    }

    const notesFile = join(tree, "release-notes.md");
    writeFileSync(notesFile, notesFor(repoRoot, version, bumpSha.slice(0, 9)));
    run(["gh", "release", "create", tag, "--prerelease", "--target", bumpSha, "--title", `splice ${tag}`,
      "--notes-file", notesFile, ...ASSETS.map((asset) => join(tree, "dist", asset))], repoRoot, Bun.env, false);
    console.log(`release beta: jar ${sha256(join(tree, "dist/splice.jar"))}`);
    console.log(run(["gh", "release", "view", tag, "--json", "url", "-q", ".url"], repoRoot));
    run(["git", "worktree", "remove", "--force", tree], repoRoot);
    rmSync(tree, { recursive: true, force: true });
    return 0;
  } catch (error) {
    if (!(error instanceof Refusal)) throw error;
    console.error(error.message);
    if (existsSync(tree)) console.error(`release beta: the build tree is kept at ${tree} for a look`);
    return 1;
  }
}
