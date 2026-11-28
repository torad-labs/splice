// The home a console-own Splice lives in: a folder of its own under /tmp holding its HOME, config, state, logs and tmux
// socket, and every process it runs. Nothing of it may resolve into the person's own Splice, Claude Code or keyring,
// and it never takes the control port 3096.
import { spawnSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import { existsSync, mkdirSync, realpathSync, writeFileSync } from "node:fs";
import { userInfo } from "node:os";
import { dirname, join, resolve, sep } from "node:path";
import { daemonEnv } from "../e2e/src/daemon-env.ts";

/** The prefix every console-own home has, so `down` removes nothing else. */
export const PREFIX = "splice-console-own-";
/** Where every console-own home is made: directly under /tmp (its real path is /private/tmp on macOS), never under
 *  os.tmpdir(), which on macOS is a per-user /var/folders/… path. */
export const TMP = "/tmp";
/** The control port of the Splice that runs on the machine, which a home of its own never takes. */
export const LIVE_CONTROL_PORT = 3096;

export interface Home {
  root: string;
  state: string;
  config: string;
  tmux: string;
  bin: string;
  work: string;
  log: string;
  jar: string;
}

export function homeAt(root: string): Home {
  return {
    root,
    state: join(root, ".splice", "state"),
    config: join(root, ".config", "splice", "splice.toml"),
    tmux: join(root, "tmux"),
    bin: join(root, "bin"),
    work: join(root, "work", "harbor"),
    log: join(root, "daemon.log"),
    jar: join(root, "splice.jar"),
  };
}

/** Where the person's own Splice, Claude Code and keyring live under the account home [own]. */
function guarded(own: string): string[] {
  return [".splice", ".claude-codex", ".config/splice", ".claude", ".claude.json", ".local/share/keyrings", ".local/share/splice"].map((p) => join(own, p));
}

/** [p] as it resolves on disk: its nearest existing ancestor's real path, with the rest of it after. */
function real(p: string): string {
  let at = resolve(p);
  const rest: string[] = [];
  while (!existsSync(at)) {
    rest.unshift(at.slice(dirname(at).length + 1));
    at = dirname(at);
  }
  return join(realpathSync(at), ...rest);
}

const inside = (p: string, dir: string) => p === dir || p.startsWith(dir + sep);

/** Refuses, naming the path, when any path this home would write resolves into the person's own state. [account] is
 *  the account's own home, read from the system (never $HOME, which a caller may have pointed anywhere); only a test
 *  names another, so that it guards a made-up home and writes nothing in the real one. */
export function refuseForeign(h: Home, account: string = userInfo().homedir): void {
  const protectedDirs = guarded(account).map(real);
  for (const p of Object.values(h)) {
    const r = real(p);
    const hit = protectedDirs.find((d) => inside(r, d) || inside(d, r));
    if (hit) throw new Error(`refusing: ${p} resolves into ${hit}, which belongs to the person's own setup`);
    if (!inside(r, realpathSync(TMP))) throw new Error(`refusing: ${p} is outside ${TMP}`);
  }
}

/** A new home under /tmp. Every refusal is decided on its paths before anything is made, so a refused home leaves no
 *  folder behind; the folder itself is then made exclusively, so two homes never share one. */
export function makeHome(prefix = PREFIX): Home {
  const h = homeAt(join(TMP, prefix + randomBytes(6).toString("hex")));
  refuseForeign(h);
  mkdirSync(h.root, { mode: 0o700 });
  for (const d of [h.state, dirname(h.config), h.tmux, h.bin, h.work]) mkdirSync(d, { recursive: true });
  return h;
}

/** [path] when it is a console-own home: a folder directly under /tmp (by its real path) with the console-own prefix. */
export function isOwnHome(path: string): boolean {
  const root = resolve(path);
  return root.split(sep).pop()!.startsWith(PREFIX) && existsSync(dirname(root)) && realpathSync(dirname(root)) === realpathSync(TMP);
}

// what would point a process here at the person's own Splice, Claude Code, account, display or session bus
const FOREIGN = /^(ANTHROPIC_|CLAUDE_|OTEL_|LISTEN_|TMUX|XDG_|DBUS_|BROWSER$|DISPLAY$|WAYLAND_DISPLAY$|SSH_AUTH_SOCK$)/;

/** The environment every process of this home runs in: the caller's, without anything that names another Splice, the
 *  person's Claude Code, keyring or session bus, and then this home's own paths. */
export function homeEnv(h: Home, more: Record<string, string> = {}): Record<string, string> {
  const base = Object.fromEntries(Object.entries(daemonEnv({})).filter(([k]) => !FOREIGN.test(k)));
  return {
    ...base,
    HOME: h.root,
    // this home's commands first; never the person's own wrapped heads, which launch through the person's Splice
    PATH: [h.bin, ...(process.env.PATH ?? "/usr/bin:/bin").split(":").filter((d) => d && !/\/hostshield\/heads\/?$/.test(d))].join(":"),
    DISABLE_AUTOUPDATER: "1",
    SPLICE_CONFIG: h.config,
    SPLICE_STATE_DIR: h.state,
    XDG_CONFIG_HOME: join(h.root, ".config"),
    XDG_DATA_HOME: join(h.root, ".local", "share"),
    XDG_STATE_HOME: join(h.root, ".local", "state"),
    XDG_CACHE_HOME: join(h.root, ".cache"),
    TMUX_TMPDIR: h.tmux,
    // the launcher looks for a supervisor unit by this name: one that cannot exist, so it never meets the person's
    SPLICE_SUPERVISOR_UNIT: `${PREFIX}${h.root.slice(-6)}.service`,
    ...more,
  };
}

/** Every process whose environment names this home's state: the daemon, the stand-in, the probe session and its tmux.
 *  Read from ps with each process's environment, which Linux and macOS both give (macOS has no /proc): BSD ps takes -E,
 *  procps the e flag. */
export function pidsOf(h: Home): number[] {
  const mark = `SPLICE_STATE_DIR=${h.state}`;
  const args = process.platform === "darwin" ? ["-E", "-ax", "-ww", "-o", "pid=,command="] : ["axeww", "-o", "pid=,command="];
  const r = spawnSync("ps", args, { encoding: "utf8", maxBuffer: 64 * 1024 * 1024, timeout: 10_000 });
  return (r.stdout ?? "").split("\n")
    .map((line) => /^\s*(\d+)\s(.*)$/.exec(line))
    .filter((m): m is RegExpExecArray => m !== null && Number(m[1]) !== process.pid && (` ${m[2]} `).includes(` ${mark} `))
    .map((m) => Number(m[1]));
}

export function writeText(p: string, text: string): void {
  mkdirSync(dirname(p), { recursive: true });
  writeFileSync(p, text);
}
