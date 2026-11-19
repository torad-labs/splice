// What the door-sessions checks share: `claude` started the way a person starts it, through the installed launcher
// (`splice-launch`, linked under the command's name), on a stand-in Claude Code that registers itself in the Claude
// folder it runs under, as Claude Code does (~/.claude/sessions/<pid>.json), and then waits. What the person sees is
// read back: what the launcher printed, the launch record splice keeps for that process, and the session's row.
// (docs/specs/door-default.md, docs/specs/door-network.md.)

import { type ChildProcess, spawn } from "node:child_process";
import { chmodSync, existsSync, mkdirSync, readFileSync, symlinkSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { type Splice, jar, until } from "../../server/lib/harness.ts";

/** What the launcher says when it could not record a launch. */
export const NOT_RECORDED = "launch ownership could not be recorded";

export interface Started {
  /** The launcher's pid, which its exec of Claude Code keeps. */
  pid: number;
  session: string;
  /** What the launcher printed on stderr, before Claude Code took over. */
  said(): string;
  /** The environment Claude Code was started in. */
  env: Record<string, string>;
  proc: ChildProcess;
  stop(): void;
}

/** A stand-in for Claude Code: registers this process in the Claude folder it runs under, writes down its environment,
 *  and waits, so the session stays live while a check reads it. */
function standIn(s: Splice): string {
  const bin = join(s.home, "stand-in-bin");
  mkdirSync(bin, { recursive: true });
  const file = join(bin, "claude");
  writeFileSync(
    file,
    `#!/bin/sh
dir=\${CLAUDE_CONFIG_DIR:-$HOME/.claude}
mkdir -p "$dir/sessions"
now=$(date +%s)
printf '{"pid":%s,"sessionId":"%s","cwd":"%s","startedAt":%s000,"updatedAt":%s000,"status":"idle"}' "$$" "$STAND_IN_SESSION" "$PWD" "$now" "$now" > "$dir/sessions/$$.json"
env > "$HOME/stand-in-env-$$.txt"
printf '%s\\n' "$$" > "$HOME/stand-in-started-$STAND_IN_SESSION"
exec sleep 600
`,
  );
  chmodSync(file, 0o755);
  return bin;
}

/** `command` typed in a terminal on `s`'s machine: the launcher under that name, Claude Code on the PATH after it.
 *  With `around`, Claude Code itself is started, as a person who goes around splice starts it. */
export async function start(s: Splice, command = "claude", around = false): Promise<Started> {
  const bin = standIn(s);
  const links = join(s.home, "commands");
  mkdirSync(links, { recursive: true });
  const link = around ? join(bin, "claude") : join(links, command);
  if (!around && !existsSync(link)) symlinkSync(join(s.home, ".local", "share", "splice", "splice-launch"), link);
  const session = crypto.randomUUID();
  const env = {
    ...s.env(),
    PATH: `${bin}:${process.env.PATH ?? "/usr/bin:/bin"}`,
    SPLICE_JAR: jar(),
    // no unit of this machine's own may answer for the check's daemon
    SPLICE_SUPERVISOR_UNIT: "splice-door-sessions-check.service",
    STAND_IN_SESSION: session,
  };
  // splice marks every launch it makes with SPLICE=1; a terminal that is itself a session splice launched carries it
  if (around) delete (env as Record<string, string>).SPLICE;
  const proc = spawn(link, [], { cwd: s.home, env, stdio: ["ignore", "pipe", "pipe"] });
  let said = "";
  proc.stderr?.on("data", (d) => (said += d));
  proc.stdout?.on("data", (d) => (said += d));
  const marker = join(s.home, `stand-in-started-${session}`);
  await until(`${command} starts Claude Code`, () => existsSync(marker) || proc.exitCode !== null, 180_000);
  if (!existsSync(marker)) throw new Error(`${command} exited ${proc.exitCode} before Claude Code started:\n${said}`);
  const pid = Number(readFileSync(marker, "utf8").trim());
  const envFile = join(s.home, `stand-in-env-${pid}.txt`);
  await until("Claude Code wrote its environment", () => existsSync(envFile));
  const started: Record<string, string> = {};
  for (const line of readFileSync(envFile, "utf8").split("\n")) {
    const i = line.indexOf("=");
    if (i > 0) started[line.slice(0, i)] = line.slice(i + 1);
  }
  return {
    pid,
    session,
    said: () => said,
    env: started,
    proc,
    stop: () => {
      try {
        process.kill(pid, "SIGKILL");
      } catch {}
    },
  };
}

/** Where the session's Claude Code sends its turns: the door's address on a door launch, else its base URL. */
export function address(x: Started): string {
  return x.env.CLAUDE_CODE_USE_MANTLE === "1" ? (x.env.ANTHROPIC_BEDROCK_MANTLE_BASE_URL ?? "") : (x.env.ANTHROPIC_BASE_URL ?? "");
}

export interface LaunchRecord {
  pid: number;
  head: string;
  baseUrl: string;
  kind: string;
}

/** The launch record splice keeps for `pid`, or null when it keeps none. */
export function launchRecord(s: Splice, pid: number): LaunchRecord | null {
  const file = join(s.state, "launch-owners", `${pid}.json`);
  return existsSync(file) ? (JSON.parse(readFileSync(file, "utf8")) as LaunchRecord) : null;
}

export interface Row {
  session_id: string;
  head: string;
  route: string;
  door?: { command: string; address: string; machine: string | null } | null;
  model?: string | null;
  served_model?: string | null;
  availability: string;
  [k: string]: unknown;
}

/** The session's row in `GET /api/sessions`, or null when the list has none. */
export async function row(s: Splice, session: string): Promise<Row | null> {
  const r = await s.api("/api/sessions");
  if (r.status !== 200) throw new Error(`/api/sessions answered ${r.status}: ${await r.text()}`);
  const j = (await r.json()) as { sessions: Row[] };
  return j.sessions.find((x) => x.session_id === session) ?? null;
}
