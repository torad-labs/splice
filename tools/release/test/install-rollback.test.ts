// install.sh's rollback says what it did (V4-299). After a failed commit it moved the previous jar and
// shim back without checking the moves: under `set -e` the first failed move ended the script with no
// line of its own, and the EXIT trap then deleted both backups, the only copy of the previous install.
//
// The sandbox is install-provenance.test.ts's: a PATH farm of the real tools the script needs, with
// java faked. Here mv is faked too: it fails the shim's commit, so the rollback runs, and it fails every
// move out of a backup, so the rollback itself fails. SPLICE_JAR mode (a prebuilt jar with its sibling
// shim) needs no release server. The script runs from a copy outside the repo so it does not build.
import { afterAll, describe, expect, test } from "bun:test";
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { layout } from "../../gate/src/lib/repo.ts";

const { repoRoot } = layout();
const workspaces: string[] = [];
afterAll(() => {
  for (const dir of workspaces) rmSync(dir, { recursive: true, force: true });
});

const scriptDir = mkdtempSync(join(tmpdir(), "install-rollback-script-"));
workspaces.push(scriptDir);
const INSTALLER = join(scriptDir, "install.sh");
writeFileSync(INSTALLER, readFileSync(join(repoRoot, "install.sh")));

const TOOLS = [
  "bash", "env", "sh", "uname", "curl", "grep", "sed", "awk", "mktemp", "cp", "rm", "ln", "readlink", "install",
  "chmod", "mkdir", "dirname", "cat", "head", "tr", "printf",
];

function exe(path: string, body: string) {
  writeFileSync(path, body);
  chmodSync(path, 0o755);
}

/** Runs the installer over a previous install whose restore cannot move the backups back. */
async function failedRollback() {
  const dir = mkdtempSync(join(tmpdir(), "install-rollback-"));
  workspaces.push(dir);
  const bin = join(dir, "path");
  mkdirSync(bin);
  for (const tool of TOOLS) {
    const real = Bun.which(tool);
    if (!real) throw new Error(`the installer test needs ${tool} on PATH`);
    symlinkSync(real, join(bin, tool));
  }
  const realMv = Bun.which("mv");
  if (!realMv) throw new Error("the installer test needs mv on PATH");
  exe(join(bin, "mv"), `#!/usr/bin/env bash
for arg in "$@"; do case "$arg" in *.backup.*) echo "mv: refused out of a backup" >&2; exit 1 ;; esac; done
case "\${@: -1}" in */splice-launch) echo "mv: refused the shim commit" >&2; exit 1 ;; esac
exec ${JSON.stringify(realMv)} "$@"
`);
  exe(join(bin, "java"), `#!/usr/bin/env bash
case "$*" in
  -version) echo 'openjdk version "21.0.4" 2024-07-16' >&2 ;;
  *" version") echo "splice 9.9.9" ;;
  *) : ;;
esac
`);
  exe(join(bin, "node"), "#!/usr/bin/env bash\n[ \"$1\" = -v ] && echo v24.0.0\nexit 0\n");
  exe(join(bin, "claude"), "#!/usr/bin/env bash\nexit 0\n");
  const candidate = join(dir, "candidate");
  mkdirSync(candidate);
  writeFileSync(join(candidate, "splice.jar"), "new jar\n");
  writeFileSync(join(candidate, "splice-launch"), "#!/usr/bin/env node\n// new shim\n");
  const share = join(dir, "share");
  mkdirSync(share);
  writeFileSync(join(share, "splice.jar"), "previous jar\n");
  writeFileSync(join(share, "splice-launch"), "previous shim\n");

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
    },
  });
  const [out, err, code] = await Promise.all([
    new Response(proc.stdout).text(),
    new Response(proc.stderr).text(),
    proc.exited,
  ]);
  const backups = readdirSync(share).filter((name) => name.includes(".backup.")).map((name) => join(share, name));
  return { code, out: out + err, share, backups };
}

describe("install.sh rollback (V4-299)", () => {
  test("a rollback whose moves fail says so, keeps the backups and names the moves to make by hand", async () => {
    const r = await failedRollback();

    expect(r.code).not.toBe(0);
    expect(r.backups.length).toBe(2);
    expect(r.out).not.toContain("previous installation restored");
    expect(r.out).toContain("could NOT be restored");
    for (const backup of r.backups) {
      expect(existsSync(backup)).toBe(true);
      expect(r.out).toContain(`mv -f '${backup}'`);
    }
    expect(readFileSync(r.backups.find((b) => b.includes("splice.jar"))!, "utf8")).toBe("previous jar\n");
  });
});
