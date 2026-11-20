// restart-at-once: a daemon booted from this tree the way splice's systemd unit runs it, on a machine with no user
// manager (the Linux build box). systemd-socket-activate holds the listening sockets and hands them over by name
// (LISTEN_FDS, LISTEN_FDNAMES), the launcher (`splice-launch daemon`) keeps them and runs the daemon as its child, and
// INVOCATION_ID says a unit started it. So a restart the daemon takes on (POST /api/daemon/restart, the console's
// Restart) asks the launcher for the next daemon, which starts beside the running one, and the running one hands over.
// systemd-socket-activate execs the launcher at the first connection to one of its sockets, which this file makes.

import { spawn } from "node:child_process";
import { existsSync, openSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { Splice, jar, until } from "./harness.ts";

/** Create a home for [toml] and start its daemon under the launcher. [heads] names each head's key and port, which
 *  the socket manager listens on for it. */
export async function bootUnderLauncher(toml: string, heads: Record<string, number>, env: Record<string, string> = {}): Promise<Splice> {
  const s = await Splice.create(toml, env);
  try {
    await startUnderLauncher(s, heads);
  } catch (e) {
    await s.dispose();
    throw e;
  }
  return s;
}

/** Start the daemon of [s] under the launcher, the sockets held by systemd-socket-activate. */
export async function startUnderLauncher(s: Splice, heads: Record<string, number>): Promise<void> {
  const listeners = [{ name: "control", port: s.controlPort }, ...Object.entries(heads).map(([key, port]) => ({ name: `head-${key}`, port }))];
  const env: Record<string, string> = {
    ...s.env(),
    SPLICE_HEAD: "splice",
    SPLICE_JAR: jar(),
    SPLICE_JVM_OPTS: "-Xmx1g -Dsplice.noSystemBrowser=1",
    INVOCATION_ID: "restart-at-once-check",
  };
  const args: string[] = [];
  for (const l of listeners) args.push("-l", `127.0.0.1:${l.port}`);
  args.push(`--fdname=${listeners.map((l) => l.name).join(":")}`);
  for (const [k, v] of Object.entries(env)) args.push("-E", `${k}=${v}`);
  args.push(join(s.home, ".local", "share", "splice", "splice-launch"), "daemon");
  const from = existsSync(s.log) ? statSync(s.log).size : 0;
  const fd = openSync(s.log, "a");
  const proc = spawn("systemd-socket-activate", args, { cwd: s.home, env, stdio: ["ignore", fd, fd], detached: true });
  for (let i = 0; i < 360; i++) {
    if (proc.exitCode !== null) throw new Error(`the launcher exited ${proc.exitCode}:\n${s.logTail()}`);
    const said = readFileSync(s.log, "utf8").slice(from);
    if (said.includes("[daemon] up")) return;
    // Until it execs, systemd-socket-activate waits for a connection. One made before it listens is refused, so one
    // is made at every look until it says it execs; each waits in the queue for the daemon.
    if (!said.includes("Execing")) {
      void fetch(`http://127.0.0.1:${s.controlPort}/health`, { signal: AbortSignal.timeout(200_000) }).catch(() => {});
    }
    await Bun.sleep(500);
  }
  throw new Error(`the daemon did not come up under the launcher in 180 s:\n${s.logTail()}`);
}

export interface Health {
  pid: number | null;
  launcher: string | null;
  version: string | null;
}

/** What /health says of the daemon serving now; null when nothing answers. */
export async function health(s: Splice): Promise<Health | null> {
  try {
    const r = await fetch(`http://127.0.0.1:${s.controlPort}/health`, { signal: AbortSignal.timeout(5_000) });
    const j = (await r.json()) as { pid?: number; launcher?: string; version?: string };
    return { pid: j.pid ?? null, launcher: j.launcher ?? null, version: j.version ?? null };
  } catch {
    return null;
  }
}

/** The pid of the daemon serving now. */
export async function servingPid(s: Splice): Promise<number> {
  let pid: number | null = null;
  await until("a daemon answering /health with its pid", async () => {
    pid = (await health(s))?.pid ?? null;
    return pid !== null;
  }, 60_000);
  return pid!;
}

/** Wait until a daemon other than [from] answers /health, and answer when it did (ms since the epoch). */
export async function nextDaemon(s: Splice, from: number, ms = 180_000): Promise<number> {
  await until(`a daemon other than ${from} answering /health`, async () => {
    const pid = (await health(s))?.pid ?? null;
    return pid !== null && pid !== from;
  }, ms);
  return Date.now();
}

export interface Ran {
  code: number;
  out: string;
  err: string;
  /** When the command ended, ms since the epoch. */
  endedAt: number;
}

/** `splice <args>` against [s], run without blocking this process, its stdin closed. */
export function cliAsync(s: Splice, args: string[], env: Record<string, string> = {}): Promise<Ran> {
  const p = s.spawnCli(args, env);
  p.stdin?.end();
  let out = "";
  let err = "";
  p.stdout?.on("data", (d: Buffer) => (out += d.toString()));
  p.stderr?.on("data", (d: Buffer) => (err += d.toString()));
  return new Promise((resolve) => p.on("close", (code) => resolve({ code: code ?? -1, out, err, endedAt: Date.now() })));
}

/** How many times [needle] occurs in [hay]. */
export function occurrences(hay: string, needle: string): number {
  return hay.split(needle).length - 1;
}

/** A streamed Messages answer read whole: it ends with message_stop and carries no restart line. */
export function whole(body: string): boolean {
  return body.includes('"message_stop"') && !body.includes("restarted during");
}
