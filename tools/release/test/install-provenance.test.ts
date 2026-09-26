// install.sh in release mode, against an https-style (non file://) release base served on loopback:
// the install must complete with no gh and with a gh that is not signed in (V4-217), while the
// sha256 check against sha256sums.txt refuses a tampered asset in every case, and a signed-in gh
// still verifies the attestation and still refuses a failed one.
//
// Everything the installer runs is faked on PATH the way accept.test.ts fakes java: the release is
// synthetic, and PATH is a farm of symlinks to the real tools the script needs, minus gh, so "no gh"
// holds on a machine that has one. A gh stub stands in for the two gh states that exist.
import { afterAll, describe, expect, test } from "bun:test";
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { layout } from "../../gate/src/lib/repo.ts";

const { repoRoot } = layout();
const workspaces: string[] = [];
afterAll(() => {
  for (const dir of workspaces) rmSync(dir, { recursive: true, force: true });
});

// install.sh takes its own directory as a checkout when settings.gradle.kts sits next to it, and
// then builds from source. Release mode is the mode under test, so it runs from a copy outside
// the repo, the way `curl … | bash` runs it.
const scriptDir = mkdtempSync(join(tmpdir(), "install-provenance-script-"));
workspaces.push(scriptDir);
const INSTALLER = join(scriptDir, "install.sh");
writeFileSync(INSTALLER, readFileSync(join(repoRoot, "install.sh")));

// The real tools install.sh calls in release mode. A missing one fails the setup loudly.
const TOOLS = [
  "bash", "env", "sh", "uname", "curl", "sha256sum", "awk", "grep", "sed", "mktemp", "cp", "mv", "rm",
  "ln", "readlink", "install", "chmod", "mkdir", "dirname", "cat", "head", "tr", "timeout", "printf",
];

const JAR = "synthetic splice jar bytes\n";
const SHIM = "#!/usr/bin/env node\n// synthetic splice-launch\n";
const sha = (text: string) => createHash("sha256").update(text).digest("hex");

function exe(path: string, body: string) {
  writeFileSync(path, body);
  chmodSync(path, 0o755);
}

type Gh = "absent" | "signed-out" | "signed-in";

/** A sandbox: HOME, the share and bin dirs, and a PATH with the fakes and the tool farm. */
function sandbox(gh: Gh, verifyExit = 0) {
  const dir = mkdtempSync(join(tmpdir(), "install-provenance-"));
  workspaces.push(dir);
  const bin = join(dir, "path");
  mkdirSync(bin);
  for (const tool of TOOLS) {
    const real = Bun.which(tool);
    if (!real) throw new Error(`the installer test needs ${tool} on PATH`);
    symlinkSync(real, join(bin, tool));
  }
  const share = join(dir, "share");
  const binDir = join(dir, "bin");
  // java: 21 for the preflight, the jar's version, and an `install --all` that links `splice`.
  exe(join(bin, "java"), `#!/usr/bin/env bash
case "$*" in
  -version) echo 'openjdk version "21.0.4" 2024-07-16' >&2 ;;
  *" version") echo "splice 9.9.9" ;;
  *" install --all") mkdir -p "$SPLICE_BIN_DIR" && ln -sfn "$SPLICE_JAR" "$SPLICE_BIN_DIR/splice" ;;
  *) : ;;
esac
`);
  exe(join(bin, "node"), "#!/usr/bin/env bash\n[ \"$1\" = -v ] && echo v24.0.0\nexit 0\n");
  exe(join(bin, "claude"), "#!/usr/bin/env bash\nexit 0\n");
  const ghLog = join(dir, "gh.log");
  if (gh !== "absent") {
    exe(join(bin, "gh"), `#!/usr/bin/env bash
echo "$*" >> ${JSON.stringify(ghLog)}
case "$1 $2" in
  "auth status") ${gh === "signed-in" ? "exit 0" : 'echo "You are not logged into any GitHub hosts." >&2; exit 1'} ;;
  "attestation verify") exit ${verifyExit} ;;
esac
exit 0
`);
  }
  return { dir, bin, share, binDir, ghLog };
}

