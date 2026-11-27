import { dlopen, ptr } from "bun:ffi";
import { readSync, writeSync } from "node:fs";

// Bun.spawn reaps its child as soon as it exits. Here the host's unreaped PID
// pins the group until after the last signal, even if the host goes first.
const symbols = {
  setsid: { returns: "i32", args: [] },
  posix_spawnattr_init: { returns: "i32", args: ["ptr"] },
  posix_spawnattr_setflags: { returns: "i32", args: ["ptr", "i16"] },
  posix_spawnattr_setpgroup: { returns: "i32", args: ["ptr", "i32"] },
  posix_spawnattr_destroy: { returns: "i32", args: ["ptr"] },
  posix_spawn: { returns: "i32", args: ["ptr", "ptr", "ptr", "ptr", "ptr", "ptr"] },
  poll: { returns: "i32", args: ["ptr", "u32", "i32"] },
  waitid: { returns: "i32", args: ["i32", "i32", "ptr", "i32"] },
  waitpid: { returns: "i32", args: ["i32", "ptr", "i32"] },
  kill: { returns: "i32", args: ["i32", "i32"] },
} as const;

const darwin = process.platform === "darwin";
function libc() {
  if (darwin) return dlopen("/usr/lib/libSystem.B.dylib", symbols).symbols;
  try {
    return dlopen("libc.so.6", symbols).symbols;
  } catch {
    const arch = process.arch === "arm64" ? "aarch64" : "x86_64";
    return dlopen(`/lib/ld-musl-${arch}.so.1`, symbols).symbols;
  }
}

function requireZero(code: number, operation: string) {
  if (code !== 0) throw new Error(`${operation} failed (${code})`);
}

const c = libc();
const WNOHANG = 1;
const WEXITED = 4;
const WNOWAIT = darwin ? 0x20 : 0x1000000;
const P_PID = 1;
const SIGTERM = 15;
const SIGKILL = 9;
const POLL_MS = 200;
const STOP_HOOK_MS = 2100;
const TERM_GRACE_MS = 250;
const REAP_MS = 1000;

function spawnHost() {
  const command = process.argv.slice(2);
  const encoder = new TextEncoder();
  function vector(strings: string[]) {
    const bytes = strings.map(s => encoder.encode(s + "\0"));
    return { bytes, pointers: new BigUint64Array([...bytes.map(b => BigInt(ptr(b))), 0n]) };
  }
  const argv = vector(command);
  const envp = vector(Object.entries(process.env).map(([key, value]) => `${key}=${value}`));
  // Pointer-aligned storage for libc's opaque posix_spawnattr_t (a pointer on macOS).
  const attrs = new BigUint64Array(128);
  const child = new Int32Array(1);
  requireZero(c.posix_spawnattr_init(ptr(attrs)), "posix_spawnattr_init");
  try {
    requireZero(c.posix_spawnattr_setflags(ptr(attrs), 2), "POSIX_SPAWN_SETPGROUP");
    requireZero(c.posix_spawnattr_setpgroup(ptr(attrs), 0), "set child group");
    requireZero(c.posix_spawn(
      ptr(child), ptr(argv.bytes[0]!), null, ptr(attrs), ptr(argv.pointers), ptr(envp.pointers),
    ), "start mod host");
  } finally {
    c.posix_spawnattr_destroy(ptr(attrs));
  }
  return child[0]!;
}

const status = new Int32Array(1);
const info = new BigUint64Array(16); // siginfo_t; si_signo is the first int on Linux and macOS
const poll = new Int32Array(2); // pollfd: fd 0, events 0 (POLLHUP is unconditional), revents 0
let stopping = false;
process.on("SIGTERM", () => { stopping = true; });

function exited(host: number): boolean {
  info.fill(0n);
  requireZero(c.waitid(P_PID, host, ptr(info), WNOHANG | WEXITED | WNOWAIT), "watch mod host");
  return Number(info[0]! & 0xffffffffn) !== 0;
}

async function stopHost(host: number) {
  // waitid above observes but does not reap. Only this function waits the PID out.
  exited(host);
  c.kill(-host, SIGTERM);
  await Bun.sleep(TERM_GRACE_MS);
  exited(host);
  c.kill(-host, SIGKILL);
  const until = performance.now() + REAP_MS;
  do {
    if (c.waitpid(host, ptr(status), WNOHANG) === host) {
      return (status[0]! & 127) === 0 ? status[0]! >> 8 : 128 + (status[0]! & 127);
    }
    await Bun.sleep(20);
  } while (performance.now() < until);
  return 128 + SIGKILL;
}

async function guard(): Promise<number> {
  if (c.setsid() < 0) throw new Error("could not isolate the mod session");
  // The daemon records our identity before it opens this gate.
  if (readSync(0, new Uint8Array(1), 0, 1, null) !== 1) return 0;
  const host = spawnHost();
  try {
    writeSync(1, JSON.stringify({ type: "guardian", pid: host }) + "\n");
    while (!stopping && !exited(host)) {
      if (c.poll(ptr(poll), 1, POLL_MS) > 0) {
        // The host reads the same pipe; do not consume any of its protocol bytes.
        const until = performance.now() + STOP_HOOK_MS;
        while (!stopping && !exited(host) && performance.now() < until) await Bun.sleep(20);
        break;
      }
      await Bun.sleep(0);
    }
  } catch (error) {
    const code = await stopHost(host);
    console.error(error);
    return code;
  }
  return stopHost(host);
}

process.exitCode = await guard();
