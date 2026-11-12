import { readdir, readFile, readlink, realpath, stat } from "node:fs/promises";
import { resolve, basename } from "node:path";
import { homedir } from "node:os";
import { candidateCommand } from "./candidate-command";
import { sha256 } from "./qualification";

export type ListenerIdentity = {
  pid: number; startTicks: string; bootId: string; controlPort: number; socketInodes: string[];
  supervisorUnit: string; supervisorMainPid: number | null;
};
export type CurrentInstalledIdentity = {
  listener: ListenerIdentity; jarFd: string; jarSha256: string; installedPath: string;
  hostPid: number; hostStartTicks: string; hostExeSha256: string; settingsCommand: string[];
};
export function listeningInodes(tables: string[], port: number): string[] {
  if (!Number.isSafeInteger(port) || port < 1 || port > 65535) throw new Error("invalid configured listener port");
  return [...new Set(tables.flatMap(table => table.trim().split("\n").slice(1).flatMap(line => {
    const columns = line.trim().split(/\s+/); const local = columns[1]?.split(":");
    return columns[3] === "0A" && parseInt(local?.[1] ?? "", 16) === port && /^\d+$/.test(columns[9] ?? "") ? [columns[9]!] : [];
  })))].sort();
}
export function listenerFindings(recorded: ListenerIdentity | undefined, current: ListenerIdentity): string[] {
  if (!recorded) return ["installed evidence lacks independently resolved configured listener identity"];
  const failures: string[] = [];
  for (const name of ["pid", "startTicks", "bootId", "controlPort", "supervisorUnit"] as const) {
    if (recorded[name] !== current[name]) failures.push(`installed configured listener ${name} changed`);
  }
  if (current.supervisorMainPid !== current.pid) failures.push("configured service MainPID does not own the configured listener");
  if (recorded.supervisorMainPid !== current.supervisorMainPid) failures.push("installed configured service selection changed");
  if (JSON.stringify(recorded.socketInodes) !== JSON.stringify(current.socketInodes)) failures.push("installed configured listening socket changed");
  return failures;
}
async function birth(pid: number): Promise<string> {
  const status = await readFile(`/proc/${pid}/stat`, "utf8"); const end = status.lastIndexOf(")");
  const ticks = status.slice(end + 2).split(" ")[19];
  if (!ticks || !/^\d+$/.test(ticks)) throw new Error(`cannot establish process ${pid} birth`);
  return ticks;
}
async function execute(argv: string[], environment?: Record<string, string>): Promise<{ stdout: string; stderr: string; exitCode: number }> {
  const child = Bun.spawn(argv, { stdin: "ignore", stdout: "pipe", stderr: "pipe", ...(environment ? { env: environment } : {}) });
  const timeout = setTimeout(() => child.kill("SIGKILL"), 15_000);
  try {
    const [stdout, stderr, exitCode] = await Promise.all([new Response(child.stdout).text(), new Response(child.stderr).text(), child.exited]);
    return { stdout, stderr, exitCode };
  } finally { clearTimeout(timeout); }
}
async function owners(inodes: string[]): Promise<number[]> {
  const sockets = new Set(inodes.map(inode => `socket:[${inode}]`)); const found: number[] = [];
  for (const entry of await readdir("/proc")) {
    if (!/^\d+$/.test(entry)) continue;
    try {
      for (const fd of await readdir(`/proc/${entry}/fd`)) {
        try { if (sockets.has(await readlink(`/proc/${entry}/fd/${fd}`))) { found.push(Number(entry)); break; } }
        catch { /* A descriptor can close during inspection. */ }
      }
    } catch { /* Foreign/exited process descriptors cannot establish ownership. */ }
  }
  return [...new Set(found)].sort((left, right) => left - right);
}
async function descendants(pid: number): Promise<number[]> {
  const found: number[] = [], pending = [pid];
  while (pending.length) {
    const parent = pending.shift()!;
    try {
      const children = new Set<number>();
      for (const task of await readdir(`/proc/${parent}/task`)) {
        try { (await readFile(`/proc/${parent}/task/${task}/children`, "utf8")).trim().split(/\s+/).filter(Boolean).map(Number).forEach(child => children.add(child)); }
        catch { /* Threads may finish while the process remains alive. */ }
      }
      for (const child of children) if (!found.includes(child)) { found.push(child); pending.push(child); }
    } catch { /* Exited process is not usable identity evidence. */ }
  }
  return found;
}

