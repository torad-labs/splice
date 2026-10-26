// A hub call a session cancels is settled by the cancel (PR #247, round 6, finding 8).
//
// Claude Code POSTs tools/call to the hub front, the person presses Esc or a client timeout fires, and the SDK client
// POSTs notifications/cancelled for that id. A server that keeps MCP's cancellation rule never answers the cancelled
// request, so only the cancel can release the front's record of the call. What the session must see (CHANGELOG.md): a
// hub call keeps working however many calls were cancelled before it, and an update reloads a running front in place.
//
// This drives the real pair the way a session does: HTTP to the front binary, a holder binary in front of a stdio
// server, and a stand-in daemon on the control port that says where the server runs, attests its generation and
// confirms no exit, since the holder runs throughout. Link the pair first, as the gate's mcpHubBinaries leg does:
//
//   bun tools/gate slot hub -- :integrations-mcp-hub:linkHolderReleaseExecutableLinuxX64 \
//     :integrations-mcp-hub:linkFrontReleaseExecutableLinuxX64
//   bun test tools/hub-standin/cancel.test.ts
import { afterAll, expect, test } from "bun:test";
import { type ChildProcess, spawn } from "node:child_process";
import { randomBytes } from "node:crypto";
import { existsSync } from "node:fs";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { connect, createServer } from "node:net";
import { join, resolve } from "node:path";

const BUILT = resolve(import.meta.dir, "../../integrations/mcp-hub/build/bin/linuxX64");
const FRONT = join(BUILT, "frontReleaseExecutable/splice-mcp-front.kexe");
const HOLDER = join(BUILT, "holderReleaseExecutable/splice-mcp-holder.kexe");
const SERVER = "holding";
const GENERATION = "g1";
/** One more than the 256 calls the front retains, so a front that keeps cancelled calls refuses the last one. */
const CANCELLED_CALLS = 257;

const owned: { child: ChildProcess; signal: NodeJS.Signals }[] = [];
let daemon: ReturnType<typeof Bun.serve> | undefined;
let home: string | undefined;

afterAll(async () => {
  for (const { child, signal } of owned) child.kill(signal);
  await daemon?.stop(true);
  if (home !== undefined) await rm(home, { recursive: true, force: true });
});

interface Reply {
  readonly status: number;
  readonly session: string | undefined;
  readonly body: string;
}

interface Pending {
  /** The front's answer, or undefined once the connection closed without one. */
  readonly reply: Promise<Reply | undefined>;
  /** Gives the request up, as a client that dropped its id closes the connection. */
  readonly abandon: () => void;
}

function parse(data: Buffer): Reply | undefined {
  const end = data.indexOf("\r\n\r\n");
  if (end < 0) return undefined;
  const [status, ...fields] = data.subarray(0, end).toString("latin1").split("\r\n");
  const headers = new Map(fields.map((field) => {
    const colon = field.indexOf(":");
    return [field.slice(0, colon).trim().toLowerCase(), field.slice(colon + 1).trim()] as const;
  }));
  const length = Number(headers.get("content-length") ?? "0");
  if (data.length < end + 4 + length) return undefined;
  return {
    status: Number(status?.split(" ")[1]),
    session: headers.get("mcp-session-id"),
    body: data.subarray(end + 4, end + 4 + length).toString("utf8"),
  };
}

/** What a session reads from [reply]: its status and the JSON-RPC error's words, if any. */
function answered(reply: Reply | undefined): string {
  if (reply === undefined) return "closed without an answer";
  const words = (JSON.parse(reply.body || "{}") as { error?: { message?: string } }).error?.message;
  return `answered ${reply.status}${words === undefined ? "" : `: ${words}`}`;
}

async function until<T>(read: () => T | undefined, ms: number): Promise<T | undefined> {
  const deadline = performance.now() + ms;
  for (;;) {
    const value = read();
    if (value !== undefined || performance.now() >= deadline) return value;
    await Bun.sleep(5);
  }
}

function lines(child: ChildProcess, each: (line: string) => void): void {
  let partial = "";
  child.stderr?.on("data", (chunk: Buffer) => {
    const parts = (partial + chunk.toString("utf8")).split("\n");
    partial = parts.pop() ?? "";
    parts.forEach(each);
  });
}

async function freePort(): Promise<number> {
  const probe = createServer();
  await new Promise<void>((done) => probe.listen(0, "127.0.0.1", done));
  const address = probe.address();
  await new Promise<void>((done) => probe.close(() => done()));
  if (address === null || typeof address === "string") throw new Error("no loopback port was free");
  return address.port;
}

