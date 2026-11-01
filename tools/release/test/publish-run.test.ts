// Goal (review round 9 on PR #247, taken as a suggestion): `release publish <version> --run <id>` puts a release.yml
// run's bundle on splice.torad.ai/download only when every file sha256sums.txt names carries that workflow's build
// provenance attestation. A bundle with one file the workflow did not attest is refused before anything is uploaded.
//
// The command runs as a maintainer runs it, `bun tools/release publish 0.4.0 --run 123`. gh is GitHub's network, so a
// stand-in on its PATH plays it: `run download` hands over the bundle, and `attestation
// verify` refuses one file. secret-tool is stood in for too and holds nothing, so a refusal that came too late could
// never reach the real bucket.
import { afterAll, describe, expect, test } from "bun:test";
import { chmodSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { tmpdir } from "node:os";
import { join } from "node:path";

const REPO = join(import.meta.dir, "..", "..", "..");

const root = mkdtempSync(join(tmpdir(), "publish-run-"));
afterAll(() => rmSync(root, { recursive: true, force: true }));

function bundle(): string {
  const dir = join(root, "bundle");
  mkdirSync(dir, { recursive: true });
  const files: Record<string, string> = {
    "splice.jar": "jar 0.4.0",
    "splice-launch": 'SPLICE_GATEWAY_VERSION="0.4.0"\n',
    "install.sh": "installer 0.4.0",
  };
  for (const [name, body] of Object.entries(files)) writeFileSync(join(dir, name), body);
  const sha = (s: string) => createHash("sha256").update(s).digest("hex");
  writeFileSync(join(dir, "sha256sums.txt"), `${Object.entries(files).map(([n, b]) => `${sha(b)}  ${n}`).join("\n")}\n`);
  return dir;
}

function standIns(bundleDir: string, unattested: string): string {
  const bin = join(root, "bin");
  mkdirSync(bin, { recursive: true });
  const gh = [
    "#!/usr/bin/env bash",
    'case "$1 $2" in',
    `  "run download") for last; do :; done; cp ${bundleDir}/* "$last"/ ;;`,
    `  "attestation verify") if [ "$(basename "$3")" = "${unattested}" ]; then echo "no attestation found" >&2; exit 1; fi ;;`,
    '  *) echo "gh stand-in: $*" >&2; exit 1 ;;',
    "esac",
  ].join("\n");
  writeFileSync(join(bin, "gh"), `${gh}\n`);
  writeFileSync(join(bin, "secret-tool"), "#!/usr/bin/env bash\nexit 1\n");
  chmodSync(join(bin, "gh"), 0o755);
  chmodSync(join(bin, "secret-tool"), 0o755);
  return bin;
}

describe("release publish --run", () => {
  test("a run whose bundle has a file the workflow did not attest is refused before any upload", async () => {
    const bin = standIns(bundle(), "splice.jar");
    const run = Bun.spawnSync([process.execPath, "tools/release", "publish", "0.4.0", "--run", "123"], {
      cwd: REPO,
      env: { ...process.env, PATH: `${bin}:${process.env.PATH}` },
      stdin: "ignore",
      stdout: "pipe",
      stderr: "pipe",
    });
    expect(run.exitCode).toBe(1);
    const message = run.stderr.toString();
    expect(message).toContain("splice.jar");
    expect(message).toContain("attestation");
    expect(message).toContain("nothing was published");
    expect(message).not.toContain("keyring");
  });
});
