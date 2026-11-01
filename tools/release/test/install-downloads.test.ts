// Goal (Marcos, Oct 31: "I dont want the release download to be there [GitHub] ... being at Cloudflare makes more
// sense"; then "Have tje download be splice.torad.ai/download"): someone who runs install.sh gets every file of the
// release from splice.torad.ai/download, never from github.com.
// A default install takes the newest stable from /latest, a pinned SPLICE_VERSION takes that version's own folder,
// and a prebuilt jar with no launcher beside it fetches the launcher of its own release from that release's folder.
//
// The network is faked the way `release accept` fakes it: a `curl` that serves the release's files by name and logs
// every URL, so the URLs are the assertion. The release is synthetic, as in install-provenance.test.ts.
import { afterAll, describe, expect, test } from "bun:test";
import { chmodSync, copyFileSync, existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { layout } from "../../gate/src/lib/repo.ts";
import { fakeNetwork, makeSandbox, runInstall, sandboxEnv } from "../src/lib/sandbox.ts";

const { repoRoot } = layout();
const HOST = "https://splice.torad.ai/download";
const cleanup: string[] = [];
afterAll(() => {
  for (const dir of cleanup) rmSync(dir, { recursive: true, force: true });
});

const JAR = "synthetic splice jar bytes\n";
const SHIM = '#!/usr/bin/env node\n// synthetic splice-launch\nconst SPLICE_SHIM_VERSION = "shim-test";\nconst SPLICE_GATEWAY_VERSION = "9.9.9";\n';
const sha = (text: string) => createHash("sha256").update(text).digest("hex");

function exe(path: string, body: string) {
  writeFileSync(path, body);
  chmodSync(path, 0o755);
}

/** The release the fake curl serves: the jar, its launcher, their sums, and the installer itself. */
function release(): string {
  const dir = mkdtempSync(join(tmpdir(), "install-downloads-release-"));
  cleanup.push(dir);
  writeFileSync(join(dir, "splice.jar"), JAR);
  writeFileSync(join(dir, "splice-launch"), SHIM);
  writeFileSync(join(dir, "sha256sums.txt"), `${sha(JAR)}  splice.jar\n${sha(SHIM)}  splice-launch\n`);
  copyFileSync(join(repoRoot, "install.sh"), join(dir, "install.sh"));
  return dir;
}

/** java, node and claude as the installer needs them, ahead of the machine's own. */
function fakes(): string {
  const dir = mkdtempSync(join(tmpdir(), "install-downloads-fakes-"));
  cleanup.push(dir);
  exe(join(dir, "java"), `#!/usr/bin/env bash
case "$*" in
  -version) echo 'openjdk version "21.0.4" 2024-07-16' >&2 ;;
  *" shim-version") echo shim-test ;;
  *" version") echo "splice 9.9.9" ;;
  *" install --all") mkdir -p "$SPLICE_BIN_DIR" && ln -sfn "$SPLICE_JAR" "$SPLICE_BIN_DIR/splice" ;;
  *) : ;;
esac
`);
  exe(join(dir, "node"), "#!/usr/bin/env bash\n[ \"$1\" = -v ] && echo v24.0.0\nexit 0\n");
  exe(join(dir, "claude"), "#!/usr/bin/env bash\nexit 0\n");
  return dir;
}

/** One install with the fake network; the URLs curl was asked for, in order. */
function install(env: Record<string, string>): { urls: string[]; output: string; ok: boolean } {
  const assets = release();
  const network = fakeNetwork();
  cleanup.push(network.dir);
  const box = makeSandbox("install-downloads-");
  cleanup.push(box.dir);
  const result = runInstall(assets, box, {
    ...sandboxEnv(box),
    SPLICE_FAKE_RELEASE_ASSETS: assets,
    SPLICE_FAKE_CURL_LOG: network.curlLog,
    SPLICE_FAKE_GH_LOG: network.ghLog,
    SPLICE_RELEASE_BASE_URL: "",
    PATH: `${network.dir}:${fakes()}:${box.bin}:${Bun.env.PATH ?? ""}`,
    ...env,
  });
  const urls = existsSync(network.curlLog) ? readFileSync(network.curlLog, "utf8").split("\n").filter(Boolean) : [];
  return { urls, output: result.output, ok: result.ok };
}

describe("install.sh downloads from splice.torad.ai", () => {
  test("a default install takes the newest stable release from /latest", () => {
    const run = install({});
    expect(run.ok, run.output).toBe(true);
    expect(run.urls).toEqual([`${HOST}/latest/splice.jar`, `${HOST}/latest/sha256sums.txt`, `${HOST}/latest/splice-launch`]);
  });

  test("a pinned SPLICE_VERSION takes every file from that version's folder", () => {
    for (const pinned of ["v9.9.9", "v9.8.7-beta.6"]) {
      const run = install({ SPLICE_VERSION: pinned });
      expect(run.ok, run.output).toBe(true);
      expect(run.urls).toEqual([`${HOST}/${pinned}/splice.jar`, `${HOST}/${pinned}/sha256sums.txt`, `${HOST}/${pinned}/splice-launch`]);
    }
  });

  test("a prebuilt jar with no launcher beside it fetches its own release's launcher", () => {
    const lone = mkdtempSync(join(tmpdir(), "install-downloads-lone-"));
    cleanup.push(lone);
    writeFileSync(join(lone, "splice.jar"), JAR);
    const run = install({ SPLICE_JAR: join(lone, "splice.jar") });
    expect(run.ok, run.output).toBe(true);
    expect(run.urls).toEqual([`${HOST}/v9.9.9/sha256sums.txt`, `${HOST}/v9.9.9/splice-launch`]);
  });

  test("no install asks github.com for anything", () => {
    for (const env of [{}, { SPLICE_VERSION: "v9.9.9" }] as Record<string, string>[]) {
      const run = install(env);
      expect(run.urls.filter((url) => url.includes("github.com"))).toEqual([]);
    }
  });
});