test(
  "after 257 cancelled calls a fresh hub call is answered, and the front upgrades in place",
  async () => {
    if (!existsSync(FRONT) || !existsSync(HOLDER)) throw new Error(`link the hub pair first: ${FRONT} and ${HOLDER}`);
    home = await mkdtemp("/tmp/hub-cancel-");
    const state = join(home, "state");
    const hub = join(state, "mcp-hub");
    await mkdir(hub, { recursive: true, mode: 0o700 });
    const key = randomBytes(32).toString("hex");
    await writeFile(join(hub, "access-key"), key, { mode: 0o600 });
    await writeFile(join(state, "mgmt-key"), randomBytes(32).toString("hex"), { mode: 0o600 });
    const socket = join(home, "holder.sock");

    daemon = Bun.serve({
      hostname: "127.0.0.1",
      port: 0,
      fetch(request) {
        const path = new URL(request.url).pathname;
        if (path === "/api/mcp/hub/resolve" || path === "/api/mcp/hub/start") {
          return Response.json({ socket, generation: GENERATION, shared: true });
        }
        if (path === "/api/mcp/hub/attest") return Response.json({ socket, generation: GENERATION });
        if (path === "/api/mcp/hub/listing") return new Response(null, { status: 204 });
        return new Response(null, { status: 404 });
      },
    });

    const holder = spawn(HOLDER, [socket, process.execPath, join(import.meta.dir, "holding.ts")], {
      stdio: ["ignore", "ignore", "pipe"],
    });
    owned.push({ child: holder, signal: "SIGTERM" });
    const held = new Set<number>();
    lines(holder, (line) => {
      const n = /^holding (\d+)$/.exec(line)?.[1];
      if (n !== undefined) held.add(Number(n));
    });
    expect(await until(() => (existsSync(socket) ? true : undefined), 10_000)).toBe(true);

    const port = await freePort();
    const front = spawn(FRONT, ["--hub", hub, "--control-port", String(daemon.port), "--port", String(port)], {
      stdio: ["ignore", "ignore", "pipe"],
      env: {},
    });
    owned.push({ child: front, signal: "SIGKILL" });
    const said: string[] = [];
    lines(front, (line) => said.push(line));
    expect(await until(() => said.find((line) => line.includes("serving on")), 10_000)).toBeDefined();

    const post = (body: object, session?: string): Pending => {
      const payload = JSON.stringify(body);
      const head = [
        `POST /mcp/${SERVER} HTTP/1.1`,
        "Host: 127.0.0.1",
        `Authorization: Bearer ${key}`,
        "Content-Type: application/json",
        "Accept: application/json, text/event-stream",
        `X-Splice-Launch: ${process.pid}`,
        ...(session === undefined ? [] : [`Mcp-Session-Id: ${session}`]),
        `Content-Length: ${Buffer.byteLength(payload)}`,
      ];
      const connection = connect(port, "127.0.0.1");
      connection.write(`${head.join("\r\n")}\r\n\r\n${payload}`);
      const reply = new Promise<Reply | undefined>((done) => {
        let data = Buffer.alloc(0);
        connection.on("data", (chunk: Buffer) => {
          data = Buffer.concat([data, chunk]);
          const parsed = parse(data);
          if (parsed !== undefined) {
            done(parsed);
            connection.end();
          }
        });
        connection.on("error", () => done(undefined));
        connection.on("close", () => done(undefined));
      });
      return { reply, abandon: () => connection.destroy() };
    };
    const call = (id: number, name: string, session: string | undefined): Pending =>
      post({ jsonrpc: "2.0", id, method: "tools/call", params: { name, arguments: { n: id } } }, session);
    const cancel = (id: number, session: string | undefined): Promise<Reply | undefined> =>
      post({ jsonrpc: "2.0", method: "notifications/cancelled", params: { requestId: id, reason: "Esc" } }, session).reply;
    const reaches = (id: number, pending: Pending): Promise<string | undefined> =>
      Promise.race([until(() => (held.has(id) ? "held by the server" : undefined), 10_000), pending.reply.then(answered)]);

    const init = await post({
      jsonrpc: "2.0",
      id: 0,
      method: "initialize",
      params: { protocolVersion: "2025-11-25", capabilities: {}, clientInfo: { name: "claude-code", version: "2" } },
    }).reply;
    expect(answered(init)).toBe("answered 200");
    const session = init?.session;
    expect(session).toBeDefined();
    expect(answered(await post({ jsonrpc: "2.0", method: "notifications/initialized" }, session).reply)).toBe("answered 202");

    // Each call reaches the server, is cancelled, and is given up by its client; the server never answers it.
    for (let id = 1; id <= CANCELLED_CALLS; id++) {
      const pending = call(id, "wait", session);
      expect(`call ${id} ${await reaches(id, pending)}`).toBe(`call ${id} held by the server`);
      expect(`cancel ${id} ${answered(await cancel(id, session))}`).toBe(`cancel ${id} answered 202`);
      pending.abandon();
    }

    const fresh = await call(1000, "count", session).reply;
    expect(answered(fresh)).toBe("answered 200");
    expect(JSON.parse(fresh?.body ?? "{}").result).toEqual({ content: [{ type: "text", text: "1" }] });

    // The SDK keeps a cancelled call's POST open, so the front answers it: a busy connection holds an upgrade back.
    const kept = call(1001, "wait", session);
    expect(await reaches(1001, kept)).toBe("held by the server");
    expect(answered(await cancel(1001, session))).toBe("answered 202");
    const unanswered = Bun.sleep(5_000).then(() => "still open after 5 s");
    expect(await Promise.race([kept.reply.then(answered), unanswered])).toBe("answered 202");

    // An update reloads the front with SIGUSR2; it execs in place once nothing is in flight.
    front.kill("SIGUSR2");
    const upgrade = await until(() => said.find((line) => /resumed in place|upgrade blocked/.test(line)), 20_000);
    expect(upgrade).toContain("resumed in place");
    const after = await call(1002, "count", session).reply;
    expect(answered(after)).toBe("answered 200");
    expect(JSON.parse(after?.body ?? "{}").result).toEqual({ content: [{ type: "text", text: "2" }] });
  },
  120_000,
);
