// LAN share, phase 2, milestone 6 (docs/specs/lan-share.md): which registry records a Claude Code session lists in
// ListAgents and delivers SendMessage to, when one process holds several. Mirrors of another machine's sessions are
// hosted by the daemon, one socket each, and each needs a registry record whose pid is alive: this probe says whether
// those records can all anchor on the daemon's own pid, or each needs a process of its own.
//
// Run: bun tools/probes/claude-code-mirror-anchor.ts <log> [seconds]
// It registers four records for as long as it runs (default 300 s), then removes them:
//   a  <pid>.json                  the usual name, this process's pid and socket
//   b  <pid>.anchor-b.json         a second record for the same pid, its own socket and key
//   c  anchor-c.json               a record whose file is not named after any pid
//   d  <sleeper pid>.json          a record anchored on a child process's pid, the socket still held here
// A live Claude Code session then calls ListAgents and SendMessage to each name (splice-anchor-a … -d); every
// connection, auth line and frame each socket receives is written to <log> as one JSON line.
import { createHash, randomBytes } from "node:crypto";
import { appendFileSync, chmodSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { createServer, type Server } from "node:net";
import { homedir } from "node:os";
import { join } from "node:path";

const [logArg, secondsArg] = process.argv.slice(2);
if (!logArg) throw new Error("usage: bun tools/probes/claude-code-mirror-anchor.ts <log> [seconds]");
const logPath: string = logArg;
const seconds = Number(secondsArg ?? 300);
const sessions = join(homedir(), ".claude", "sessions");
const socks = "/tmp/cc-socks";
mkdirSync(socks, { recursive: true, mode: 0o700 });

function procStart(pid: number): string {
  const ps = Bun.spawnSync(["ps", "-o", "lstart=", "-p", String(pid)], { env: { ...process.env, TZ: "UTC", LC_ALL: "C" } });
  return ps.stdout.toString().trim();
}

function log(entry: Record<string, unknown>) {
  appendFileSync(logPath, JSON.stringify({ at: new Date().toISOString(), ...entry }) + "\n");
}

const sleeper = Bun.spawn(["sleep", String(seconds + 30)]);
const self = process.pid;
const arms = [
  { arm: "a", pid: self, file: `${self}.json` },
  { arm: "b", pid: self, file: `${self}.anchor-b.json` },
  { arm: "c", pid: self, file: "anchor-c.json" },
  { arm: "d", pid: sleeper.pid, file: `${sleeper.pid}.json` },
];
const written: string[] = [];
const servers: Server[] = [];

for (const { arm, pid, file } of arms) {
  const socket = join(socks, `${self}-anchor-${arm}.sock`);
  rmSync(socket, { force: true });
  const token = randomBytes(16).toString("hex");
  const server = createServer((connection) => {
    let buffered = "";
    log({ arm, event: "connected" });
    connection.on("data", (chunk) => {
      buffered += chunk.toString();
      let newline: number;
      while ((newline = buffered.indexOf("\n")) >= 0) {
        const line = buffered.slice(0, newline);
        buffered = buffered.slice(newline + 1);
        const frame = JSON.parse(line);
        if (frame.type === "auth") log({ arm, event: "auth", tokenMatches: frame.token === token });
        else log({ arm, event: "frame", type: frame.type, from: frame.from, content: String(frame.message?.content ?? "").slice(0, 300) });
      }
    });
    connection.on("end", () => log({ arm, event: "closed", rest: buffered.slice(0, 300) }));
  });
  server.listen(socket, () => chmodSync(socket, 0o600));
  servers.push(server);
  const start = procStart(pid);
  const now = Date.now();
  const keyFile = join(sessions, `${pid}.${createHash("sha256").update(socket).digest("hex")}.key`);
  writeFileSync(keyFile, JSON.stringify({ peerToken: token, procStart: start, pidDomain: "darwin" }), { mode: 0o600 });
  const record = {
    pid, sessionId: crypto.randomUUID(), cwd: process.cwd(), startedAt: now, procStart: start, version: "splice-anchor/0",
    peerProtocol: 1, peerFeatures: [], kind: "splice-anchor", entrypoint: "splice-anchor", pidDomain: "darwin",
    messagingSocketPath: socket, name: `splice-anchor-${arm}`, nameSource: "user", nameSince: now, status: "idle",
    updatedAt: now, statusUpdatedAt: now,
  };
  writeFileSync(join(sessions, file), JSON.stringify(record), { mode: 0o644 });
  written.push(join(sessions, file), keyFile, socket);
  log({ arm, event: "registered", file, pid, socket, procStart: start });
}

function cleanup() {
  for (const server of servers) server.close();
  for (const path of written) rmSync(path, { force: true });
  sleeper.kill();
  log({ event: "removed" });
  process.exit(0);
}
process.on("SIGTERM", cleanup);
process.on("SIGINT", cleanup);
setTimeout(cleanup, seconds * 1000);
