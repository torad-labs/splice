// install.sh over an install `splice upgrade` moved onto a release. The upgrade leaves share/splice.jar a link to
// releases/<release>/splice.jar (UpgradeActivation.kt), and the daemon it restarts opens its jar through that link. A
// person who then runs install.sh with a jar reporting that same release (a checkout's build, a prebuilt jar) is told "a
// splice that is already running keeps the old build until it restarts", so the daemon's open jar must still hold the
// bytes it opened. The JVM reads each class from its open jar at the offset it recorded on opening it: a jar rewritten
// in place under it fails every class it has not loaded yet.
//
// The sandbox is install-rollback.test.ts's: a PATH farm of the real tools the script needs, java faked, and SPLICE_JAR
// mode, the path a checkout's build also takes once its jar is copied in. The script runs from a copy outside the repo
// so it does not build. The daemon is this test, holding the jar open through the link and reading it at an offset.
import { afterAll, describe, expect, test } from "bun:test";
import { chmodSync, closeSync, mkdirSync, mkdtempSync, openSync, readFileSync, readSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { layout } from "../../gate/src/lib/repo.ts";

const { repoRoot } = layout();
const workspaces: string[] = [];
afterAll(() => {
  for (const dir of workspaces) rmSync(dir, { recursive: true, force: true });
});

const scriptDir = mkdtempSync(join(tmpdir(), "install-running-daemon-script-"));
workspaces.push(scriptDir);
const INSTALLER = join(scriptDir, "install.sh");
writeFileSync(INSTALLER, readFileSync(join(repoRoot, "install.sh")));

const TOOLS = [
  "bash", "env", "sh", "uname", "curl", "grep", "sed", "awk", "mktemp", "cp", "mv", "rm", "ln", "readlink", "install",
  "chmod", "mkdir", "dirname", "cat", "head", "tr", "printf",
];

const RELEASE = "9.9.9";
// Paired with the fake jar's `shim-version` and `version`; install.sh refuses a pair that does not match.
const SHIM = `#!/usr/bin/env node\n// splice-launch\nconst SPLICE_SHIM_VERSION = "shim-test";\nconst SPLICE_GATEWAY_VERSION = "${RELEASE}";\n`;
const OPENED = `the ${RELEASE} jar the running daemon opened\n`;
const REBUILT = `the ${RELEASE} jar built again, other bytes\n`;

function exe(path: string, body: string) {
  writeFileSync(path, body);
  chmodSync(path, 0o755);
}

/** A sandbox whose share dir is the one `splice upgrade` leaves on RELEASE: the release's jar and launcher under
 *  releases/, `current` naming it, the live jar a link into it and the live launcher a real file (UpgradeLayout.kt). */
function upgradedInstall() {
  const dir = mkdtempSync(join(tmpdir(), "install-running-daemon-"));
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
  *" shim-version") echo shim-test ;;
  *" version") echo "splice ${RELEASE}" ;;
  *" install --all") mkdir -p "$SPLICE_BIN_DIR" && ln -sfn "$SPLICE_JAR" "$SPLICE_BIN_DIR/splice" ;;
  *) : ;;
esac
`);
  exe(join(bin, "claude"), "#!/usr/bin/env bash\nexit 0\n");
  const share = join(dir, "share");
  const release = join(share, "releases", RELEASE);
  mkdirSync(release, { recursive: true });
  writeFileSync(join(release, "splice.jar"), OPENED);
  writeFileSync(join(release, "splice-launch"), SHIM);
  symlinkSync(RELEASE, join(share, "releases", "current"));
  symlinkSync(join("releases", RELEASE, "splice.jar"), join(share, "splice.jar"));
  writeFileSync(join(share, "splice-launch"), SHIM);
  writeFileSync(join(share, "managed-install"), "splice upgrade\n");
  const candidate = join(dir, "candidate");
  mkdirSync(candidate);
  writeFileSync(join(candidate, "splice.jar"), REBUILT);
  writeFileSync(join(candidate, "splice-launch"), SHIM);
  return { dir, bin, share, jar: join(candidate, "splice.jar") };
}

/** install.sh as a person runs it with a prebuilt jar, over the sandbox's install. */
async function install(box: ReturnType<typeof upgradedInstall>) {
  const proc = Bun.spawn(["bash", INSTALLER], {
    cwd: box.dir,
    stdin: "ignore",
    stdout: "pipe",
    stderr: "pipe",
    env: { PATH: box.bin, HOME: box.dir, SPLICE_SHARE_DIR: box.share, SPLICE_BIN_DIR: join(box.dir, "bin"), SPLICE_JAR: box.jar },
  });
  const [out, err, code] = await Promise.all([
    new Response(proc.stdout).text(),
    new Response(proc.stderr).text(),
    proc.exited,
  ]);
  return { code, out: out + err };
}

/** What a reader of the open jar finds at offset 0, read by position as the JVM reads an entry. */
function readAtStart(fd: number): string {
  const bytes = Buffer.alloc(4096);
  return bytes.subarray(0, readSync(fd, bytes, 0, bytes.length, 0)).toString("utf8");
}

describe("install.sh over a release `splice upgrade` made current", () => {
  test("a run for that same release leaves the running daemon's jar holding the bytes it opened", async () => {
    const box = upgradedInstall();
    // The daemon runs `java -jar <share>/splice.jar daemon`: it opens the jar through the link and keeps it open.
    const daemonJar = openSync(join(box.share, "splice.jar"), "r");
    try {
      const r = await install(box);
      expect(r.code, r.out).toBe(0);
      expect(readAtStart(daemonJar)).toBe(OPENED);
      // The install still lands: the live jar and the release's kept copy are the new build.
      expect(readFileSync(join(box.share, "splice.jar"), "utf8")).toBe(REBUILT);
      expect(readFileSync(join(box.share, "releases", RELEASE, "splice.jar"), "utf8")).toBe(REBUILT);
    } finally {
      closeSync(daemonJar);
    }
  });
});
