#!/usr/bin/env bun
/** Loopback control+head for heads-e2e-selftest.sh.
 *
 *  Two sockets, never a real daemon and never a vendor. The control plane answers
 *  /health and /api/heads so heads-e2e.sh will not cold-start splice.jar. The head
 *  answers /v1/models, /v1/messages (Anthropic SSE with `event:` lines, two
 *  deltas), and /v1/messages/count_tokens. Authorization on BOTH planes is
 *  appended to --record as JSONL so a skip/FATAL arm that still probes is visible.
 *
 *  V4-145: converted to TypeScript (bun). THIS ONE CANNOT USE Bun.serve, and the reason is a
 *  measured capability gap rather than a preference: the DR-113 arm closes a connection with NO
 *  HTTP response at all, so the harness's curl sees an empty reply (a transport rc, not a payload).
 *  I probed both Bun.serve escape hatches and neither is that — returning a Response whose stream
 *  errors still sends headers (curl exits 0), and returning a non-Response makes Bun answer with
 *  its own "Welcome to Bun!" body, which is the opposite of an empty reply. So both planes are raw
 *  sockets over Bun.listen and the HTTP is written here, which also buys exact control over
 *  Content-Length, keep-alive, and that bare close. The artifact is the SERVED BYTES plus the
 *  RECORD FILE, and both were differentialled against the Python server's own raw output.
 */
import { appendFileSync, existsSync, writeFileSync } from "node:fs";

function sse(duplicateStop = false): string {
  // Two deltas: stream_probe's buffering check only fires at >= 4 in one chunk.
  const frames: [string, string][] = [
    ["message_start", '{"type":"message_start","message":{"usage":{"input_tokens":2}}}'],
    ["content_block_start",
      '{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}'],
    ["content_block_delta",
      '{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"1, "}}'],
    ["content_block_delta",
      '{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"2 END"}}'],
    ["content_block_stop", '{"type":"content_block_stop","index":0}'],
    ["message_delta",
      '{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}'],
    ["message_stop", '{"type":"message_stop"}'],
  ];
  if (duplicateStop) {
    frames.push(["message_stop", '{"type":"message_stop"}']);
  }
  return frames.map(([n, d]) => `event: ${n}\ndata: ${d}\n\n`).join("");
}

/** Python json.dumps for the shapes this server emits — the separators are ", " and ": ", which is
 *  what the record file and every body carry. Kept local rather than importing python-json because the
 *  values here are literals of a fixed shape: no floats, no integer-like keys, no non-ASCII. */
function dumps(v: unknown): string {
  if (v === null || v === undefined) return "null";
  if (v === true) return "true";
  if (v === false) return "false";
  if (typeof v === "number") return String(v);
  if (typeof v === "string") return JSON.stringify(v);
  if (Array.isArray(v)) return "[" + v.map(dumps).join(", ") + "]";
  return (
    "{" +
    Object.keys(v as Record<string, unknown>)
      .map((k) => `${JSON.stringify(k)}: ${dumps((v as Record<string, unknown>)[k])}`)
      .join(", ") +
    "}"
  );
}

interface Loop {
  record: string;
  headKey: string;
  headPort: number;
  duplicateStopFile: string | null;
  unknownKindHead: string | null;
  countTokensDropFile: string | null;
}

function append(loop: Loop, plane: string, path: string, authorization: string | null): void {
  const row = { plane, path, authorization: authorization ?? "" };
  appendFileSync(loop.record, dumps(row) + "\n", "utf8");
}

interface Request {
  method: string;
  path: string;
  headers: Record<string, string>;
  body: string;
}

const STATUS_TEXT: Record<number, string> = { 200: "OK", 404: "Not Found" };

function response(status: number, contentType: string, body: string): string {
  return (
    `HTTP/1.1 ${status} ${STATUS_TEXT[status] ?? "OK"}\r\n` +
    `Content-Type: ${contentType}\r\n` +
    `Content-Length: ${Buffer.byteLength(body)}\r\n` +
    "\r\n" +
    body
  );
}

function json(status: number, payload: unknown): string {
  return response(status, "application/json", dumps(payload));
}