/** Resolve the current endpoint first; operator-provided historical PIDs never select the service. */
export async function observeCurrentInstallation(java: string, candidateJarHash: string, nativeHash: string, nonce: string): Promise<CurrentInstalledIdentity> {
  if (process.platform !== "linux") throw new Error("current installed deployment driver only qualifies Linux");
  const installedPath = await realpath(resolve(homedir(), ".local/share/splice/splice.jar"));
  if (sha256(await readFile(installedPath)) !== candidateJarHash) throw new Error("installed jar differs from current candidate");
  const settingsCommand = candidateCommand(java, installedPath, "--installed-settings", nonce);
  const settingsResult = await execute(settingsCommand);
  if (settingsResult.exitCode !== 0) throw new Error(`installed jar cannot resolve authoritative listener settings: ${settingsResult.stderr.trim()}`);
  let settings: any;
  try { settings = JSON.parse(settingsResult.stdout.trim()); } catch { throw new Error("installed settings diagnostic did not return a single JSON result"); }
  if (settings.nonce !== nonce || !Number.isSafeInteger(settings.controlPort) || typeof settings.supervisorUnit !== "string" || !settings.supervisorUnit) throw new Error("installed settings diagnostic returned unbound/malformed settings");
  const service = await execute(["systemctl", "--user", "show", settings.supervisorUnit, "--property=MainPID", "--value"]);
  const servicePid = service.exitCode === 0 && /^\d+$/.test(service.stdout.trim()) ? Number(service.stdout.trim()) : 0;
  if (!Number.isSafeInteger(servicePid) || servicePid <= 0) throw new Error("configured supervisor has no live MainPID; a separate daemon cannot establish installed service identity");
  const serviceBirth = await birth(servicePid);
  // The selected daemon's actual boot environment is the authority. It may differ from this agent's.
  const daemonEnvironment = Object.fromEntries((await readFile(`/proc/${servicePid}/environ`, "utf8")).split("\0").filter(Boolean).map(entry => {
    const equals = entry.indexOf("="); if (equals < 1) throw new Error("malformed configured daemon environment"); return [entry.slice(0, equals), entry.slice(equals + 1)];
  }));
  const boundSettings = await execute(settingsCommand, daemonEnvironment);
  if (boundSettings.exitCode !== 0) throw new Error("installed settings diagnostic is unsupported in the actual daemon environment");
  let actualSettings: any;
  try { actualSettings = JSON.parse(boundSettings.stdout.trim()); } catch { throw new Error("daemon-bound settings diagnostic returned malformed JSON"); }
  if (actualSettings.nonce !== nonce || actualSettings.supervisorUnit !== settings.supervisorUnit || !Number.isSafeInteger(actualSettings.controlPort)) throw new Error("daemon environment changed the configured service selection or returned unbound settings");
  settings = actualSettings;
  const tables = await Promise.all(["tcp", "tcp6"].map(protocol => readFile(`/proc/net/${protocol}`, "utf8")));
  const socketInodes = listeningInodes(tables, settings.controlPort);
  if (!socketInodes.length) throw new Error(`configured control listener :${settings.controlPort} is not listening`);
  const pids = await owners(socketInodes);
  if (pids.length !== 1) throw new Error(`configured listener requires exactly one independently resolved owner, found ${pids.join(",") || "none"}`);
  const pid = pids[0]!;
  if (pid !== servicePid || await birth(servicePid) !== serviceBirth) throw new Error("the configured service MainPID does not own the authoritative listener");
  const [startTicks, bootId, command] = await Promise.all([birth(pid), readFile("/proc/sys/kernel/random/boot_id", "utf8"), readFile(`/proc/${pid}/cmdline`, "utf8")]);
  if (basename(await readlink(`/proc/${pid}/exe`)) !== "java" || !command.split("\0").includes("daemon") || command.includes("--internal-code-mode-parity")) throw new Error("configured listener is not the installed Splice daemon JVM");
  let jarFd: string | undefined;
  for (const descriptor of await readdir(`/proc/${pid}/fd`)) {
    const path = `/proc/${pid}/fd/${descriptor}`;
    try {
      if ((await readlink(path)).replace(/ \(deleted\)$/, "") === installedPath && (await stat(path)).isFile() && sha256(await readFile(path)) === candidateJarHash) { jarFd = path; break; }
    } catch { /* A closing descriptor does not prove the open jar. */ }
  }
  if (!jarFd) throw new Error("configured listener is not holding the qualified installed jar open");
  let hostPid: number | undefined;
  for (const child of await descendants(pid)) {
    try { if (sha256(await readFile(`/proc/${child}/exe`)) === nativeHash) { hostPid = child; break; } }
    catch { /* A different/exited child cannot prove the selected native executable. */ }
  }
  if (!hostPid) throw new Error("configured listener has no live owned native host matching the current reference");
  const listener: ListenerIdentity = { pid, startTicks, bootId: bootId.trim(), controlPort: settings.controlPort, socketInodes,
    supervisorUnit: settings.supervisorUnit, supervisorMainPid: servicePid };
  if (await birth(pid) !== startTicks) throw new Error("listener process changed during identity inspection");
  return { listener, jarFd, jarSha256: candidateJarHash, installedPath, hostPid, hostStartTicks: await birth(hostPid), hostExeSha256: nativeHash, settingsCommand };
}
