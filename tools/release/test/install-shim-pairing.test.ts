// install.sh installs a launcher that pairs with the jar (2026-10-01). The two are version-locked: the
// launcher refuses a daemon whose shim version is not its own. A prebuilt jar installed with no sibling
// launcher (`SPLICE_JAR=… ./install.sh`) reused whatever launcher was already installed, so a newer jar
// went live beside an older launcher, and the run still said "installed". The jar names the launcher it
// needs (`shim-version`, in every release since 0.3.0) and the launcher carries the same marker
// (SPLICE_SHIM_VERSION, the line InstallShim.kt reads), so the installer can keep a pair.
//
// The harness is install-rollback.test.ts's: a PATH farm of the real tools the script needs, java faked
// for a jar that needs `shim-new`, and the script run from a copy outside the repo so it does not build.
// The release the installer may fetch from is a file:// directory, so nothing touches the network.
import { afterAll, describe, expect, test } from "bun:test";
import { createHash } from "node:crypto";
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { layout } from "../../gate/src/lib/repo.ts";

const { repoRoot } = layout();
const workspaces: string[] = [];
afterAll(() => {
  for (const dir of workspaces) rmSync(dir, { recursive: true, force: true });
});

const scriptDir = mkdtempSync(join(tmpdir(), "install-shim-pairing-script-"));
workspaces.push(scriptDir);
const INSTALLER = join(scriptDir, "install.sh");
writeFileSync(INSTALLER, readFileSync(join(repoRoot, "install.sh")));

const TOOLS = [
  "bash", "env", "sh", "uname", "curl", "sha256sum", "awk", "grep", "sed", "mktemp", "cp", "mv", "rm",
  "ln", "readlink", "install", "chmod", "mkdir", "dirname", "cat", "head", "tr", "timeout", "printf",
];

const launcher = (marker: string) => `#!/usr/bin/env node\nconst SPLICE_SHIM_VERSION = "${marker}";\n`;
const NEW = launcher("shim-new");
const OLD = launcher("shim-old");
const PREVIOUS_JAR = "previous jar\n";

function exe(path: string, body: string) {
  writeFileSync(path, body);
  chmodSync(path, 0o755);
}

interface Setup {
  /** The launcher already installed beside the previous jar. */
  installed: string;
  /** A launcher next to the prebuilt jar, as a release bundle ships it; none when absent. */
  sibling?: string;
  /** A launcher named by SPLICE_SHIM. */
  explicit?: string;
}

/** Installs a prebuilt jar that needs `shim-new` over a previous install, with a release of that
 *  version (jar, launcher, sums) on a file:// base the installer may fetch the launcher from. */
async function installPrebuilt(setup: Setup) {
  const dir = mkdtempSync(join(tmpdir(), "install-shim-pairing-"));
  workspaces.push(dir);
  const bin = join(dir, "path");
  mkdirSync(bin);
  for (const tool of TOOLS) {
    const real = Bun.which(tool);
    if (!real) throw new Error(`the installer test needs ${tool} on PATH`);
    symlinkSync(real, join(bin, tool));
  }
  exe(join(bin, "java"), `#!/usr/bin/env bash
case "$*" in
  -version) echo 'openjdk version "21.0.4" 2024-07-16' >&2 ;;
  *" shim-version") echo shim-new ;;
  *" version") echo "splice 9.9.9" ;;
  *" install --all") mkdir -p "$SPLICE_BIN_DIR" && ln -sfn "$SPLICE_JAR" "$SPLICE_BIN_DIR/splice" ;;
  *) : ;;
esac
`);
  exe(join(bin, "node"), "#!/usr/bin/env bash\n[ \"$1\" = -v ] && echo v24.0.0\nexit 0\n");
  exe(join(bin, "claude"), "#!/usr/bin/env bash\nexit 0\n");

  const candidate = join(dir, "candidate");
  mkdirSync(candidate);
  writeFileSync(join(candidate, "splice.jar"), "new jar\n");
  if (setup.sibling !== undefined) writeFileSync(join(candidate, "splice-launch"), setup.sibling);
  const explicit = join(dir, "explicit-launch");
  if (setup.explicit !== undefined) writeFileSync(explicit, setup.explicit);

  const release = join(dir, "release");
  mkdirSync(release);
  writeFileSync(join(release, "splice-launch"), NEW);
  const sha = createHash("sha256").update(NEW).digest("hex");
  writeFileSync(join(release, "sha256sums.txt"), `${sha}  splice-launch\n`);

  const share = join(dir, "share");
  mkdirSync(share);
  writeFileSync(join(share, "splice.jar"), PREVIOUS_JAR);
  exe(join(share, "splice-launch"), setup.installed);

  const proc = Bun.spawn(["bash", INSTALLER], {
    cwd: dir,
    stdin: "ignore",
    stdout: "pipe",
    stderr: "pipe",
    env: {
      PATH: bin,
      HOME: dir,
      SPLICE_SHARE_DIR: share,
      SPLICE_BIN_DIR: join(dir, "bin"),
      SPLICE_JAR: join(candidate, "splice.jar"),
      SPLICE_RELEASE_BASE_URL: `file://${release}`,
      ...(setup.explicit !== undefined ? { SPLICE_SHIM: explicit } : {}),
    },
  });
  const [out, err, code] = await Promise.all([
    new Response(proc.stdout).text(),
    new Response(proc.stderr).text(),
    proc.exited,
  ]);
  return {
    code,
    out: out + err,
    jar: readFileSync(join(share, "splice.jar"), "utf8"),
    shim: readFileSync(join(share, "splice-launch"), "utf8"),
  };
}

describe("install.sh pairs the launcher with the jar", () => {
  test("a prebuilt jar with no sibling, over a stale installed launcher, installs the launcher it needs", async () => {
    const r = await installPrebuilt({ installed: OLD });
    expect(r.code, r.out).toBe(0);
    expect(r.shim).toBe(NEW);
  });

  test("an installed launcher that already pairs is kept, and a 0.3.x launcher's marker is read too", async () => {
    const kept = await installPrebuilt({ installed: `${NEW}// a local edit\n` });
    expect(kept.code, kept.out).toBe(0);
    expect(kept.shim).toBe(`${NEW}// a local edit\n`);
    const bash = await installPrebuilt({ installed: "#!/usr/bin/env bash\nSPLICE_SHIM_VERSION=\"shim-new\"\n" });
    expect(bash.code, bash.out).toBe(0);
    expect(bash.shim).toContain('SPLICE_SHIM_VERSION="shim-new"');
  });

  test("a sibling launcher that pairs is the one installed", async () => {
    const r = await installPrebuilt({ installed: OLD, sibling: `${NEW}// the bundle's own\n` });
    expect(r.code, r.out).toBe(0);
    expect(r.shim).toBe(`${NEW}// the bundle's own\n`);
  });

  test("a launcher named by SPLICE_SHIM that does not pair is refused before anything goes live", async () => {
    const r = await installPrebuilt({ installed: OLD, explicit: OLD });
    expect(r.code).not.toBe(0);
    expect(r.out).toContain("shim-old");
    expect(r.out).toContain("shim-new");
    expect(r.out).not.toContain("splice: installed");
    expect([r.jar, r.shim]).toEqual([PREVIOUS_JAR, OLD]);
  });
});
