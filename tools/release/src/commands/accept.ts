// `release accept [distDir]` — THE ACCEPTANCE GATE OVER A STAGED BUNDLE (checks/release/accept.sh
// until PR 6). Every check and every message of that script is here, in its order.
//
// What it answers, in one run: the bundle is complete and non-empty; its checksums are its own; the
// jar carries the same sidecars it ships beside; the launcher and the jar agree on a version; and
// install.sh, driven against these exact bytes in a throwaway HOME, installs, refuses a corrupt or
// incomplete bundle, verifies build provenance for both downloaded artifacts on every channel, and
// puts the previous install back when the new one fails after it committed.
//
// DR-25 redo (codex catch, 2026-08-31): `-f` accepted zero-byte artifacts whose checksums were
// computed AFTER truncation — `sha256sum -c` cannot defend a staged-empty file. Non-empty is the
// floor for every published artifact, and the asset SET now comes from the staged manifest
// (src/lib/dist.ts), not from a second hand copy of stage's list.
import {
  chmodSync,
  copyFileSync,
  existsSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { createHash } from "node:crypto";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { SUMS, readManifest } from "../lib/dist.ts";
import { stagedGatewayVersion } from "../lib/shim.ts";
import {
  assertFailedWithoutSuccess,
  breakAttestation,
  danglingJava,
  failingInstallJava,
  fakeNetwork,
  makeSandbox,
  runInstall,
  runInstalled,
  sandboxEnv,
} from "../lib/sandbox.ts";
import { zipEntry } from "../lib/zip.ts";

export const usage =
  "accept [distDir] [--version X]       accept a staged release bundle: assets, checksums, the jar's sidecars, and install.sh end to end";

/** The repository, for the one check that compares the packaged dashboard to the built bundle. */
const REPO_ROOT_MARKER = "console/dist/index.html";

/** The sidecars the fat jar must carry byte-identically, staged name -> archive entry. */
const EMBEDDED: readonly (readonly [string, string])[] = [
  ["LICENSE", "META-INF/LICENSE"],
  ["THIRD_PARTY_NOTICES.md", "META-INF/THIRD_PARTY_NOTICES.md"],
  ["THIRD_PARTY_LICENSES.txt", "META-INF/THIRD_PARTY_LICENSES.txt"],
  ["PROVENANCE.md", "META-INF/PROVENANCE.md"],
  ["bom.cdx.json", "META-INF/bom.cdx.json"],
  ["dependency-licenses.json", "META-INF/dependency-licenses.json"],
];

const RELEASE_REPO = "torad-labs/splice";

function fail(message: string): number {
  console.error(message);
  return 1;
}

function sha256(path: string): string {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

/** `java -jar <jar> version`, stdout only, as the command substitution captured it. */
function jarVersionOf(jar: string): string {
  return Bun.spawnSync(["java", "-jar", jar, "version"], { stdout: "pipe", stderr: "pipe" }).stdout.toString().trim();
}

export async function accept(argv: readonly string[], repoRoot: string): Promise<number> {
  let dist: string | undefined;
  let expected = Bun.env.SPLICE_EXPECTED_VERSION ?? "";
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]!;
    if (arg === "--version") {
      const value = argv[++i];
      if (!value) return fail("release accept: --version needs the version it expects");
      expected = value;
    } else if (arg.startsWith("-")) {
      return fail(`release accept: unknown argument ${arg}`);
    } else if (dist === undefined) {
      dist = arg;
    } else {
      return fail(`release accept: one dist directory, got a second: ${arg}`);
    }
  }
  const distDir = dist ?? join(repoRoot, "dist");

  // ── the bundle itself ────────────────────────────────────────────────────────────────────────
  // The manifest is read FIRST because it carries the asset set (DR-25); its own missing/EMPTY
  // refusal is the same line the asset loop would have printed for it.
  const manifest = readManifest(distDir);
  if (manifest instanceof Error) return fail(manifest.message);
  for (const asset of [...manifest.assets, SUMS]) {
    const path = join(distDir, asset);
    let size = -1;
    try {
      size = statSync(path).size;
    } catch {
      /* missing reports as EMPTY does */
    }
    if (size <= 0) return fail(`release accept: missing or EMPTY ${path}`);
  }
  // Semantic floor for the one pure-prose artifact: PROVENANCE must actually be the provenance
  // document, not any non-empty placeholder.
  const provenance = readFileSync(join(distDir, "PROVENANCE.md"), "utf8").split("\n", 1)[0] ?? "";
  if (!provenance.startsWith("# Provenance")) {
    return fail("release accept: PROVENANCE.md does not open with the provenance header");
  }

  const jar = join(distDir, "splice.jar");
  const jarVersion = jarVersionOf(jar).replace(/^splice /, "");
  const shimGatewayVersion = stagedGatewayVersion(join(distDir, "splice-launch"));
  if (shimGatewayVersion !== jarVersion) {
    return fail(`release accept: launcher expects gateway ${shimGatewayVersion} but jar is ${jarVersion}`);
  }

  // `sha256sum -c sha256sums.txt`, and then the leg that line cannot do: the manifest's names are
  // exactly what is STAGED. The old check counted lines against a hand copy of the asset list — a
  // denominator taken from the list being checked (§24). The directory is the denominator now.
  const mismatched = manifest.assets.filter((asset) => sha256(join(distDir, asset)) !== manifest.sums.get(asset));
  if (mismatched.length > 0) {
    for (const asset of mismatched) console.error(`${asset}: FAILED`);
    return fail(`sha256sum: WARNING: ${mismatched.length} computed checksum(s) did NOT match`);
  }
  const staged = new Set(manifest.assets);
  const onDisk = new Set(readdirSync(distDir));
  onDisk.delete(SUMS);
  if (staged.size !== onDisk.size || [...onDisk].some((name) => !staged.has(name))) {
    return fail("release accept: sha256sums.txt does not cover the exact asset set");
  }

  // ── the compliance documents, and the jar's own copies of them ───────────────────────────────
  const bom = JSON.parse(readFileSync(join(distDir, "bom.cdx.json"), "utf8")) as Record<string, unknown>;
  const licenses = JSON.parse(readFileSync(join(distDir, "dependency-licenses.json"), "utf8")) as Record<string, unknown>;
  if (bom.bomFormat !== "CycloneDX" || !Array.isArray(bom.components) || bom.components.length === 0) {
    return fail("release accept: SBOM is not a non-empty CycloneDX document");
  }
  // Mirror of actions/attest's checkIsCycloneDX: publish rejects a BOM lacking ANY of these, so the
  // acceptance gate must too (v0.1.1's first tag run failed only at publish time).
  if (!bom.bomFormat || !bom.serialNumber || !bom.specVersion) {
    return fail("release accept: SBOM would fail actions/attest detection (needs bomFormat + serialNumber + specVersion)");
  }
  if (!String(bom.serialNumber ?? "").startsWith("urn:uuid:")) {
    return fail("release accept: SBOM serialNumber must be a urn:uuid (deterministic, content-derived)");
  }
  if (!Array.isArray(licenses.dependencies) || licenses.dependencies.length === 0) {
    return fail("release accept: dependency-license inventory is empty");
  }
  for (const [sidecar, entry] of EMBEDDED) {
    let packaged: Buffer;
    try {
      packaged = zipEntry(jar, entry);
    } catch (error) {
      return fail(`release accept: ${sidecar} differs from ${entry} in splice.jar (${String(error)})`);
    }
    if (!packaged.equals(readFileSync(join(distDir, sidecar)))) {
      return fail(`release accept: ${sidecar} differs from ${entry} in splice.jar`);
    }
  }
  // PR 4: the bundle is :console:bundle's output; the jar carries what that build produced.
  if (!zipEntry(jar, "webui/index.html").equals(readFileSync(join(repoRoot, REPO_ROOT_MARKER)))) {
    return fail("release accept: packaged dashboard differs from the built console bundle");
  }

  // ── install.sh, against these exact bytes ────────────────────────────────────────────────────
  const first = makeSandbox();
  const localEnv = {
    ...sandboxEnv(first),
    SPLICE_RELEASE_BASE_URL: `file://${distDir}`,
    PATH: `${first.bin}:${Bun.env.PATH ?? ""}`,
  };
  const installed = runInstall(distDir, first, localEnv);
  if (!installed.ok) return fail(installed.output);
  const command = join(first.bin, "splice");
  const isCommand = (() => {
    try {
      return lstatSync(command).isSymbolicLink() && existsSync(command);
    } catch {
      return false;
    }
  })();
  if (!isCommand) return fail("release accept: installed splice command is missing or dangling");
  const version = runInstalled(first, ["version"], { ...sandboxEnv(first), SPLICE_JAR: join(first.share, "splice.jar") });
  if (!version.startsWith("splice ")) return fail(`release accept: unexpected version output: ${version}`);
  if (expected !== "" && version !== `splice ${expected}`) {
    return fail(`release accept: expected 'splice ${expected}', got '${version}'`);
  }
  rmSync(first.dir, { recursive: true, force: true });

  // Exercise the genuine remote-release path without network access. The fake downloader serves the
  // staged assets, while the fake GitHub CLI records which candidates were attested.
  const network = fakeNetwork();
  const remote = makeSandbox();
  const fakeEnv = (sandbox: ReturnType<typeof makeSandbox>) => ({
    ...sandboxEnv(sandbox),
    SPLICE_FAKE_RELEASE_ASSETS: distDir,
    SPLICE_FAKE_CURL_LOG: network.curlLog,
    SPLICE_FAKE_GH_LOG: network.ghLog,
    PATH: `${network.dir}:${sandbox.bin}:${Bun.env.PATH ?? ""}`,
  });
  const attestations = (): number =>
    (existsSync(network.ghLog) ? readFileSync(network.ghLog, "utf8") : "")
      .split("\n")
      .filter((line) => line.startsWith("attestation verify ")).length;
  const resetLogs = () => {
    writeFileSync(network.ghLog, "");
    writeFileSync(network.curlLog, "");
  };
  const urls = (): string[] =>
    (existsSync(network.curlLog) ? readFileSync(network.curlLog, "utf8") : "").split("\n").filter((line) => line !== "");

  resetLogs();
  const remoteRun = runInstall(distDir, remote, {
    ...fakeEnv(remote),
    SPLICE_RELEASE_BASE_URL: "https://example.invalid/splice-release",
  });
  if (!remoteRun.ok) return fail(remoteRun.output);
  if (attestations() !== 2) {
    return fail(`release accept: remote install did not attest both release artifacts\n${readFileSync(network.ghLog, "utf8")}`);
  }
  for (const asset of ["splice.jar", "splice-launch"]) {
    if (!remoteRun.output.includes(`${asset} attestation: OK`)) {
      return fail(`release accept: remote install missing ${asset} attestation OK`);
    }
  }

  // With no channel override, every download must stay on GitHub's stable-only `latest` release.
  resetLogs();
  const stable = makeSandbox();
  const stableRun = runInstall(distDir, stable, fakeEnv(stable));
  if (!stableRun.ok) return fail(stableRun.output);
  const stableBase = `https://github.com/${RELEASE_REPO}/releases/latest/download`;
  const expectedStable = [`${stableBase}/splice.jar`, `${stableBase}/sha256sums.txt`, `${stableBase}/splice-launch`];
  if (urls().join("\n") !== expectedStable.join("\n")) {
    return fail("release accept: default install did not use the exact stable latest URLs");
  }
  if (attestations() !== 2) {
    return fail(`release accept: stable install did not attest both release artifacts\n${readFileSync(network.ghLog, "utf8")}`);
  }
  for (const asset of ["splice.jar", "splice-launch"]) {
    if (!stableRun.output.includes(`${asset} attestation: OK`)) {
      return fail(`release accept: stable install missing ${asset} attestation OK`);
    }
  }
  rmSync(stable.dir, { recursive: true, force: true });

  // Stable and prerelease pins must send every download to their exact tag.
  for (const pinned of [`v${jarVersion}`, "v9.8.7-beta.6"]) {
    resetLogs();
    const versioned = makeSandbox();
    const run = runInstall(distDir, versioned, { ...fakeEnv(versioned), SPLICE_VERSION: pinned });
    if (!run.ok) return fail(run.output);
    const base = `https://github.com/${RELEASE_REPO}/releases/download/${pinned}`;
    const expectedVersioned = [`${base}/splice.jar`, `${base}/sha256sums.txt`, `${base}/splice-launch`];
    if (urls().join("\n") !== expectedVersioned.join("\n")) {
      return fail(`release accept: ${pinned} install did not use the exact version-pinned URLs`);
    }
    if (attestations() !== 2) {
      return fail(`release accept: ${pinned} install did not attest both release artifacts\n${readFileSync(network.ghLog, "utf8")}`);
    }
    for (const asset of ["splice.jar", "splice-launch"]) {
      if (!run.output.includes(`${asset} attestation: OK`)) {
        return fail(`release accept: ${pinned} install missing ${asset} attestation OK`);
      }
    }
    rmSync(versioned.dir, { recursive: true, force: true });
  }

  // A failed provenance check must abort before the candidate artifacts become live.
  breakAttestation(network);
  writeFileSync(join(remote.share, "splice.jar"), "previous jar\n");
  writeFileSync(join(remote.share, "splice-launch"), "previous shim\n");
  const refused = runInstall(distDir, remote, {
    ...sandboxEnv(remote),
    SPLICE_RELEASE_BASE_URL: "https://example.invalid/splice-release",
    SPLICE_FAKE_RELEASE_ASSETS: distDir,
    PATH: `${network.dir}:${remote.bin}:${Bun.env.PATH ?? ""}`,
  });
  if (refused.ok) return fail("release accept: failed provenance verification unexpectedly installed");
  // The shell asserted these with a bare `[ ]` under `set -e`, which exits 1 in silence; the reason
  // is named here, because an unverified artifact going live is the failure this arm exists for.
  if (readFileSync(join(remote.share, "splice.jar"), "utf8") !== "previous jar\n") {
    return fail("release accept: failed provenance verification replaced the live jar");
  }
  if (readFileSync(join(remote.share, "splice-launch"), "utf8") !== "previous shim\n") {
    return fail("release accept: failed provenance verification replaced the live shim");
  }
  rmSync(network.dir, { recursive: true, force: true });
  rmSync(remote.dir, { recursive: true, force: true });

  // ── bundles that must NOT install ────────────────────────────────────────────────────────────
  const corrupt = mkdtempSync(join(tmpdir(), "release-accept-corrupt-"));
  copyBundle(distDir, corrupt);
  writeFileSync(join(corrupt, "splice.jar"), "\ncorrupt\n", { flag: "a" });
  const corruptProblem = assertFailedWithoutSuccess(corrupt, "checksum mismatch");
  rmSync(corrupt, { recursive: true, force: true });
  if (corruptProblem) return fail(corruptProblem);

  const missingShim = mkdtempSync(join(tmpdir(), "release-accept-noshim-"));
  copyBundle(distDir, missingShim);
  rmSync(join(missingShim, "splice-launch"));
  const missingProblem = assertFailedWithoutSuccess(missingShim, "missing launcher");
  rmSync(missingShim, { recursive: true, force: true });
  if (missingProblem) return fail(missingProblem);

  // The two java stubs below only need to be FIRST on PATH; the bundle they install from is the
  // staged one (the script copied dist/ beside each stub, which nothing then read).
  const dangling = mkdtempSync(join(tmpdir(), "release-accept-dangling-"));
  danglingJava(dangling);
  const danglingSandbox = makeSandbox();
  const danglingRun = runInstall(distDir, danglingSandbox, {
    ...sandboxEnv(danglingSandbox),
    SPLICE_RELEASE_BASE_URL: `file://${distDir}`,
    PATH: `${dangling}:${Bun.env.PATH ?? ""}`,
  });
  rmSync(dangling, { recursive: true, force: true });
  rmSync(danglingSandbox.dir, { recursive: true, force: true });
  if (danglingRun.ok) return fail(`release accept: dangling command unexpectedly succeeded\n${danglingRun.output}`);
  if (danglingRun.output.includes("splice: installed")) {
    return fail(`release accept: dangling command printed success\n${danglingRun.output}`);
  }

  const rollback = mkdtempSync(join(tmpdir(), "release-accept-rollback-"));
  failingInstallJava(rollback);
  const rollbackSandbox = makeSandbox();
  writeFileSync(join(rollbackSandbox.share, "splice.jar"), "previous jar\n");
  writeFileSync(join(rollbackSandbox.share, "splice-launch"), "previous shim\n");
  const rollbackRun = runInstall(distDir, rollbackSandbox, {
    ...sandboxEnv(rollbackSandbox),
    SPLICE_RELEASE_BASE_URL: `file://${distDir}`,
    PATH: `${rollback}:${Bun.env.PATH ?? ""}`,
  });
  rmSync(rollback, { recursive: true, force: true });
  if (rollbackRun.ok) {
    return fail(`release accept: post-commit install failure unexpectedly succeeded\n${rollbackRun.output}`);
  }
  const restoredJar = readFileSync(join(rollbackSandbox.share, "splice.jar"), "utf8");
  const restoredShim = readFileSync(join(rollbackSandbox.share, "splice-launch"), "utf8");
  rmSync(rollbackSandbox.dir, { recursive: true, force: true });
  if (restoredJar !== "previous jar\n") return fail("release accept: failed install did not restore previous jar");
  if (restoredShim !== "previous shim\n") return fail("release accept: failed install did not restore previous shim");

  console.log(`release accept: OK (${version})`);
  return 0;
}

/** `cp -a "$DIST/." "$target/"` for a flat bundle: every staged file, modes included. */
function copyBundle(distDir: string, target: string): void {
  mkdirSync(target, { recursive: true });
  for (const name of readdirSync(distDir)) {
    copyFileSync(join(distDir, name), join(target, name));
    chmodSync(join(target, name), statSync(join(distDir, name)).mode & 0o7777);
  }
}
