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
// So the qualified bytes travel as they are, and two more verbs carry them (splice-hub-pin for splice-lead, Oct 19):
//
//   hub pin <sha>  once hostshield-lead's desk has run the kept pair: copies its two binaries into tools/release/mcp-hub/
//                  and writes their hashes into tools/release/mcp-hub.sha256 in one move, so the committed bytes and
//                  their pins are one commit and cannot disagree. It also records the git tree id of integrations/mcp-hub
//                  at <sha> in tools/release/mcp-hub.tree. A runner checks out exactly the pair the pins name.
//   hub place      checks the committed pair against the pins and puts it where :app:stageRelease reads the hub
//                  binaries, for a stage that excludes the two link tasks (release.yml; beta.ts and the 5B runbook step
//                  do the same by hand from the kept pair). It then compares integrations/mcp-hub at HEAD with the
//                  recorded tree id and prints one warning line when they differ. That is a warning and never a
//                  refusal: a hub change after the pin ships the older hub until someone builds, qualifies and pins
//                  a new pair, and this is how the release says so (splice-lead, Oct 20).
//
// `hub <sha>` qualifies nothing and starts nothing. The qualification is hostshield-lead's: only after its desk has run
// the kept pair is `hub pin` run, and only then does lead-install.sh install the pair and reload a front that is
// running. No unit and no installed binary is touched here.
import { chmodSync, copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

export const usage = [
  "hub <sha>                            build the MCP hub pair at <sha> and keep it under ../artifacts/mcp-hub/<sha>",
  "hub pin <sha>                        after hostshield-lead's qualification: commit the kept pair beside its pins and source tree id",
  "hub place                            check the committed hub pair against the pins, put it where stageRelease reads it, warn if the hub sources moved",
].join("\n  ");

const TREE = "/tmp/splice-hub-build";
const HUB_KEPT = "../artifacts/mcp-hub";
const HUB_BUILT = "integrations/mcp-hub/build/bin/linuxX64";
const PINS = "tools/release/mcp-hub.sha256";
const PAIR = "tools/release/mcp-hub";
const SOURCES = "integrations/mcp-hub";
const SOURCE_TREE = "tools/release/mcp-hub.tree";
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

/** The pins file as name to hash; a line without both is not a pin. */
function pins(repoRoot: string): Map<string, string> {
  const found = new Map<string, string>();
  for (const line of readFileSync(join(repoRoot, PINS), "utf8").split("\n")) {
    const [hash, name] = line.trim().split(/\s+/);
    if (hash && name) found.set(name, hash);
  }
  return found;
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

function build(ref: string, repoRoot: string): number {
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
  console.log(`kept ${kept}; for hostshield-lead to qualify, then \`bun tools/release hub pin ${short}\`:`);
  for (const b of HUB) console.log(`${sha256(join(kept, `splice-mcp-${b}`))}  splice-mcp-${b}`);
  return 0;
}

function pin(ref: string, repoRoot: string): number {
  const short = run(["git", "rev-parse", "--short=9", `${ref}^{commit}`], repoRoot);
  const kept = join(repoRoot, HUB_KEPT, short);
  for (const b of HUB) {
    const from = join(kept, `splice-mcp-${b}`);
    if (!existsSync(from)) throw new Refusal(`release hub pin: ${from} is not kept; \`bun tools/release hub ${short}\` builds it`);
  }
  // Read before anything is written, so a sha without the hub folder leaves nothing half pinned.
  const tree = run(["git", "rev-parse", `${short}:${SOURCES}`], repoRoot);
  mkdirSync(join(repoRoot, PAIR), { recursive: true });
  const lines = HUB.map((b) => {
    const to = join(repoRoot, PAIR, `splice-mcp-${b}`);
    copyFileSync(join(kept, `splice-mcp-${b}`), to);
    chmodSync(to, 0o755);
    return `${sha256(to)}  splice-mcp-${b}`;
  });
  writeFileSync(join(repoRoot, PINS), `${lines.join("\n")}\n`);
  writeFileSync(join(repoRoot, SOURCE_TREE), `${tree}  ${SOURCES}  # built at ${short}\n`);
  console.log(`pinned ${short}: commit ${PAIR}/, ${PINS} and ${SOURCE_TREE} together`);
  for (const line of lines) console.log(line);
  return 0;
}

/**
 * One line when [SOURCES] at HEAD is not the tree the committed pair was built from, else null. A missing record or an
 * unreadable HEAD is also one line: either way the pair cannot be compared with the hub sources.
 */
function drift(repoRoot: string): string | null {
  const file = join(repoRoot, SOURCE_TREE);
  const recorded = existsSync(file) ? readFileSync(file, "utf8").trim().split(/\s+/)[0] : undefined;
  if (!recorded) return `${SOURCE_TREE} is missing, so nothing says which hub sources the pair was built from`;
  let now: string;
  try {
    now = run(["git", "rev-parse", `HEAD:${SOURCES}`], repoRoot);
  } catch (error) {
    if (error instanceof Refusal) return `${SOURCES} cannot be read at HEAD, so the pair cannot be compared with the hub sources`;
    throw error;
  }
  if (now === recorded) return null;
  return `${SOURCES} is not the tree the pair was built from (${recorded.slice(0, 9)} recorded, ${now.slice(0, 9)} at HEAD): ` +
    "the hub that ships is the older one; build, qualify and pin a new pair if the change matters";
}

function place(repoRoot: string): number {
  const pinned = pins(repoRoot);
  // Both binaries are checked before either is placed, so a bad pair never leaves one of them behind.
  const pair = HUB.map((b) => {
    const name = `splice-mcp-${b}`;
    const file = join(repoRoot, PAIR, name);
    if (!existsSync(file)) throw new Refusal(`release hub place: ${PAIR}/${name} is missing; \`bun tools/release hub pin <sha>\` commits it`);
    const got = sha256(file);
    const want = pinned.get(name);
    if (got !== want) throw new Refusal(`release hub place: ${PAIR}/${name} is ${got}, qualified in ${PINS} is ${want ?? "none"}`);
    return { b, file };
  });
  for (const { b, file } of pair) {
    const dir = join(repoRoot, HUB_BUILT, `${b}ReleaseExecutable`);
    mkdirSync(dir, { recursive: true });
    copyFileSync(file, join(dir, `splice-mcp-${b}.kexe`));
  }
  console.log(`placed the qualified hub pair under ${HUB_BUILT}`);
  const moved = drift(repoRoot);
  // A warning, never a refusal; on a runner it becomes an annotation on the run.
  if (moved) console.error(`${process.env.GITHUB_ACTIONS === "true" ? "::warning::" : ""}release hub place: warning: ${moved}`);
  return 0;
}

export function hub(argv: string[], repoRoot: string): number {
  const [first, ...rest] = argv;
  const wellFormed = first === "place" ? rest.length === 0 : first === "pin" ? rest.length === 1 : argv.length === 1;
  if (first === undefined || !wellFormed) {
    console.error(`usage: bun tools/release ${usage}`);
    return 2;
  }
  try {
    if (first === "place") return place(repoRoot);
    if (first === "pin") return pin(rest[0] ?? "", repoRoot);
    return build(first, repoRoot);
  } catch (error) {
    if (error instanceof Refusal) {
      console.error(error.message);
      return 1;
    }
    throw error;
  }
}
