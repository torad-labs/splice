// The probe session: a real Claude Code session launched through a console-own Splice, the way a person starts one
// (the command, linked to the splice-launch shim, run in a terminal), in a tmux of its own on the home's own socket.
// It talks only to the stand-in, so a write the console sends it (a message, a stop) is tried on it and never on a
// session the person runs.
import { spawnSync } from "node:child_process";
import { chmodSync, copyFileSync, existsSync, mkdirSync, openSync, readdirSync, readSync, realpathSync, symlinkSync } from "node:fs";
import { userInfo } from "node:os";
import { basename, join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";
import type { Daemon } from "./splice.ts";

const SESSION = "probe";
export const socketOf = (tmuxDir: string) => join(tmuxDir, "probe.sock");

function tmux(d: Daemon, ...args: string[]): { code: number; out: string } {
  const r = spawnSync("tmux", ["-S", socketOf(d.h.tmux), ...args], { env: d.env(), encoding: "utf8", timeout: 20_000 });
  return { code: r.status ?? -1, out: `${r.stdout ?? ""}${r.stderr ?? ""}` };
}

const pane = (d: Daemon) => tmux(d, "capture-pane", "-p", "-t", SESSION).out;
const keys = (d: Daemon, ...k: string[]) => tmux(d, "send-keys", "-t", SESSION, ...k);

/** Whether [file] is a launcher of some Splice (the shim, or a wrapper that hands a command to one): running it would
 *  launch through the person's own Splice, never this home's. */
function launcher(file: string): boolean {
  const real = realpathSync(file);
  if (/^(splice-launch|hostshield|hostshield-path)$/.test(basename(real))) return true;
  const head = Buffer.alloc(200);
  const fd = openSync(real, "r");
  const n = readSync(fd, head, 0, head.length, 0);
  return /splice-launch/.test(head.subarray(0, n).toString("latin1"));
}

/** Claude Code itself: $CONSOLE_OWN_CLAUDE, else the first `claude` on PATH that is no Splice's launcher, else the newest
 *  native install. */
function realClaude(): string {
  if (process.env.CONSOLE_OWN_CLAUDE) return process.env.CONSOLE_OWN_CLAUDE;
  for (const dir of (process.env.PATH ?? "").split(":")) {
    const at = join(dir, "claude");
    if (dir && existsSync(at) && !launcher(at)) return realpathSync(at);
  }
  const versions = join(userInfo().homedir, ".local", "share", "claude", "versions");
  const newest = existsSync(versions) ? readdirSync(versions).sort((a, b) => a.localeCompare(b, "en", { numeric: true })).pop() : undefined;
  if (newest) return join(versions, newest);
  throw new Error("no Claude Code found: set CONSOLE_OWN_CLAUDE to its binary");
}

/** Puts [command] on the home's PATH as this home's launcher, the way `splice install` links a command, and `claude` as
 *  Claude Code itself, so the launcher finds them here before anything on the person's PATH. */
function link(d: Daemon, command: string): void {
  const shim = join(d.h.root, ".local", "share", "splice", "splice-launch");
  if (!existsSync(shim)) {
    mkdirSync(join(d.h.root, ".local", "share", "splice"), { recursive: true });
    copyFileSync(join(layout().repoRoot, "app/src/main/dist/bin/splice-launch"), shim);
    chmodSync(shim, 0o755);
  }
  for (const [name, target] of [[command, shim], ["claude", realClaude()]] as const) {
    const at = join(d.h.bin, name);
    if (!existsSync(at)) symlinkSync(target, at);
  }
}

// how long each step of the probe session may take: five minutes, since the Mac it runs on is often busy with other
// campaigns' checks, and Claude Code's first start and first answer are its slowest steps
const WAIT_MS = 300_000;

/** Waits until the pane shows [want], answering Claude Code's first-run questions (the text style, trusting the
 *  folder) the way a person would. */
async function until(d: Daemon, want: RegExp, ms: number): Promise<string> {
  const end = Date.now() + ms;
  let p = "";
  while (Date.now() < end) {
    p = pane(d);
    if (want.test(p)) return p;
    if (/trust this folder|do you trust/i.test(p)) keys(d, /❯[ \t]*\d?\.?\s*Yes/.test(p) ? "Enter" : "Down");
    else if (/text style|theme to use|choose the text/i.test(p)) keys(d, "Enter");
    else if (/press enter to continue|security notes/i.test(p)) keys(d, "Enter");
    await Bun.sleep(1000);
  }
  throw new Error(`the probe session never showed ${want}; its screen:\n${p}`);
}

/** Starts the probe session on [command] and has it answer once from the stand-in. Answers its session id. */
export async function startProbe(d: Daemon, command: string): Promise<string> {
  link(d, command);
  const started = tmux(d, "new-session", "-d", "-s", SESSION, "-x", "200", "-y", "50", "-c", d.h.work, `${command}; sleep 600`);
  if (started.code !== 0) throw new Error(`tmux did not start the probe session: ${started.out}`);
  // ready to type: the old footer hint, or (Claude Code 2.1 on) the empty input line between its two rules
  await until(d, /for shortcuts|\? for help|shift\+tab to cycle|─{8,}[ \t]*\r?\n❯[ \t\u00a0]*\r?\n─{8,}/i, WAIT_MS);
  keys(d, "-l", "Say hello to the harbor.");
  await Bun.sleep(300);
  keys(d, "Enter");
  await until(d, /Hello from the chat mock/, WAIT_MS);
  for (let i = 0; i < WAIT_MS / 1000; i++) {
    const r = await d.api("/api/sessions");
    const live = ((await r.json()) as { sessions?: { session_id?: string; availability?: string }[] }).sessions?.find((s) => s.availability === "live");
    if (live?.session_id) return live.session_id;
    await Bun.sleep(1000);
  }
  throw new Error("the probe session answered but splice lists no live session");
}

export function stopProbe(tmuxDir: string, env: Record<string, string>): void {
  spawnSync("tmux", ["-S", socketOf(tmuxDir), "kill-server"], { env, timeout: 10_000 });
}