/** Serves a release (jar, shim, sums) on loopback; `sums` can be swapped for a tampered one. */
function release(sums = `${sha(JAR)}  splice.jar\n${sha(SHIM)}  splice-launch\n`) {
  const files: Record<string, string> = { "splice.jar": JAR, "splice-launch": SHIM, "sha256sums.txt": sums };
  const server = Bun.serve({
    hostname: "127.0.0.1",
    port: 0,
    fetch(req) {
      const name = new URL(req.url).pathname.slice(1);
      return name in files ? new Response(files[name]) : new Response("not found", { status: 404 });
    },
  });
  return { base: `http://127.0.0.1:${server.port}`, stop: () => server.stop(true) };
}

async function install(gh: Gh, options: { verifyExit?: number; sums?: string } = {}) {
  const box = sandbox(gh, options.verifyExit ?? 0);
  const rel = release(options.sums);
  try {
    const proc = Bun.spawn(["bash", INSTALLER], {
      cwd: box.dir,
      stdin: "ignore",
      stdout: "pipe",
      stderr: "pipe",
      env: {
        PATH: box.bin,
        HOME: box.dir,
        SPLICE_SHARE_DIR: box.share,
        SPLICE_BIN_DIR: box.binDir,
        SPLICE_RELEASE_BASE_URL: rel.base,
      },
    });
    const [out, err, code] = await Promise.all([
      new Response(proc.stdout).text(),
      new Response(proc.stderr).text(),
      proc.exited,
    ]);
    const ghCalls = existsSync(box.ghLog) ? readFileSync(box.ghLog, "utf8") : "";
    return { code, out: out + err, ghCalls, jar: join(box.share, "splice.jar"), shim: join(box.share, "splice-launch") };
  } finally {
    rel.stop();
  }
}

describe("install.sh release provenance (V4-217)", () => {
  const later = (r: { jar: string; shim: string }) => [
    `gh attestation verify ${r.jar} --repo torad-labs/splice`,
    `gh attestation verify ${r.shim} --repo torad-labs/splice`,
  ];

  test("with no gh, the install completes and prints the command that verifies provenance later", async () => {
    const r = await install("absent");
    expect(r.out).not.toContain("GitHub CLI (gh) is required");
    expect(r.code).toBe(0);
    expect(readFileSync(r.jar, "utf8")).toBe(JAR);
    expect(r.out).toContain("gh is not installed");
    for (const line of later(r)) expect(r.out).toContain(line);
  });

  test("with gh signed out, the install completes without calling attestation verify", async () => {
    const r = await install("signed-out");
    expect(r.code).toBe(0);
    expect(r.ghCalls).not.toContain("attestation verify");
    expect(r.out).toContain("gh is not signed in");
    for (const line of later(r)) expect(r.out).toContain(line);
  });

  test("with gh signed in, both assets' attestations are verified and nothing is deferred", async () => {
    const r = await install("signed-in");
    expect(r.code).toBe(0);
    expect(r.ghCalls).toMatch(/attestation verify \S*splice\.jar\S* --repo torad-labs\/splice/);
    expect(r.ghCalls).toMatch(/attestation verify \S*splice-launch\S* --repo torad-labs\/splice/);
    expect(r.out).toContain("splice.jar attestation: OK");
    expect(r.out).not.toContain("verify it later");
  });

  test("with gh signed in, a failed attestation still refuses and installs nothing", async () => {
    const r = await install("signed-in", { verifyExit: 1 });
    expect(r.code).not.toBe(0);
    expect(r.out).toContain("attestation verification FAILED for splice.jar");
    expect(existsSync(r.jar)).toBe(false);
  });

  for (const gh of ["absent", "signed-out", "signed-in"] as const) {
    test(`a tampered sha256sums.txt refuses with gh ${gh}`, async () => {
      const r = await install(gh, { sums: `${sha("other bytes")}  splice.jar\n${sha(SHIM)}  splice-launch\n` });
      expect(r.code).not.toBe(0);
      expect(r.out).toContain("sha256 verification FAILED for splice.jar");
      expect(existsSync(r.jar)).toBe(false);
    });
  }
});
