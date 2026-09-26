// THE INSTALL SANDBOX — install.sh driven against the staged bundle, in a throwaway HOME, with the
// network faked (checks/release/accept.sh:83-351 until PR 6).
//
// Two things here are the whole point and are kept exactly:
//   - the installer is run as `bash -s < install.sh` from a work directory, with every path it
//     resolves (HOME, share, bin, java's user.home) inside the sandbox — an acceptance run must
//     never touch the operator's own install;
//   - the "network" is a stub `curl` that serves the STAGED assets and logs every URL, and a stub
//     `gh` that logs every attestation call. The URLs are the assertion: a default install must go
//     to GitHub's stable `latest` and a pinned one to its exact tag, and neither may skip an
//     attestation. The stubs are Bun scripts rather than shell (PR 6: no new shell in the tree);
//     they take the same argv positions curl and gh are called with.
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

export interface Sandbox {
  readonly dir: string;
  readonly home: string;
  readonly share: string;
  readonly bin: string;
  readonly work: string;
}

export interface InstallResult {
  readonly ok: boolean;
  readonly code: number;
  /** stdout and stderr together, as `>output 2>&1` produced */
  readonly output: string;
}

export function makeSandbox(prefix = "release-accept-"): Sandbox {
  const dir = mkdtempSync(join(tmpdir(), prefix));
  const sandbox = { dir, home: join(dir, "home"), share: join(dir, "share"), bin: join(dir, "bin"), work: join(dir, "work") };
  for (const path of [sandbox.home, sandbox.share, sandbox.bin, sandbox.work]) mkdirSync(path, { recursive: true });
  return sandbox;
}

/** An executable Bun script at `path`. `#!<bun>` by absolute path: the stub must resolve even when
 *  the caller's PATH is the sandbox's own bin directory. */
export function writeStub(path: string, body: string): void {
  writeFileSync(path, `#!${process.execPath}\n${body}`);
  chmodSync(path, 0o755);
}

/** The environment every install scenario shares: the sandbox IS the machine. */
export function sandboxEnv(sandbox: Sandbox): Record<string, string> {
  return {
    HOME: sandbox.home,
    JAVA_TOOL_OPTIONS: `-Duser.home=${sandbox.home}`,
    SPLICE_SHARE_DIR: sandbox.share,
    SPLICE_BIN_DIR: sandbox.bin,
  };
}

/** `bash -s < <assets>/install.sh` from the sandbox's work directory, output merged. */
export function runInstall(assets: string, sandbox: Sandbox, env: Record<string, string>): InstallResult {
  const script = readFileSync(join(assets, "install.sh"));
  const proc = Bun.spawnSync(["bash", "-s"], {
    cwd: sandbox.work,
    env: { ...Bun.env, ...env },
    stdin: new Uint8Array(script),
    stdout: "pipe",
    stderr: "pipe",
  });
  const output = `${proc.stdout.toString()}${proc.stderr.toString()}`;
  return { ok: proc.exitCode === 0, code: proc.exitCode ?? 1, output };
}

/** The installed command, run for its stdout alone — `"$sandbox/bin/splice" version`. */
export function runInstalled(sandbox: Sandbox, argv: readonly string[], env: Record<string, string>): string {
  const proc = Bun.spawnSync([join(sandbox.bin, "splice"), ...argv], {
    env: { ...Bun.env, ...env },
    stdout: "pipe",
    stderr: "pipe",
  });
  return proc.stdout.toString().trim();
}

/**
 * A bundle that MUST NOT install: the run has to fail AND must not have printed success. A failure
 * that still says "splice: installed" is the shape the operator acts on, so it is checked
 * separately from the exit code.
 */
export function assertFailedWithoutSuccess(assets: string, label: string): string | null {
  const sandbox = makeSandbox();
  const result = runInstall(assets, sandbox, { ...sandboxEnv(sandbox), SPLICE_RELEASE_BASE_URL: `file://${assets}`, PATH: `${sandbox.bin}:${Bun.env.PATH ?? ""}` });
  if (result.ok) return `release accept: ${label} unexpectedly succeeded\n${result.output}`;
  if (result.output.includes("splice: installed")) {
    return `release accept: ${label} printed success while failing\n${result.output}`;
  }
  return null;
}

/** The fake network: a `curl` that serves the staged assets and logs every URL, and a `gh` that
 *  logs every call. Returns the directory to put FIRST on PATH, plus the two log paths. */
export interface FakeNetwork {
  readonly dir: string;
  readonly ghLog: string;
  readonly curlLog: string;
}

export function fakeNetwork(): FakeNetwork {
  const dir = mkdtempSync(join(tmpdir(), "release-accept-net-"));
  writeStub(
    join(dir, "curl"),
    // curl is called as `curl -fsSL <url> -o <dest>`: the url is $2 and the destination $4.
    'import { appendFileSync, copyFileSync } from "node:fs";\n' +
      'import { basename, join } from "node:path";\n' +
      "const argv = process.argv.slice(2);\n" +
      "const url = argv[1] ?? \"\";\n" +
      "const dest = argv[3] ?? \"\";\n" +
      'appendFileSync(process.env.SPLICE_FAKE_CURL_LOG, `${url}\\n`);\n' +
      "copyFileSync(join(process.env.SPLICE_FAKE_RELEASE_ASSETS, basename(url)), dest);\n",
  );
  writeStub(
    join(dir, "gh"),
    'import { appendFileSync } from "node:fs";\n' +
      'appendFileSync(process.env.SPLICE_FAKE_GH_LOG, `${process.argv.slice(2).join(" ")}\\n`);\n',
  );
  return { dir, ghLog: join(dir, "gh.log"), curlLog: join(dir, "curl.log") };
}

/** The same `gh`, refusing: a failed provenance check must abort before anything becomes live. */
export function breakAttestation(network: FakeNetwork): void {
  writeStub(join(network.dir, "gh"), "process.exit(1);\n");
}

/** A `java` whose `install` leaves a DANGLING command; `version` and `init` behave. */
export function danglingJava(dir: string): void {
  writeStub(
    join(dir, "java"),
    'import { symlinkSync } from "node:fs";\n' +
      'import { join } from "node:path";\n' +
      "const verb = process.argv[4];\n" +
      'if (verb === "version") { console.log("splice test"); process.exit(0); }\n' +
      'if (verb === "init") process.exit(0);\n' +
      'if (verb === "install") symlinkSync("/missing", join(process.env.SPLICE_BIN_DIR, "splice"));\n',
  );
}

/** A `java` whose `install` fails AFTER the artifacts were committed — the rollback's trigger. */
export function failingInstallJava(dir: string): void {
  writeStub(
    join(dir, "java"),
    "const verb = process.argv[4];\n" +
      'if (verb === "version") { console.log("splice test"); process.exit(0); }\n' +
      'if (verb === "init") process.exit(0);\n' +
      'if (verb === "install") process.exit(9);\n',
  );
}
