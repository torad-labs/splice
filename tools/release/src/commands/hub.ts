// `release hub <sha>` — builds the MCP hub's two native binaries at <sha> and keeps them, as built, where the
// install and the beta look for a qualified pair: ../artifacts/mcp-hub/<short sha>/ (splice-runtime-lead, Oct 13).
//
// A front change reaches nobody until its binary is rebuilt, qualified and installed, and an ordinary splice install
// never rebuilds it: Kotlin/Native writes each source file's absolute path into the binary, so a build in an install's
// throwaway tree can never hash to the pins (lead-install.sh). This verb is the one place a pair is built. It builds
// in one tree, /tmp/splice-hub-build, then prints the pair in tools/release/mcp-hub.sha256's format.
//
// A fixed tree does NOT make a build reproducible, and no two builds of the same sources are expected to hash the same.
// `strings` on the pairs kept on 2026-10-13 and 2026-10-19 finds the pre-push tree's path, projects/<name>/repo/.git/
// splice-prepush/tree, in every binary and /tmp/splice-hub-build in none: Gradle's build cache (org.gradle.caching=true)
// hands the compile back from the tree that first made it, with that tree's paths inside (splice-hub-pin, Oct 19). The
// two holders, whose sources did not change between them, differ in 84 bytes: the 6 letters of the project name in one
// embedded path (projects/mythos to projects/splice), the 20 bytes of the ELF build ID, and 58 more spread through the
// file that were not decoded. So a pin is the bytes of ONE build: the kept pair ships as built and is never rebuilt to
// check it, and any other build, a cold cache, another tree or a runner, is expected to hash differently. A rebuild is for changed hub
// sources (`git diff --quiet <kept sha> <head> -- integrations/mcp-hub`), and it is qualified again.
//
// It qualifies nothing and starts nothing. The pins are hostshield-lead's: once its desk has run the kept pair, it
// writes those two lines into mcp-hub.sha256, and only then does lead-install.sh install the pair and reload a front
// that is running. No unit, no installed binary and no pin is touched here.
import { chmodSync, copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

export const usage =
  "hub <sha>                            build the MCP hub pair at <sha> and keep it under ../artifacts/mcp-hub/<sha>";

const TREE = "/tmp/splice-hub-build";
const HUB_KEPT = "../artifacts/mcp-hub";
const HUB_BUILT = "integrations/mcp-hub/build/bin/linuxX64";
const HUB = ["holder", "front"] as const;

class Refusal extends Error {}

function run(argv: string[], cwd: string, quiet = true): string {
  const child = Bun.spawnSync(argv, { cwd, stdin: "ignore", stdout: quiet ? "pipe" : "inherit", stderr: quiet ? "pipe" : "inherit" });
  if (child.exitCode !== 0) {
    const said = quiet ? `\n${child.stderr?.toString() ?? ""}${child.stdout?.toString() ?? ""}`.trimEnd() : "";
    throw new Refusal(`release hub: ${argv.join(" ")} exited ${child.exitCode ?? child.signalCode}${said}`);
  }
  return quiet ? (child.stdout?.toString() ?? "").trim() : "";
}

function sha256(path: string): string {
  return new Bun.CryptoHasher("sha256").update(readFileSync(path)).digest("hex");
}

/** The one binary Gradle linked for [b], whatever its base name. */
function built(tree: string, b: string): string {
  const dir = join(tree, HUB_BUILT, `${b}ReleaseExecutable`);
  const found = existsSync(dir) ? readdirSync(dir).filter((name) => name.endsWith(".kexe")) : [];
  const [only] = found;
  if (only === undefined || found.length !== 1) {
    throw new Refusal(`release hub: expected one .kexe in ${dir}, found ${found.length}`);
  }
  return join(dir, only);
}

export function hub(argv: string[], repoRoot: string): number {
  const [ref] = argv;
  if (!ref || argv.length !== 1) {
    console.error(`usage: bun tools/release ${usage}`);
    return 2;
  }
  try {
    const short = run(["git", "rev-parse", "--short=9", `${ref}^{commit}`], repoRoot);
    const kept = join(repoRoot, HUB_KEPT, short);
    if (existsSync(kept)) throw new Refusal(`release hub: ${kept} is already kept; a kept pair is never rebuilt over`);
    if (existsSync(TREE)) run(["git", "worktree", "remove", "--force", TREE], repoRoot);
    run(["git", "worktree", "add", "--detach", TREE, short], repoRoot);
    try {
      run(["bun", "tools/gate", "slot", `hub-${short}`, "--",
        ":integrations-mcp-hub:linkHolderReleaseExecutableLinuxX64",
        ":integrations-mcp-hub:linkFrontReleaseExecutableLinuxX64"], TREE, false);
      mkdirSync(kept, { recursive: true, mode: 0o700 });
      for (const b of HUB) {
        const file = join(kept, `splice-mcp-${b}`);
        copyFileSync(built(TREE, b), file);
        chmodSync(file, 0o700);
      }
    } finally {
      run(["git", "worktree", "remove", "--force", TREE], repoRoot);
    }
    console.log(`kept ${kept}; for hostshield-lead to qualify, then pin in tools/release/mcp-hub.sha256:`);
    for (const b of HUB) console.log(`${sha256(join(kept, `splice-mcp-${b}`))}  splice-mcp-${b}`);
    return 0;
  } catch (error) {
    if (error instanceof Refusal) {
      console.error(error.message);
      return 1;
    }
    throw error;
  }
}