/** Returns the raw response, or null when the connection must be closed with NO response (DR-113). */
function handle(loop: Loop, plane: string, req: Request): string | null {
  append(loop, plane, req.path, req.headers["authorization"] ?? null);

  if (req.method === "GET") {
    if (plane === "control" && req.path === "/health") {
      return json(200, { ok: true });
    }
    if (plane === "control" && req.path === "/api/heads") {
      const heads: unknown[] = [
        {
          key: loop.headKey,
          label: loop.headKey,
          port: loop.headPort,
          healthy: true,
          authKind: "client",
        },
      ];
      // DR-49: a second head whose authKind the harness does not recognize — every other arm
      // filters it out via --head, so only the FATAL arm ever selects it.
      if (loop.unknownKindHead) {
        heads.push({
          key: loop.unknownKindHead,
          label: loop.unknownKindHead,
          port: loop.headPort,
          healthy: true,
          authKind: "mystery-kind",
        });
      }
      return json(200, { heads });
    }
    if (plane === "head" && req.path === "/v1/models") {
      return json(200, { data: [{ id: `${loop.headKey}--claude-haiku-4-5` }] });
    }
    return json(404, { error: "not found" });
  }

  if (plane === "head" && req.path === "/v1/messages") {
    const duplicateStop = loop.duplicateStopFile !== null && existsSync(loop.duplicateStopFile);
    return response(200, "text/event-stream", sse(duplicateStop));
  }
  if (plane === "head" && req.path === "/v1/messages/count_tokens") {
    if (loop.countTokensDropFile !== null && existsSync(loop.countTokensDropFile)) {
      // DR-113 arm: transport-level death — close with no HTTP response at all, so the harness's
      // curl sees an empty reply (a transport rc, not a payload).
      return null;
    }
    return json(200, { input_tokens: 3 });
  }
  return json(404, { error: "not found" });
}

type Listener = { port: number; stop: (force?: boolean) => void };

function serve(loopRef: () => Loop, plane: string): Listener {
  const buffers = new WeakMap<object, string>();
  const server = Bun.listen({
    hostname: "127.0.0.1",
    port: 0,
    socket: {
      data(socket, data) {
        const prev = buffers.get(socket) ?? "";
        const buf = prev + data.toString("latin1");
        const headerEnd = buf.indexOf("\r\n\r\n");
        if (headerEnd < 0) {
          buffers.set(socket, buf);
          return;
        }
        const head = buf.slice(0, headerEnd);
        const lines = head.split("\r\n");
        const [method, target] = lines[0].split(" ");
        const headers: Record<string, string> = {};
        for (const line of lines.slice(1)) {
          const idx = line.indexOf(":");
          if (idx > 0) headers[line.slice(0, idx).trim().toLowerCase()] = line.slice(idx + 1).trim();
        }
        const want = Number(headers["content-length"] ?? "0") || 0;
        const body = buf.slice(headerEnd + 4);
        if (body.length < want) {
          buffers.set(socket, buf);
          return;
        }
        buffers.set(socket, body.slice(want));
        const path = (target ?? "/").split("?")[0];
        const loop = loopRef();
        const out = handle(loop, plane, { method, path, headers, body: body.slice(0, want) });
        if (out === null) {
          socket.end();
          return;
        }
        socket.write(out);
      },
      close(socket) {
        buffers.delete(socket);
      },
      error(socket) {
        buffers.delete(socket);
      },
    },
  });
  return { port: server.port, stop: (force?: boolean) => server.stop(force) };
}

function parseArgs(argv: string[]): Record<string, string | null> | null {
  const known = new Set([
    "--record", "--ready-file", "--head-key", "--duplicate-stop-file",
    "--unknown-kind-head", "--count-tokens-drop-file",
  ]);
  const out: Record<string, string | null> = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (!known.has(a)) return null;
    const v = argv[++i];
    if (v === undefined) return null;
    out[a.slice(2)] = v;
  }
  if (!out["record"] || !out["ready-file"]) return null;
  return out;
}

function main(): number {
  const args = parseArgs(process.argv.slice(2));
  if (args === null) {
    process.stderr.write("usage: loopback_control.ts --record PATH --ready-file PATH [--head-key K]\n");
    process.stderr.write("       [--duplicate-stop-file PATH] [--unknown-kind-head K]\n");
    process.stderr.write("       [--count-tokens-drop-file PATH]\n");
    return 2;
  }
  const record = args["record"] as string;
  const duplicateStopFile = args["duplicate-stop-file"] ?? null;
  const unknownKindHead = args["unknown-kind-head"] ?? null;
  const countTokensDropFile = args["count-tokens-drop-file"] ?? null;

  // Bind the head first so /api/heads can advertise the real port, then hand the control plane the
  // completed Loop. READY is written once BOTH sockets are listening, so the selftest cannot race a
  // refused /health and cold-start the installed splice.jar.
  writeFileSync(record, "", "utf8");
  let loop: Loop = {
    record,
    headKey: args["head-key"] ?? "claude-splice",
    headPort: 0,
    duplicateStopFile,
    unknownKindHead,
    countTokensDropFile,
  };
  const headSrv = serve(() => loop, "head");
  loop = { ...loop, headPort: headSrv.port };
  const controlSrv = serve(() => loop, "control");

  writeFileSync(args["ready-file"] as string, `CONTROL=${controlSrv.port}\nHEAD=${headSrv.port}\n`, "utf8");
  process.stdout.write(`READY control=${controlSrv.port} head=${headSrv.port}\n`);
  return 0;
}

if (import.meta.main) {
  const rc = main();
  if (rc !== 0) {
    process.exit(rc);
  }
  // Python's main() ends on `threading.Event().wait()` and stays alive until interrupted; the
  // equivalent here is simply NOT exiting. process.exit() would tear the listeners down the instant
  // main returned, which is what the first differential run measured as connection-refused on every
  // request — Bun keeps the loop running on the listeners for as long as we let it.
}
