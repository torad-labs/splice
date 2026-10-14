// `release beta <version>` — a beta cut on this machine while GitHub Actions is locked (Marcos, 2026-10-13:
// local betas, no attestation). Releases are built from main only (Marcos, 2026-10-13), so this command bumps and
// pushes nothing: the version bump reaches main through the dev branch like any other change. The command
//
//   1. checks    that origin/main says <version> in the launcher, core's Versions and package.json, and that
//                its CHANGELOG.md has the version's own section;
//   2. builds    the reproducible jar and the staged bundle in a throwaway tree under /tmp at that main sha,
//                through `gate slot` like every gradle run here, with release.yml's steps: stageRelease with the
//                tag threaded in, `release accept`, and the hub pair checked against mcp-hub.sha256;
//   3. publishes `gh release create v<version> --prerelease --target <main sha>` with release.yml's asset set
//                and notes that say the build was local and unattested.
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
  "beta <X.Y.Z-beta.N>                  cut a local, unattested beta from origin/main: build in /tmp, accept, gh release create --prerelease";

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

/** The three version markers, as main must spell them for the version being cut. */
const MARKERS: readonly (readonly [string, (v: string) => string])[] = [
  ["app/src/main/dist/bin/splice-launch", (v) => `SPLICE_GATEWAY_VERSION="${v}"`],
  ["core/src/main/kotlin/splice/core/Versions.kt", (v) => `public const val GATEWAY_VERSION: String = "${v}"`],
  ["package.json", (v) => `  "version": "${v}",`],
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

/** Parse the native build's checksum format once for selection and final staged-byte verification. */
function hubPins(repoRoot: string): Map<string, string> {
  return new Map(
    readFileSync(join(repoRoot, PINS), "utf8").split("\n").filter(Boolean).map((line) => {
      const [hash, name] = line.split(/\s+/);
            if (!hash || !name) throw new Refusal(`release beta: malformed native checksum line in ${PINS}`);
      return [name, hash] as const;
    }),
  );
}

/** The kept hub folder whose two binaries hash to their pins, as lead-install.sh selects it. */
function qualifiedHub(repoRoot: string): string {
  const pins = hubPins(repoRoot);
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
    `Built locally from ${sha} on main without GitHub attestation, because Actions is locked. The tag v${version}`,
    `points at that commit. The build ran \`bun tools/release beta ${version}\` in a throwaway tree, which appended`,
    "this section before building, so the jar embeds it. No Sigstore attestation exists for these assets:",
    "`gh attestation verify` fails on them, and install.sh, run with a signed-in gh, refuses them. Each asset is",
    "bound to the release only by sha256sums.txt.",
    "",
  ].join("\n");
}

/** The version's own CHANGELOG section under the local-build line, read from the main sha's tree. */
function notesFor(changelog: string, version: string, sha: string): string {
  const section = releaseNotes(changelog, version);
  if (section instanceof Error) throw new Refusal(`release beta: on main, ${section.message}`);
  return [
    `**Built locally from ${sha} on main without GitHub attestation, because Actions is locked.** The tag points at`,
    "that commit. `gh attestation verify` fails on these assets, so install.sh refuses them while gh is signed in.",
    "Install with gh signed out, or download splice.jar and splice-launch, check them against sha256sums.txt, and",
    "run `SPLICE_JAR=./splice.jar ./install.sh`.",
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
    run(["git", "fetch", "origin", "main"], repoRoot);
    const sha = run(["git", "rev-parse", "FETCH_HEAD"], repoRoot);
    const short = sha.slice(0, 9);
    for (const [path, spell] of MARKERS) {
      if (!run(["git", "show", `${sha}:${path}`], repoRoot).split("\n").includes(spell(version))) {
        throw new Refusal(`release beta: origin/main (${short}) ${path} does not say ${version}`);
      }
    }
    const notes = notesFor(run(["git", "show", `${sha}:CHANGELOG.md`], repoRoot), version, short);

    const hub = qualifiedHub(repoRoot);
    if (existsSync(tree)) run(["git", "worktree", "remove", "--force", tree], repoRoot);
    run(["git", "worktree", "add", "--detach", tree, sha], repoRoot);
    writeFileSync(join(tree, "docs/PROVENANCE.md"), readFileSync(join(tree, "docs/PROVENANCE.md"), "utf8") + provenanceSection(short, version));
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
    const pinned = hubPins(repoRoot);
    for (const b of HUB) {
      const got = sha256(join(tree, "dist", `splice-mcp-${b}`));
      if (pinned.get(`splice-mcp-${b}`) !== got) throw new Refusal(`release beta: dist/splice-mcp-${b} is ${got}, not its pin in ${PINS}`);
    }

    const notesFile = join(tree, "release-notes.md");
    writeFileSync(notesFile, notes);
    run(["gh", "release", "create", tag, "--prerelease", "--target", sha, "--title", `splice ${tag}`,
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
