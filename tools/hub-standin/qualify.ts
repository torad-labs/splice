#!/usr/bin/env bun
import { createHash, randomUUID } from "node:crypto";
import { spawn, type ChildProcess } from "node:child_process";
import { access, chmod, copyFile, mkdir, mkdtemp, open, readFile, readdir, rename, rm, stat, writeFile, type FileHandle } from "node:fs/promises";
import { createServer } from "node:net";
import { homedir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { performance } from "node:perf_hooks";
import { failureCode, Probe, type Sample } from "./probe";
import { CaptureFailure, CaptureProbe, type ReaderMode } from "./capture";

const OLD_HASHES = {
  front: "52c4b8c308fb1268e0e16fcf995396997056f9b9f87c1a3757a8e555025988af",
  holder: "2044df8c5b87c5a21e5ced4a0b5316a2723393e609aee3ccbada362bd6cf06b6",
};
const REQUIRED_BEHAVIOR_COMMIT = "15a0af554";
const scriptDir = dirname(import.meta.path);
const flags = new Map<string, string>();
const allowed = new Set(["--pair", "--old-pair", "--jar", "--java", "--receipt-dir", "--front-sha256", "--holder-sha256", "--jar-sha256", "--reader-mode"]);
for (let i = 2; i < process.argv.length; i += 2) {
  const flag = process.argv[i], value = process.argv[i + 1];
  if (!flag || !allowed.has(flag) || !value || flags.has(flag)) throw new Error("Use only the documented noncredential flag/value arguments");
  flags.set(flag, value);
}
const required = (name: string): string => {
  const value = flags.get(name);
  if (!value) throw new Error(`Missing ${name}`);
  return value;
};
const pair = resolve(required("--pair"));
const oldPair = resolve(required("--old-pair"));
const jar = resolve(required("--jar"));
const java = resolve(required("--java"));
const receiptDir = resolve(required("--receipt-dir"));
const readerMode = flags.get("--reader-mode") ?? "jvm-core";
if (readerMode !== "native-json" && readerMode !== "jvm-core") throw new Error("reader-mode must be native-json or jvm-core");
const expected = {
  front: required("--front-sha256"), holder: required("--holder-sha256"), jar: required("--jar-sha256"),
};
if ([expected.front, expected.holder, expected.jar].some((hash) => !/^[a-f0-9]{64}$/.test(hash))) {
  throw new Error("Expected artifact digests must be complete SHA-256 values supplied by the release owner");
}
const unit = `splice-hub-standin-${process.pid}-${randomUUID().slice(0, 8)}`;
const units: string[] = [];
const daemons: ChildProcess[] = [];
const daemonPids = new Map<ChildProcess, number>();
let daemonNamespace: FileHandle | undefined;
const redactions = new Set<string>();
const receipt: Record<string, unknown> = { status: "NOT_QUALIFIED", requiredBehaviorCommit: REQUIRED_BEHAVIOR_COMMIT, artifactProvenance: "release-owner supplied hashes; build revision is not inferred", steps: [] };
const calls: Sample[] = [];
let desk: string | undefined, probe: Probe | undefined, probeWork: Promise<void> | undefined, capture: CaptureProbe | undefined;
let phase = "setup";
let cleanupOk = true;
let receiptCreated = false;
let interrupted = false, cleaning = false;
let daemonLog = "";
const interrupt = () => { interrupted = true; probe?.stop(); };
process.on("SIGINT", interrupt);
process.on("SIGTERM", interrupt);

/** Only messages authored by this procedure are safe to retain in its receipt. */
class QualificationFailure extends Error {}

function invariant(ok: unknown, message: string): asserts ok {
  if (!ok) throw new QualificationFailure(message);
}

async function command(program: string, args: string[]): Promise<string> {
  return await new Promise((resolveCommand, reject) => {
    const child = spawn(program, args, { stdio: ["ignore", "pipe", "pipe"] });
    let output = "";
    child.stdout.on("data", (chunk) => { output = (output + chunk.toString()).slice(-8192); });
    child.stderr.resume();
    let timedOut = false;
    const timer = setTimeout(() => { timedOut = true; child.kill("SIGKILL"); }, 15_000);
    child.once("error", () => { clearTimeout(timer); reject(new QualificationFailure("A stand-in prerequisite command could not start")); });
    child.once("exit", (code) => {
      clearTimeout(timer);
      if (code === 0 && !timedOut) resolveCommand(output.trim());
      else reject(new QualificationFailure(`Stand-in prerequisite command ${timedOut ? "timed out" : "failed"}: ${program}`));
    });
  });
}

async function digest(path: string): Promise<string> {
  return createHash("sha256").update(await readFile(path)).digest("hex");
}

async function port(): Promise<number> {
  const socket = createServer();
  await new Promise<void>((resolvePort, reject) => {
    socket.once("error", reject);
    socket.listen(0, "127.0.0.1", () => resolvePort());
  });
  const address = socket.address();
  invariant(address && typeof address !== "string", "Could not select a loopback port");
  const number = address.port;
  await new Promise<void>((resolvePort, reject) => socket.close((error) => error ? reject(error) : resolvePort()));
  return number;
}

async function waitFor(check: () => Promise<boolean>, label: string, milliseconds = 30_000): Promise<void> {
  const end = performance.now() + milliseconds;
  while (performance.now() < end) {
    invariant(cleaning || !interrupted, "The stand-in was interrupted");
    if (await check()) return;
    await new Promise((done) => setTimeout(done, 100));
  }
  throw new QualificationFailure(`Timed out waiting for ${label}`);
}

async function privateBearer(file: string): Promise<string> {
  const value = (await readFile(file, "utf8")).trim();
  invariant(value.length >= 16 && !/\s/.test(value), "The isolated daemon did not publish a usable bearer");
  redactions.add(value);
  return value;
}

async function stopDaemon(child: ChildProcess): Promise<void> {
  const pid = daemonPids.get(child);
  const signal = async (name: NodeJS.Signals): Promise<void> => {
    if (pid) {
      try {
        const argv = (await readFile(`/proc/${pid}/cmdline`, "utf8")).split("\0");
        invariant(argv[0] === java && argv.includes(`-Duser.home=${desk}`) && argv.includes(join(desk!, "bin", "splice.jar")), "Refused to signal a daemon outside its private desk");
        process.kill(pid, name);
      } catch (error) {
        if (!(error && typeof error === "object" && "code" in error && ["ENOENT", "ESRCH"].includes(String(error.code)))) throw error;
      }
    } else if (child.exitCode === null && child.signalCode === null) child.kill(name);
  };
  if (child.exitCode !== null || child.signalCode !== null) { await signal("SIGTERM"); return; }
  await signal("SIGTERM");
  try {
    await waitFor(async () => child.exitCode !== null || child.signalCode !== null, "owned daemon termination", 10_000);
  } catch {
    await signal("SIGKILL");
    await waitFor(async () => child.exitCode !== null || child.signalCode !== null, "owned daemon kill", 5000);
  }
}

async function main(): Promise<void> {
  const hashes = {
    oldFront: await digest(join(oldPair, "splice-mcp-front")), oldHolder: await digest(join(oldPair, "splice-mcp-holder")),
    front: await digest(join(pair, "splice-mcp-front")), holder: await digest(join(pair, "splice-mcp-holder")), jar: await digest(jar),
  };
  invariant(hashes.oldFront === OLD_HASHES.front && hashes.oldHolder === OLD_HASHES.holder, "Old pair does not match the qualified bbe7a874c artifacts");
  invariant(hashes.front === expected.front && hashes.holder === expected.holder && hashes.jar === expected.jar, "Supplied artifact hashes differ");
  invariant(hashes.front !== hashes.oldFront, "Changed-byte upgrade requires a different front, not the old bytes");
  await access(java);
  invariant(!receiptDir.startsWith(`${homedir()}/.`), "Receipts must not target an operator configuration home");
  await mkdir(dirname(receiptDir), { recursive: true, mode: 0o700 });
  await mkdir(receiptDir, { mode: 0o700 });
  receiptCreated = true;
  await command("systemctl", ["--user", "show", "--property=Version", "--value"]);
  desk = await mkdtemp("/tmp/hq-");
  receipt.privateDesk = desk;
  receipt.ownedUnitPrefix = unit;
  await chmod(desk, 0o700);
  const bin = join(desk, "bin"), state = join(desk, ".splice", "state");
  const peerLog = join(desk, "native-peer-outcomes.jsonl");
  const controlPort = await port(), frontPort = await port(), missingPort = await port();
  invariant(new Set([controlPort, frontPort, missingPort]).size === 3, "Loopback ports collided; run again with a new desk");
  invariant(![controlPort, frontPort, missingPort].some((number) => number === 3095 || number === 3096), "A reserved live port was selected");
  for (const path of [bin, state, join(desk, ".config", "splice"), join(desk, ".local", "bin")]) await mkdir(path, { recursive: true, mode: 0o700 });
  await copyFile(jar, join(bin, "splice.jar"));
  await copyFile(join(pair, "splice-mcp-holder"), join(bin, "splice-mcp-holder"));
  await copyFile(join(pair, "splice-mcp-front"), join(bin, "splice-mcp-front"));
  await copyFile(join(oldPair, "splice-mcp-front"), join(bin, "old-front"));
  await copyFile(join(scriptDir, "counter.ts"), join(bin, "counter.ts"));
  invariant(await digest(join(bin, "splice.jar")) === expected.jar &&
    await digest(join(bin, "splice-mcp-holder")) === expected.holder &&
    await digest(join(bin, "splice-mcp-front")) === expected.front &&
    await digest(join(bin, "old-front")) === OLD_HASHES.front, "Artifact changed while copying into the private desk");
  for (const name of ["splice-mcp-holder", "splice-mcp-front", "old-front"]) await chmod(join(bin, name), 0o755);
  await writeFile(join(desk, ".config", "splice", "splice.toml"), `[daemon]\ncontrol_port = ${controlPort}\nmcp_hosting = true\nmcp_share = true\nauto_update = false\n`, { mode: 0o600 });
  await writeFile(join(desk, ".claude.json"), JSON.stringify({ mcpServers: {
    counter: { command: process.execPath, args: [join(bin, "counter.ts")], env: { SPLICE_HUB_CAPTURE_LOG: peerLog } },
  } }), { mode: 0o600 });
  // The supplied daemon's holder path may still default to app-mcp.slice. This
  // invocation-only adapter preserves real systemd scope ownership but accepts
  // only this desk's holder/counter arguments and redirects their slice. No
  // installed unit or production process can pass this adapter's allowlist.
  const quote = (word: string) => `'${word.replaceAll("'", "'\\''")}'`;
  const holder = join(bin, "splice-mcp-holder"), counter = join(bin, "counter.ts");
  const realSystemdRun = join(bin, "systemd-run.real");
  await copyFile("/usr/bin/systemd-run", realSystemdRun);
  await chmod(realSystemdRun, 0o755);
  invariant(await digest(realSystemdRun) === await digest("/usr/bin/systemd-run"), "The real systemd-run bytes changed while copying");
  await command(realSystemdRun, ["--version"]);
  const scopeAdapter = `#!/bin/bash\nset -euo pipefail\n` +
    `test "$#" -eq 10\ntest "$1" = --user\ntest "$2" = --scope\ntest "$3" = --quiet\n` +
    `case "$4" in --slice=app-mcp.slice|--slice=${unit}-mcp.slice) ;; *) exit 65 ;; esac\n` +
    `[[ "$5" =~ ^--unit=splice-mcp-[0-9a-f]{16}-[0-9]+-[0-9a-f]{32}$ ]]\n` +
    `test "$6" = --\ntest "$7" = ${quote(holder)}\n` +
    `case "$8" in ${quote(join(state, "mcp-hub"))}/*.sock) ;; *) exit 65 ;; esac\n` +
    `test "$9" = ${quote(process.execPath)}\ntest "\${10}" = ${quote(counter)}\n` +
    `shift 4\nexec ${quote(realSystemdRun)} --user --scope --quiet --slice=${unit}-mcp.slice --property=MemoryMax=256M "$@"\n`;
  await access("/usr/bin/systemd-run");
  await writeFile(join(bin, "systemd-run"), scopeAdapter, { mode: 0o700 });
  await access("/usr/bin/bwrap");
  const environment: NodeJS.ProcessEnv = {
    HOME: desk, XDG_CONFIG_HOME: join(desk, ".config"), XDG_DATA_HOME: join(desk, ".local", "share"), XDG_STATE_HOME: join(desk, ".local", "state"),
    XDG_RUNTIME_DIR: process.env.XDG_RUNTIME_DIR, DBUS_SESSION_BUS_ADDRESS: process.env.DBUS_SESSION_BUS_ADDRESS,
    LANG: "C.UTF-8", PATH: [bin, dirname(process.execPath), dirname(java), "/usr/bin", "/bin"].join(":"),
    SPLICE_CONFIG: join(desk, ".config", "splice", "splice.toml"), SPLICE_STATE_DIR: state,
    SPLICE_CONTROL_PORT: String(controlPort), SPLICE_SHARE_DIR: bin, SPLICE_MCP_SLICE: `${unit}-mcp.slice`,
  };
  const keepLog = (chunk: Buffer): void => { daemonLog = (daemonLog + chunk.toString()).slice(-2 * 1024 * 1024); };
  const startDaemon = async (): Promise<ChildProcess> => {
    const retainedNamespace = daemonNamespace;
    const child = spawn("/usr/bin/bwrap", ["--die-with-parent", ...(retainedNamespace ? ["--userns", "3"] : []),
      "--bind", "/", "/", "--dev-bind", "/dev", "/dev", "--proc", "/proc", "--bind", join(bin, "systemd-run"), "/usr/bin/systemd-run",
      java, `-Duser.home=${desk}`, "-Xmx512m", "-jar", join(bin, "splice.jar"), "daemon"],
    { env: environment, stdio: retainedNamespace ? ["ignore", "pipe", "pipe", retainedNamespace.fd] : ["ignore", "pipe", "pipe"] });
    daemons.push(child);
    child.stdout?.on("data", keepLog);
    child.stderr?.on("data", keepLog);
    child.once("error", () => { /* readiness fails without logging an environment or credential */ });
    await waitFor(async () => {
      const candidates = child.pid ? [child.pid] : [];
      for (let index = 0; index < candidates.length && index < 16; index++) {
        const pid = candidates[index]!;
        try {
          const argv = (await readFile(`/proc/${pid}/cmdline`, "utf8")).split("\0");
          if (argv[0] === java && argv.includes(`-Duser.home=${desk}`) && argv.includes(join(bin, "splice.jar"))) { daemonPids.set(child, pid); break; }
          const children = (await readFile(`/proc/${pid}/task/${pid}/children`, "utf8")).trim().split(/\s+/).filter(Boolean).map(Number);
          candidates.push(...children);
        } catch (error) { if (!(error && typeof error === "object" && "code" in error && error.code === "ENOENT")) throw error; }
      }
      if (child.exitCode !== null || child.signalCode !== null) throw new QualificationFailure("The isolated daemon exited before readiness");
      if (!daemonPids.has(child)) return false;
      try { const response = await fetch(`http://127.0.0.1:${controlPort}/health`, { signal: AbortSignal.timeout(1500) }); return response.ok; } catch { return false; }
    }, "isolated daemon readiness");
    const namespace = `/proc/${daemonPids.get(child)}/ns/user`;
    if (retainedNamespace) {
      invariant((await retainedNamespace.stat()).ino === (await stat(namespace)).ino, "The replacement daemon escaped the fixture user namespace");
    } else {
      daemonNamespace = await open(namespace, "r");
    }
    return child;
  };
  let daemon = await startDaemon();
  const mcpFile = join(state, "mcp-hub", "access-key"), managementFile = join(state, "mgmt-key");
  const mcp = await privateBearer(mcpFile), management = await privateBearer(managementFile);
  invariant(mcp !== management, "The desk requires a distinct MCP-scoped publication");
  const launch = String(process.pid);
  const endpoint = (listenPort: number) => new URL(`http://127.0.0.1:${listenPort}/mcp/counter`);
  const launchHeaders = { "X-Splice-Launch": launch };
  const frontPid = async (name: string): Promise<number> => Number(await command("systemctl", ["--user", "show", `${name}.service`, "--property=MainPID", "--value"]));
  const startFront = async (name: string, listenPort: number, binary: string): Promise<void> => {
    units.push(name);
    await command("systemd-run", ["--user", `--unit=${name}`, `--slice=${unit}.slice`,
      `--socket-property=ListenStream=127.0.0.1:${listenPort}`, "--socket-property=Accept=no", "--property=Type=simple",
      "--property=Restart=always", "--property=RestartSec=1", "--property=StartLimitIntervalSec=0", "--property=MemoryMax=128M", "--property=UMask=0077",
      "--property=ExecReload=/bin/kill -USR2 $MAINPID", `--setenv=SPLICE_MCP_HUB=${join(state, "mcp-hub")}`,
      `--setenv=SPLICE_MCP_CONTROL_PORT=${controlPort}`, binary, "--hub", join(state, "mcp-hub"), "--control-port", String(controlPort)]);
  };

  phase = "continuity setup";
  const continuityUnit = `${unit}-front`;
  // Exercise the supported old-to-new direction before the new code allocates durable wire ids.
  // Starting old code over new state would erase counters unknown to that old version.
  const oldSibling = join(bin, "splice-mcp-front.old");
  await copyFile(join(bin, "old-front"), oldSibling); await chmod(oldSibling, 0o755);
  await rename(oldSibling, join(bin, "splice-mcp-front"));
  await startFront(continuityUnit, frontPort, join(bin, "splice-mcp-front"));
  probe = new Probe(endpoint(frontPort), () => privateBearer(mcpFile), launchHeaders, (sample) => { calls.push(sample); });
  await probe.initialize();
  await probe.openStream();
  let probeFailed = false;
  probeWork = probe.run().catch(() => { probeFailed = true; });
  await waitFor(async () => probe!.samples.filter((sample) => sample.ok).length >= 20, "warm counter calls");
  const step = async (name: string, transition: () => Promise<void>): Promise<void> => {
    phase = name;
    const before = probe!.samples.at(-1);
    invariant(before?.ok && before.reading, "The continuity step did not start with a successful call");
    const start = probe!.samples.length, priorFront = await frontPid(continuityUnit), priorSession = probe!.sessionId;
    invariant(probe!.streamFaults === 0, "The notification stream was not healthy before transition");
    await transition();
    const afterStart = probe!.samples.length, afterStream = probe!.streamEvents;
    await waitFor(async () => {
      invariant(!probeFailed, "The probe terminated before the continuity window completed");
      invariant(probe!.streamFaults === 0, "The notification stream closed during transition");
      return probe!.samples.length - afterStart >= 100 && probe!.streamEvents > afterStream;
    }, "calls after transition", 30_000);
    const samples = probe!.samples.slice(start), successful = samples.filter((sample) => sample.ok);
    invariant(successful.length >= 99 && samples.every((sample) => sample.ok), "A continuity call failed");
    let lastAt = before.at, longestGapMs = 0;
    for (const sample of successful) { longestGapMs = Math.max(longestGapMs, sample.at - lastAt); lastAt = sample.at; }
    invariant(longestGapMs < 3000, "The continuity gap exceeded the documented three-second bound");
    const after = successful.at(-1)!.reading!;
    invariant(after.instance === before.reading.instance && after.pid === before.reading.pid && after.count > before.reading.count, "Counter process or state did not survive");
    const group = await readFile(`/proc/${after.pid}/cgroup`, "utf8");
    invariant(group.includes(`/${unit}-mcp.slice/`), "The stand-in server escaped its private holder slice");
    const nextFront = await frontPid(continuityUnit);
    invariant(priorFront > 0 && priorFront === nextFront, "The front did not retain its PID");
    invariant(probe!.streamFaults === 0 && probe!.sessionId === priorSession, "The stream or MCP session did not survive");
    (receipt.steps as unknown[]).push({ step: name, status: "PASS", calls: samples.length, failed: 0, longestGapMs,
      countBefore: before.reading.count, countAfter: after.count, serverPid: after.pid, frontPid: nextFront, streamFaults: 0, sessionRetained: true });
  };
  const oldPid = await frontPid(continuityUnit);
  invariant(await digest(`/proc/${oldPid}/exe`) === OLD_HASHES.front, "The upgrade did not start on the old front bytes");
  await step("changed-byte USR2 upgrade", async () => {
    const sibling = join(bin, "splice-mcp-front.next");
    await copyFile(join(pair, "splice-mcp-front"), sibling); await chmod(sibling, 0o755);
    invariant(await digest(sibling) === expected.front, "Replacement front changed before atomic publication");
    await rename(sibling, join(bin, "splice-mcp-front"));
    await command("systemctl", ["--user", "reload", `${continuityUnit}.service`]);
    const pid = await frontPid(continuityUnit);
    await waitFor(async () => await digest(`/proc/${pid}/exe`) === expected.front, "new front exec bytes", 5000);
  });
  capture = new CaptureProbe(endpoint(frontPort), () => privateBearer(mcpFile), launchHeaders, {
    state, peerLog, java, jar: join(bin, "splice.jar"), receiptDir, readerMode: readerMode as ReaderMode,
    readerSource: join(scriptDir, "ReceiptReader.java"),
  });
  phase = "native MCP capture after changed-byte exec";
  await capture.initialize();
  await capture.prove(phase);
  const replacedDaemonPid = daemonPids.get(daemon);
  await step("daemon replacement", async () => { await stopDaemon(daemon); daemon = await startDaemon(); });
  invariant(replacedDaemonPid !== daemonPids.get(daemon), "The JVM daemon process was not actually replaced");
  receipt.daemonReplacement = { beforePid: replacedDaemonPid, afterPid: daemonPids.get(daemon), sameFixtureUserNamespace: true };
  phase = "native MCP capture after daemon replacement";
  await capture.prove(phase);

  phase = "missing MCP publication";
  probe.stop();
  await probeWork;
  const refused = new Probe(endpoint(frontPort), async () => mcp, launchHeaders, () => {});
  await rename(mcpFile, `${mcpFile}.withheld`);
  try {
    const request = { jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2025-03-26", capabilities: {}, clientInfo: { name: "standin-missing-publication", version: "1" } } };
    const asMcp = await refused.post(request, mcp), asManagement = await refused.post(request, management);
    invariant(asMcp.status === 401 && asManagement.status === 401, "Missing MCP publication did not return the source-defined 401 refusal");
    const control = await fetch(`http://127.0.0.1:${controlPort}/api/mcp`, { headers: { Authorization: `Bearer ${management}` }, signal: AbortSignal.timeout(1500) });
    invariant(control.ok, "Management auth was not valid independently of the missing publication");
    invariant(await privateBearer(managementFile) === management, "The missing-publication check altered management auth");
    (receipt.steps as unknown[]).push({ step: phase, status: "PASS", mcpStatus: asMcp.status, managementStatus: asManagement.status });
  } finally {
    refused.stop();
    await rename(`${mcpFile}.withheld`, mcpFile);
  }

  phase = "outstanding metadata capture through cold-front recovery";
  const delayed = await capture.beforeRecovery();
  const recoveringPid = await frontPid(continuityUnit);
  await command("systemctl", ["--user", "kill", "--kill-whom=main", "--signal=SIGKILL", `${continuityUnit}.service`]);
  await waitFor(async () => {
    const pid = await frontPid(continuityUnit);
    return pid > 0 && pid !== recoveringPid;
  }, "owned front recovery");
  await capture.afterRecovery(delayed);
  (receipt.steps as unknown[]).push({ step: phase, status: "PASS", originalFrontPid: recoveringPid,
    recoveredFrontPid: await frontPid(continuityUnit), retainedPendingIdentity: true });
  receipt.capture = { proofs: capture.proofs, coreReader: readerMode === "jvm-core" ? "PASS" : "PENDING",
    sourceQualification: readerMode === "jvm-core" ? "READER_CHECKED" : "UNQUALIFIED" };
  receipt.hashes = hashes;
  receipt.method = { realDaemon: true, holderSlice: "private invocation adapter", userNamespace: "retained across daemon replacement", openNotificationStream: true, frontUpgrade: "old bbe7a874c to different supplied bytes by USR2" };
  receipt.status = readerMode === "jvm-core" ? "PASS" : "NATIVE_PAIR_ONLY";
  receipt.nativePairStatus = "PASS";
  let log = daemonLog;
  for (const secret of redactions) log = log.replaceAll(secret, "[redacted]");
  await writeFile(join(receiptDir, "daemon.log"), log, { mode: 0o600 });
}

try {
  await main();
} catch (error) {
  receipt.status = "FAIL";
  receipt.failedStep = phase;
  receipt.failure = error instanceof QualificationFailure || error instanceof CaptureFailure ? error.message : failureCode(error);
} finally {
  cleaning = true;
  if (interrupted) { receipt.status = "FAIL"; receipt.failedStep = phase; receipt.failure ??= "The stand-in was interrupted"; }
  probe?.stop();
  capture?.stop();
  try { await capture?.retainDebugEvidence(); } catch { receipt.debugEvidence = "incomplete"; }
  if (probeWork) await probeWork.catch(() => { cleanupOk = false; });
  for (const name of units.reverse()) {
    try { await command("systemctl", ["--user", "stop", `${name}.socket`, `${name}.service`]); } catch { cleanupOk = false; }
  }
  for (const daemon of daemons) {
    try { await stopDaemon(daemon); } catch { cleanupOk = false; }
  }
  // A failed launch can outlive its daemon. Only a recorded PID whose argv is this desk's exact holder may be stopped.
  if (desk) {
    const hub = join(desk, ".splice", "state", "mcp-hub"), holder = join(desk, "bin", "splice-mcp-holder");
    try {
      for (const name of (await readdir(hub)).filter((name) => /^[a-f0-9]{16}\.json$/.test(name))) {
        const record = JSON.parse(await readFile(join(hub, name), "utf8")) as { pid?: unknown };
        if (!Number.isSafeInteger(record.pid) || Number(record.pid) < 2) continue;
        const pid = Number(record.pid);
        try {
          const argv = (await readFile(`/proc/${pid}/cmdline`, "utf8")).split("\0");
          invariant(argv[0] === holder, "Cleanup refused a holder PID outside the exact private desk");
          const scope = (await readFile(`/proc/${pid}/cgroup`, "utf8")).trim().split("/").at(-1)!;
          invariant(/^splice-mcp-[a-f0-9]{16}-[0-9]+-[a-f0-9]{32}\.scope$/.test(scope), "Cleanup refused an unrecognized owned holder scope");
          await command("systemctl", ["--user", "stop", scope]);
        } catch (error) {
          if (!(error && typeof error === "object" && "code" in error && error.code === "ENOENT")) throw error;
        }
      }
    } catch { cleanupOk = false; }
  }
  for (const slice of [`${unit}-mcp.slice`, `${unit}.slice`, `splice-hub-standin-${process.pid}.slice`]) {
    try { await command("systemctl", ["--user", "stop", slice]); } catch { cleanupOk = false; }
  }
  try { await daemonNamespace?.close(); } catch { cleanupOk = false; }
  if (desk && cleanupOk) {
    try {
      await rm(desk, { recursive: true, force: true });
    } catch (error) {
      cleanupOk = false;
      receipt.failure ??= `Owned desk cleanup failed: ${failureCode(error)}`;
    }
  }
  receipt.cleanup = cleanupOk ? "complete" : "incomplete; owned desk retained";
  if (!cleanupOk) { receipt.status = "FAIL"; receipt.failure ??= "Owned stand-in cleanup did not complete"; }
  if (receiptCreated) {
    let log = daemonLog;
    for (const secret of redactions) log = log.replaceAll(secret, "[redacted]");
    await writeFile(join(receiptDir, "daemon.log"), log, { mode: 0o600 });
    try {
      await writeFile(join(receiptDir, "calls.jsonl"), calls.map((sample) => JSON.stringify(sample)).join("\n") + "\n", { mode: 0o600 });
    } catch (error) {
      receipt.status = "FAIL";
      receipt.failure ??= failureCode(error);
    }
    await writeFile(join(receiptDir, "receipt.json"), JSON.stringify(receipt, null, 2) + "\n", { mode: 0o600 });
  }
  const qualified = receipt.status === "PASS" || receipt.status === "NATIVE_PAIR_ONLY";
  process.stdout.write(`${receipt.status}: ${qualified ? "native pair continuity and actual typed capture" : phase}\n`);
  process.exitCode = qualified ? 0 : 1;
}
