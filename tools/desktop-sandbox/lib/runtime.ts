// Runtime lifecycle for tools/desktop-sandbox (docs/specs/desktop-full.md, outcome 8).
// Starts an isolated X display (Xvfb or Xephyr), a fresh splice daemon on free loopback ports, and the installed
// Claude Desktop under dbus-run-session + --password-store=basic with HOME/XDG_CONFIG_HOME inside the sandbox root.

import { spawn, spawnSync } from "node:child_process";
import {
  closeSync,
  existsSync,
  mkdirSync,
  openSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { createServer } from "node:net";
import { dirname, join, resolve } from "node:path";
import { assertSafeSandboxRoot } from "./paths.ts";

export const DESKTOP_BIN = "/usr/lib/claude-desktop/claude-desktop";
export const REPO_ROOT = resolve(dirname(import.meta.path), "..", "..", "..");

export interface SandboxState {
  root: string;
  display: string;
  displayPid: number;
  controlPort: number;
  headPort: number;
  daemonPid: number;
  desktopPid: number | null;
  jar: string;
  watch: boolean;
}

export function stateFile(root: string): string {
  return join(root, "sandbox.json");
}

export function readState(root: string): SandboxState {
  const file = stateFile(root);
  if (!existsSync(file)) {
    throw new Error(`no running sandbox state at ${file}; run 'start' first`);
  }
  return JSON.parse(readFileSync(file, "utf8")) as SandboxState;
}

export function writeState(state: SandboxState): void {
  writeFileSync(stateFile(state.root), JSON.stringify(state, null, 2) + "\n", "utf8");
}

/** Find an unused TCP loopback port. */
export function freePort(): Promise<number> {
  return new Promise((res, rej) => {
    const srv = createServer();
    srv.unref();
    srv.on("error", rej);
    srv.listen(0, "127.0.0.1", () => {
      const addr = srv.address();
      const port = typeof addr === "object" && addr ? addr.port : 0;
      srv.close(() => res(port));
    });
  });
}

/** Locate the splice gateway jar from SPLICE_JAR or app/build/libs. */
export function resolveJar(): string {
  const fromEnv = process.env.SPLICE_JAR?.trim();
  if (fromEnv && existsSync(fromEnv)) return fromEnv;
  const candidate = join(REPO_ROOT, "app", "build", "libs", "splice-gateway-all.jar");
  if (existsSync(candidate)) return candidate;
  throw new Error(
    "splice jar not found; set SPLICE_JAR=/path/to/splice-gateway-all.jar or build :app:shadowJar",
  );
}

/** Build the completely isolated environment for splice and Desktop inside `root`. */
export function sandboxEnv(root: string, display?: string): Record<string, string> {
  const home = join(root, "home");
  const config = join(root, "config");
  const stateDir = join(root, "splice-state");
  const spliceConfig = join(root, "splice-config");
  const claudeDir = join(home, ".claude");
  const out: Record<string, string> = {
    PATH: process.env.PATH ?? "/usr/local/bin:/usr/bin:/bin",
    HOME: home,
    XDG_CONFIG_HOME: config,
    XDG_DATA_HOME: join(root, "data"),
    XDG_CACHE_HOME: join(root, "cache"),
    XDG_RUNTIME_DIR: join(root, "runtime"),
    CLAUDE_CONFIG_DIR: claudeDir,
    SPLICE_HOME: join(root, "splice-home"),
    SPLICE_STATE_DIR: stateDir,
    SPLICE_CONFIG_DIR: spliceConfig,
    SPLICE_CONFIG: join(spliceConfig, "splice.toml"),
    NO_AT_BRIDGE: "1",
  };
  if (display) out.DISPLAY = display;
  if (process.env.JAVA_HOME) out.JAVA_HOME = process.env.JAVA_HOME;
  return out;
}

/** Pick a free X display number (:50 .. :199). */
function pickDisplay(): string {
  for (let n = 50; n < 200; n++) {
    if (!existsSync(`/tmp/.X${n}-lock`) && !existsSync(`/tmp/.X11-unix/X${n}`)) {
      return `:${n}`;
    }
  }
  throw new Error("no free X display number available in :50..:199");
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

function alive(pid: number | null | undefined): boolean {
  if (!pid || pid <= 0) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

export function stopProcess(pid: number | null | undefined): void {
  if (!alive(pid)) return;
  try {
    process.kill(-pid!, "SIGTERM");
  } catch {
    try {
      process.kill(pid!, "SIGTERM");
    } catch {}
  }
  const deadline = Date.now() + 3_000;
  while (Date.now() < deadline && alive(pid)) {
    Bun.sleepSync(50);
  }
  if (alive(pid)) {
    try {
      process.kill(-pid!, "SIGKILL");
    } catch {
      try {
        process.kill(pid!, "SIGKILL");
      } catch {}
    }
  }
}

function startDisplay(root: string, watch: boolean): { display: string; pid: number } {
  const display = pickDisplay();
  const bin = watch ? "Xephyr" : "Xvfb";
  const args = watch
    ? [display, "-screen", "1280x800", "-ac"]
    : [display, "-screen", "0", "1280x800x24", "-ac", "-nolisten", "tcp"];
  const logFd = openSync(join(root, "logs", "display.log"), "a");
  const child = spawn(bin, args, { detached: true, stdio: ["ignore", logFd, logFd] });
  closeSync(logFd);
  child.unref();
  if (!child.pid) throw new Error(`failed to start ${bin} on ${display}`);
  return { display, pid: child.pid };
}

async function waitForDisplay(display: string, pid: number): Promise<void> {
  const num = display.replace(/^:/, "");
  const sock = `/tmp/.X11-unix/X${num}`;
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    if (!alive(pid)) throw new Error(`display server on ${display} exited early`);
    if (existsSync(sock)) return;
    await sleep(100);
  }
  throw new Error(`timed out waiting for X display ${display}`);
}

export function runSpliceCli(
  root: string,
  jar: string,
  args: string[],
  display?: string,
): { code: number; stdout: string; stderr: string } {
  const r = spawnSync("java", ["-jar", jar, ...args], {
    env: sandboxEnv(root, display),
    encoding: "utf8",
    timeout: 60_000,
  });
  return { code: r.status ?? -1, stdout: r.stdout ?? "", stderr: r.stderr ?? "" };
}

async function startDaemon(
  root: string,
  jar: string,
  controlPort: number,
  headPort: number,
  upstreamUrl?: string,
): Promise<number> {
  const cfgDir = join(root, "splice-config");
  const baseUrl = upstreamUrl ?? "http://127.0.0.1:1";
  const toml = [
    "[daemon]",
    `control_port = ${controlPort}`,
    "",
    "[heads.sandbox]",
    'command = "claude-sandbox"',
    `port = ${headPort}`,
    'provider = "openai-compat"',
    `base_url = "${baseUrl}"`,
    'api_key = "sandbox-key"',
    'model = "sandbox-model"',
    "",
  ].join("\n");
  writeFileSync(join(cfgDir, "splice.toml"), toml, "utf8");
  const logFd = openSync(join(root, "logs", "daemon.log"), "a");
  const child = spawn("java", ["-jar", jar, "daemon", "--foreground"], {
    env: sandboxEnv(root),
    detached: true,
    stdio: ["ignore", logFd, logFd],
  });
  closeSync(logFd);
  child.unref();
  if (!child.pid) throw new Error("failed to spawn splice daemon");

  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    if (!alive(child.pid)) {
      const log = existsSync(join(root, "logs", "daemon.log"))
        ? readFileSync(join(root, "logs", "daemon.log"), "utf8").slice(-2000)
        : "";
      throw new Error(`splice daemon exited early:\n${log}`);
    }
    try {
      const res = await fetch(`http://127.0.0.1:${controlPort}/health`);
      if (res.ok) return child.pid;
    } catch {}
    await sleep(150);
  }
  throw new Error(`timed out waiting for splice daemon on :${controlPort}`);
}

export function launchDesktop(root: string, display: string): number {
  if (!existsSync(DESKTOP_BIN)) {
    throw new Error(`Claude Desktop binary not found at ${DESKTOP_BIN}`);
  }
  const env = sandboxEnv(root, display);
  const logFd = openSync(join(root, "logs", "desktop.log"), "a");
  const child = spawn(
    "dbus-run-session",
    [
      "--",
      DESKTOP_BIN,
      "--password-store=basic",
      "--no-sandbox",
      "--disable-gpu",
      `--user-data-dir=${join(root, "config", "Claude")}`,
    ],
    {
      env,
      detached: true,
      stdio: ["ignore", logFd, logFd],
    },
  );
  closeSync(logFd);
  child.unref();
  if (!child.pid) throw new Error("failed to start Claude Desktop in sandbox");
  return child.pid;
}

export async function startSandbox(opts: {
  root: string;
  watch?: boolean;
  launchApp?: boolean;
  upstreamUrl?: string;
}): Promise<SandboxState> {
  const safeRoot = assertSafeSandboxRoot(opts.root);
  const jar = resolveJar();
  for (const sub of ["home", "home/.claude", "config", "data", "cache", "runtime", "splice-home", "splice-state", "splice-config", "logs"]) {
    mkdirSync(join(safeRoot, sub), { recursive: true, mode: 0o700 });
  }
  const controlPort = await freePort();
  const headPort = await freePort();
  const disp = startDisplay(safeRoot, Boolean(opts.watch));
  await waitForDisplay(disp.display, disp.pid);
  const daemonPid = await startDaemon(safeRoot, jar, controlPort, headPort, opts.upstreamUrl);
  const desktopPid = opts.launchApp === false ? null : launchDesktop(safeRoot, disp.display);
  const state: SandboxState = {
    root: safeRoot,
    display: disp.display,
    displayPid: disp.pid,
    controlPort,
    headPort,
    daemonPid,
    desktopPid,
    jar,
    watch: Boolean(opts.watch),
  };
  writeState(state);
  return state;
}

export function captureShot(state: SandboxState, outPath: string): string {
  const abs = resolve(outPath);
  mkdirSync(dirname(abs), { recursive: true });
  const r = spawnSync(
    "ffmpeg",
    ["-y", "-loglevel", "error", "-f", "x11grab", "-video_size", "1280x800", "-i", state.display, "-frames:v", "1", abs],
    { env: sandboxEnv(state.root, state.display), encoding: "utf8", timeout: 20_000 },
  );
  if (r.status !== 0 || !existsSync(abs) || statSync(abs).size === 0) {
    throw new Error(`screenshot failed: ${r.stderr || r.stdout}`);
  }
  return abs;
}

export function runXdotool(state: SandboxState, args: string[]): void {
  const r = spawnSync("xdotool", args, {
    env: sandboxEnv(state.root, state.display),
    encoding: "utf8",
    timeout: 15_000,
  });
  if (r.status !== 0) {
    throw new Error(`xdotool ${args.join(" ")} failed: ${r.stderr || r.stdout}`);
  }
}

export function stopSandbox(root: string, removeRoot = false): void {
  const file = stateFile(root);
  if (existsSync(file)) {
    const state = readState(root);
    stopProcess(state.desktopPid);
    stopProcess(state.daemonPid);
    stopProcess(state.displayPid);
  }
  if (removeRoot && existsSync(root)) {
    rmSync(root, { recursive: true, force: true });
  }
}
