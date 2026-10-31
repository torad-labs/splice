#!/usr/bin/env bun
/** http.server.BaseHTTPRequestHandler and http.client, as the code-mode harnesses used them.
 *
 *  WHY NOT node:http. The code-mode proxy and mock were Python HTTP servers, and what the daemon
 *  under test sees from them is part of the check: the proxy forwards the vendor's bytes verbatim
 *  on a `Connection: close` response with no framing (node:http would chunk-encode them), the mock
 *  answers HTTP/1.0 and closes after every response, a rejected request gets send_error()'s exact
 *  status line and HTML body, and a handler that raises past its own except clauses drops the
 *  connection with nothing written. So the server here is BaseHTTPRequestHandler's
 *  handle_one_request/parse_request/send_* transcribed over a raw socket, and the client raises
 *  the http.client/socket exception CLASSES (ConnectionRefusedError, RemoteDisconnected,
 *  TimeoutError...) whose names the harness receipts record.
 */
import { connect as netConnect, createServer as netCreateServer, type Server, type Socket } from "node:net";
import { request as httpsRequest } from "node:https";
import {
  BadStatusLine, BrokenPipeError, IncompleteRead, OSError, osError, pyRepr,
  RemoteDisconnected, TimeoutError,
} from "./python-values.ts";

// http.server's status table (BaseHTTPRequestHandler.responses, CPython 3.13).
const RESPONSES: Record<number, [string, string]> = {
  100: ["Continue", "Request received, please continue"],
  101: ["Switching Protocols", "Switching to new protocol; obey Upgrade header"],
  102: ["Processing", ""],
  103: ["Early Hints", ""],
  200: ["OK", "Request fulfilled, document follows"],
  201: ["Created", "Document created, URL follows"],
  202: ["Accepted", "Request accepted, processing continues off-line"],
  203: ["Non-Authoritative Information", "Request fulfilled from cache"],
  204: ["No Content", "Request fulfilled, nothing follows"],
  205: ["Reset Content", "Clear input form for further input"],
  206: ["Partial Content", "Partial content follows"],
  207: ["Multi-Status", ""],
  208: ["Already Reported", ""],
  226: ["IM Used", ""],
  300: ["Multiple Choices", "Object has several resources -- see URI list"],
  301: ["Moved Permanently", "Object moved permanently -- see URI list"],
  302: ["Found", "Object moved temporarily -- see URI list"],
  303: ["See Other", "Object moved -- see Method and URL list"],
  304: ["Not Modified", "Document has not changed since given time"],
  305: ["Use Proxy", "You must use proxy specified in Location to access this resource"],
  307: ["Temporary Redirect", "Object moved temporarily -- see URI list"],
  308: ["Permanent Redirect", "Object moved permanently -- see URI list"],
  400: ["Bad Request", "Bad request syntax or unsupported method"],
  401: ["Unauthorized", "No permission -- see authorization schemes"],
  402: ["Payment Required", "No payment -- see charging schemes"],
  403: ["Forbidden", "Request forbidden -- authorization will not help"],
  404: ["Not Found", "Nothing matches the given URI"],
  405: ["Method Not Allowed", "Specified method is invalid for this resource"],
  406: ["Not Acceptable", "URI not available in preferred format"],
  407: ["Proxy Authentication Required", "You must authenticate with this proxy before proceeding"],
  408: ["Request Timeout", "Request timed out; try again later"],
  409: ["Conflict", "Request conflict"],
  410: ["Gone", "URI no longer exists and has been permanently removed"],
  411: ["Length Required", "Client must specify Content-Length"],
  412: ["Precondition Failed", "Precondition in headers is false"],
  413: ["Content Too Large", "Content is too large"],
  414: ["URI Too Long", "URI is too long"],
  415: ["Unsupported Media Type", "Entity body in unsupported format"],
  416: ["Range Not Satisfiable", "Cannot satisfy request range"],
  417: ["Expectation Failed", "Expect condition could not be satisfied"],
  418: ["I'm a Teapot", "Server refuses to brew coffee because it is a teapot."],
  421: ["Misdirected Request", "Server is not able to produce a response"],
  422: ["Unprocessable Content", ""],
  423: ["Locked", ""],
  424: ["Failed Dependency", ""],
  425: ["Too Early", ""],
  426: ["Upgrade Required", ""],
  428: ["Precondition Required", "The origin server requires the request to be conditional"],
  429: ["Too Many Requests", "The user has sent too many requests in a given amount of time (\"rate limiting\")"],
  431: ["Request Header Fields Too Large", "The server is unwilling to process the request because its header fields are too large"],
  451: ["Unavailable For Legal Reasons", "The server is denying access to the resource as a consequence of a legal demand"],
  500: ["Internal Server Error", "Server got itself in trouble"],
  501: ["Not Implemented", "Server does not support this operation"],
  502: ["Bad Gateway", "Invalid responses from another server/proxy"],
  503: ["Service Unavailable", "The server cannot process the request due to a high load"],
  504: ["Gateway Timeout", "The gateway server did not receive a timely response"],
  505: ["HTTP Version Not Supported", "Cannot fulfill request"],
  506: ["Variant Also Negotiates", ""],
  507: ["Insufficient Storage", ""],
  508: ["Loop Detected", ""],
  510: ["Not Extended", ""],
  511: ["Network Authentication Required", "The client needs to authenticate to gain network access"],
};
const ERROR_TEMPLATE = (code: number, message: string, explain: string) => `<!DOCTYPE HTML>
<html lang="en">
    <head>
        <meta charset="utf-8">
        <title>Error response</title>
    </head>
    <body>
        <h1>Error response</h1>
        <p>Error code: ${code}</p>
        <p>Message: ${message}.</p>
        <p>Error code explanation: ${code} - ${explain}.</p>
    </body>
</html>
`;
const htmlEscape = (s: string) => s.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");

/** email.message.Message as http.server fills it: ordered, case-insensitive get() of the FIRST
 *  occurrence, items() in wire order with the names as sent. */
export class Headers {
  constructor(readonly list: [string, string][]) {}
  get(name: string, dflt: string | null = null): string | null {
    const lower = name.toLowerCase();
    const hit = this.list.find(([k]) => k.toLowerCase() === lower);
    return hit === undefined ? dflt : hit[1];
  }
  items(): [string, string][] {
    return this.list;
  }
}

/** Buffered reads over a socket: readline (with a byte limit) and read(n), both waiting for data. */
class SocketReader {
  private buf = Buffer.alloc(0);
  private ended = false;
  private waiter: (() => void) | null = null;
  constructor(socket: Socket) {
    socket.on("data", (d: Buffer) => {
      this.buf = Buffer.concat([this.buf, d]);
      this.wake();
    });
    const end = () => {
      this.ended = true;
      this.wake();
    };
    socket.on("end", end);
    socket.on("close", end);
    socket.on("error", end);
  }
  private wake(): void {
    const w = this.waiter;
    this.waiter = null;
    w?.();
  }
  private more(): Promise<void> {
    return new Promise((res) => {
      this.waiter = res;
    });
  }
  async readline(limit: number): Promise<Buffer> {
    for (;;) {
      const nl = this.buf.indexOf(0x0a);
      if (nl >= 0 && nl < limit) return this.take(nl + 1);
      if (this.buf.length >= limit) return this.take(limit);
      if (this.ended) return this.take(this.buf.length);
      await this.more();
    }
  }
  async read(n: number): Promise<Buffer> {
    while (this.buf.length < n && !this.ended) await this.more();
    return this.take(Math.min(n, this.buf.length));
  }
  private take(n: number): Buffer {
    const out = this.buf.subarray(0, n);
    this.buf = this.buf.subarray(n);
    return out;
  }
}

/** What a do_GET/do_POST method is handed: the request, and the response writer. */
export interface Request {
  command: string;
  path: string;
  headers: { get(name: string, dflt?: string | null): string | null; items(): [string, string][] };
  rfile: { read(n: number): Promise<Uint8Array> | Uint8Array };
  wfile: { write(data: Uint8Array | string): void; flush(): void };
  closeConnection: boolean;
  sendResponse(code: number, message?: string): void;
  sendHeader(key: string, value: string): void;
  endHeaders(): void;
  sendError(code: number, message?: string, explain?: string): void;
}
export type Methods = Record<string, (req: Request) => void | Promise<void>>;

class Connection implements Request {
  command = "";
  path = "";
  requestVersion = "HTTP/0.9";
  headers = new Headers([]);
  closeConnection = true;
  private headerBuf: string[] = [];
  private dead = false;
  readonly rfile;
  readonly wfile;

  constructor(socket: Socket, private readonly reader: SocketReader, private readonly protocolVersion: string) {
    socket.on("close", () => {
      this.dead = true;
    });
    socket.on("error", () => {
      this.dead = true;
    });
    this.rfile = { read: (n: number) => reader.read(n) };
    this.wfile = {
      write: (data: Uint8Array | string) => {
        // A socket write in Python raises on a peer that has gone; node reports it asynchronously,
        // so a write after the close/error event is the raise.
        if (this.dead || socket.destroyed) throw new BrokenPipeError("[Errno 32] Broken pipe");
        socket.write(typeof data === "string" ? Buffer.from(data, "latin1") : data);
      },
      flush: () => {},
    };
  }

  sendResponse(code: number, message?: string): void {
    this.sendResponseOnly(code, message);
    this.sendHeader("Server", `BaseHTTP/0.6 Bun/${Bun.version}`);
    this.sendHeader("Date", new Date().toUTCString());
  }
  private sendResponseOnly(code: number, message?: string): void {
    if (this.requestVersion !== "HTTP/0.9") {
      const phrase = message ?? RESPONSES[code]?.[0] ?? "";
      this.headerBuf.push(`${this.protocolVersion} ${code} ${phrase}\r\n`);
    }
  }
  sendHeader(key: string, value: string): void {
    if (this.requestVersion !== "HTTP/0.9") this.headerBuf.push(`${key}: ${value}\r\n`);
    if (key.toLowerCase() === "connection") {
      if (value.toLowerCase() === "close") this.closeConnection = true;
      else if (value.toLowerCase() === "keep-alive") this.closeConnection = false;
    }
  }
  endHeaders(): void {
    if (this.requestVersion !== "HTTP/0.9") {
      this.headerBuf.push("\r\n");
      const bytes = Buffer.from(this.headerBuf.join(""), "latin1");
      this.headerBuf = [];
      this.wfile.write(bytes);
    }
  }
  sendError(code: number, message?: string, explain?: string): void {
    const [shortmsg, longmsg] = RESPONSES[code] ?? ["???", "???"];
    const msg = message ?? shortmsg;
    const exp = explain ?? longmsg;
    this.sendResponse(code, msg);
    this.sendHeader("Connection", "close");
    let body: Buffer | null = null;
    if (code >= 200 && ![204, 205, 304].includes(code)) {
      body = Buffer.from(ERROR_TEMPLATE(code, htmlEscape(msg), htmlEscape(exp)), "utf8");
      this.sendHeader("Content-Type", "text/html;charset=utf-8");
      this.sendHeader("Content-Length", String(body.length));
    }
    this.endHeaders();
    if (this.command !== "HEAD" && body) this.wfile.write(body);
  }

  /** handle_one_request: false when the connection should close. */
  async handleOne(methods: Methods): Promise<void> {
    const raw = await this.reader.readline(65537);
    if (raw.length > 65536) {
      this.requestVersion = "";
      this.command = "";
      this.sendError(414);
      return;
    }
    if (raw.length === 0) {
      this.closeConnection = true;
      return;
    }
    if (!(await this.parseRequest(raw))) return;
    const method = methods[this.command];
    if (method === undefined) {
      this.sendError(501, `Unsupported method (${pyRepr(this.command)})`);
      return;
    }
    await method(this);
  }

  private async parseRequest(raw: Buffer): Promise<boolean> {
    this.command = "";
    this.requestVersion = "HTTP/0.9";
    let version = this.requestVersion;
    this.closeConnection = true;
    const requestline = raw.toString("latin1").replace(/[\r\n]+$/, "");
    const words = requestline.split(/[ \t\n\r\x0b\x0c]+/).filter(Boolean);
    if (words.length === 0) return false;
    if (words.length >= 3) {
      version = words[words.length - 1]!;
      const parts = version.startsWith("HTTP/") ? version.slice(5).split(".") : [];
      if (parts.length !== 2 || parts.some((p) => !/^\d+$/.test(p) || p.length > 10)) {
        this.sendError(400, `Bad request version (${pyRepr(version)})`);
        return false;
      }
      const [major, minor] = parts.map(Number) as [number, number];
      if ((major > 1 || (major === 1 && minor >= 1)) && this.protocolVersion >= "HTTP/1.1") this.closeConnection = false;
      if (major >= 2) {
        this.sendError(505, `Invalid HTTP version (${version.slice(5)})`);
        return false;
      }
      this.requestVersion = version;
    }
    if (!(words.length >= 2 && words.length <= 3)) {
      this.sendError(400, `Bad request syntax (${pyRepr(requestline)})`);
      return false;
    }
    const [command, path] = words as [string, string];
    if (words.length === 2) {
      this.closeConnection = true;
      if (command !== "GET") {
        this.sendError(400, `Bad HTTP/0.9 request type (${pyRepr(command)})`);
        return false;
      }
    }
    this.command = command;
    this.path = path.startsWith("//") ? "/" + path.replace(/^\/+/, "") : path;
    // http.client.parse_headers: lines up to a blank one, at most 100, each at most 65536 bytes.
    const lines: string[] = [];
    for (;;) {
      const line = await this.reader.readline(65537);
      if (line.length > 65536) {
        this.sendError(431, "Line too long", "header line");
        return false;
      }
      lines.push(line.toString("latin1"));
      if (lines.length > 100) {
        this.sendError(431, "Too many headers", "got more than 100 headers");
        return false;
      }
      const s = line.toString("latin1");
      if (s === "\r\n" || s === "\n" || s === "") break;
    }
    const list: [string, string][] = [];
    for (const line of lines) {
      const s = line.replace(/\r?\n$/, "");
      if (s === "") break;
      if (/^[ \t]/.test(s) && list.length > 0) {
        list[list.length - 1]![1] += s;
        continue;
      }
      const colon = s.indexOf(":");
      if (colon < 0) continue;
      list.push([s.slice(0, colon), s.slice(colon + 1).replace(/^[ \t]+/, "")]);
    }
    this.headers = new Headers(list);
    const conntype = (this.headers.get("Connection", "") as string).toLowerCase();
    if (conntype === "close") this.closeConnection = true;
    else if (conntype === "keep-alive" && this.protocolVersion >= "HTTP/1.1") this.closeConnection = false;
    return true;
  }
}

export interface PyServer {
  serverPort: number;
  daemonThreads: boolean;
  serveForever(): Promise<void>;
  shutdown(): Promise<void> | void;
  serverClose(): Promise<void> | void;
}

/** ThreadingHTTPServer((host, port), Handler): bound and listening when it returns, one concurrent
 *  task per connection, server_close() waiting for every in-flight request (block_on_close). */
export async function threadingHTTPServer(host: string, port: number, methods: Methods, protocolVersion = "HTTP/1.0"): Promise<PyServer> {
  const inflight = new Set<Promise<void>>();
  const sockets = new Set<Socket>();
  const server: Server = netCreateServer((socket) => {
    sockets.add(socket);
    socket.on("close", () => sockets.delete(socket));
    const reader = new SocketReader(socket);
    const task = (async () => {
      try {
        for (;;) {
          const conn = new Connection(socket, reader, protocolVersion);
          await conn.handleOne(methods);
          if (conn.closeConnection) break;
        }
      } catch (error) {
        // socketserver.handle_error: report, then shut the connection with nothing more written.
        process.stderr.write(`${"-".repeat(40)}\nException occurred during processing of request from ` +
          `('${socket.remoteAddress}', ${socket.remotePort})\n${(error as Error).stack ?? String(error)}\n${"-".repeat(40)}\n`);
      } finally {
        socket.end();
      }
    })();
    inflight.add(task);
    void task.finally(() => inflight.delete(task));
  });
  await new Promise<void>((res, rej) => {
    server.once("error", (e) => rej(osError(e)));
    server.listen(port, host, () => res());
  });
  let stop: () => void = () => {};
  const stopped = new Promise<void>((res) => {
    stop = res;
  });
  return {
    serverPort: (server.address() as { port: number }).port,
    daemonThreads: false,
    serveForever: () => stopped,
    shutdown: () => stop(),
    serverClose: async () => {
      await new Promise<void>((res) => server.close(() => res()));
      await Promise.allSettled([...inflight]);
    },
  };
}

// ---------------------------------------------------------------------------------------------
// http.client
// ---------------------------------------------------------------------------------------------

export interface ClientResponse {
  status: number;
  getheader(name: string, dflt?: string): string | null;
  /** read1(n): at most n bytes of what has arrived, waiting for at least one; b"" at the end. */
  read1(n: number): Promise<Uint8Array>;
  /** read(n): up to n bytes, reading until n or the end. */
  read(n: number): Promise<Uint8Array>;
}

/** http.client.HTTPConnection(host, port, timeout).request(...).getresponse(), over a raw socket:
 *  HTTP/1.1 with Host, Accept-Encoding: identity and (for a body) Content-Length, then a response
 *  parser handling Content-Length, chunked and close-delimited bodies. */
export async function httpRequest(
  host: string, port: number, method: string, path: string, body: Uint8Array | null,
  headers: [string, string][], timeoutS: number,
): Promise<{ response: ClientResponse; close(): void }> {
  const socket = netConnect({ host, port });
  const idle = () => socket.destroy(Object.assign(new Error("timed out"), { code: "PYTIMEOUT" }));
  socket.setTimeout(timeoutS * 1000, idle);
  const reader = new SocketReader(socket);
  let failure: Error | null = null;
  socket.on("error", (e) => {
    failure = (e as { code?: string }).code === "PYTIMEOUT" ? new TimeoutError("timed out") : osError(e);
  });
  await new Promise<void>((res) => {
    socket.once("connect", () => res());
    socket.once("close", () => res());
  });
  if (failure) throw failure;
  const names = new Set(headers.map(([k]) => k.toLowerCase()));
  const lines = [`${method} ${path} HTTP/1.1`];
  if (!names.has("host")) lines.push(`Host: ${host}:${port}`);
  if (!names.has("accept-encoding")) lines.push("Accept-Encoding: identity");
  if (!names.has("content-length") && (body !== null || ["POST", "PUT", "PATCH"].includes(method))) {
    lines.push(`Content-Length: ${body?.length ?? 0}`);
  }
  for (const [k, v] of headers) lines.push(`${k}: ${v}`);
  socket.write(Buffer.from(lines.join("\r\n") + "\r\n\r\n", "latin1"));
  if (body !== null) socket.write(body);
  const response = await parseResponse(reader, () => failure, method);
  return { response, close: () => socket.destroy() };
}

async function parseResponse(reader: SocketReader, failure: () => Error | null, method: string): Promise<ClientResponse> {
  const statusLine = (await reader.readline(65537)).toString("latin1");
  if (statusLine === "") throw failure() ?? new RemoteDisconnected("Remote end closed connection without response");
  const m = /^HTTP\/\d\.\d (\d{3})(?: .*)?\r?\n$/.exec(statusLine);
  if (!m) throw new BadStatusLine(pyRepr(statusLine));
  const status = Number(m[1]);
  const list: [string, string][] = [];
  for (;;) {
    const line = (await reader.readline(65537)).toString("latin1").replace(/\r?\n$/, "");
    if (line === "") break;
    const colon = line.indexOf(":");
    if (colon > 0) list.push([line.slice(0, colon), line.slice(colon + 1).trim()]);
  }
  const headers = new Headers(list);
  const chunked = (headers.get("Transfer-Encoding") ?? "").toLowerCase().includes("chunked");
  const lengthHeader = headers.get("Content-Length");
  let remaining = !chunked && lengthHeader !== null ? Number(lengthHeader) : Infinity;
  if (method === "HEAD" || status === 204 || status === 304 || (status >= 100 && status < 200)) remaining = 0;
  let chunkLeft = 0;
  let done = remaining === 0;
  const next = async (n: number): Promise<Uint8Array> => {
    if (done) return new Uint8Array(0);
    if (chunked) {
      if (chunkLeft === 0) {
        const sizeLine = (await reader.readline(65537)).toString("latin1");
        if (sizeLine === "") throw failure() ?? new IncompleteRead("IncompleteRead(0 bytes read)");
        chunkLeft = parseInt(sizeLine.split(";")[0]!.trim(), 16);
        if (chunkLeft === 0) {
          // trailers up to the blank line
          for (;;) {
            const t = (await reader.readline(65537)).toString("latin1");
            if (t === "\r\n" || t === "\n" || t === "") break;
          }
          done = true;
          return new Uint8Array(0);
        }
      }
      const got = await reader.read(Math.min(n, chunkLeft));
      if (got.length === 0) throw failure() ?? new IncompleteRead("IncompleteRead(0 bytes read)");
      chunkLeft -= got.length;
      if (chunkLeft === 0) await reader.readline(3);
      return got;
    }
    const want = Math.min(n, remaining);
    const got = await reader.read(want === Infinity ? n : want);
    if (got.length === 0) {
      done = true;
      if (remaining !== Infinity) throw failure() ?? new IncompleteRead(`IncompleteRead(0 bytes read, ${remaining} more expected)`);
      const f = failure();
      if (f && !(f instanceof OSError && /reset/i.test(f.message))) throw f;
      return got;
    }
    if (remaining !== Infinity) {
      remaining -= got.length;
      if (remaining === 0) done = true;
    }
    return got;
  };
  return {
    status,
    getheader: (name, dflt) => headers.get(name, dflt ?? null),
    read1: (n) => next(n),
    read: async (n) => {
      const parts: Uint8Array[] = [];
      let total = 0;
      while (total < n) {
        const part = await next(n - total);
        if (part.length === 0) break;
        parts.push(part);
        total += part.length;
      }
      return Buffer.concat(parts);
    },
  };
}

/** http.client.HTTPSConnection(host, timeout) for the proxy's upstream: request() then
 *  getresponse() whose read1() streams the decoded body as it arrives. */
export interface UpstreamConnection {
  request(method: string, url: string, body: Uint8Array, headers: [string, string][]): void;
  getresponse(): Promise<ClientResponse>;
  close(): void;
}

export function httpsConnection(host: string, timeoutS: number): UpstreamConnection {
  let pending: Promise<ClientResponse> | null = null;
  let destroy: () => void = () => {};
  return {
    request(method, url, body, headers) {
      pending = new Promise<ClientResponse>((resolve, reject) => {
        const names = new Set(headers.map(([k]) => k.toLowerCase()));
        const out: Record<string, string> = {};
        if (!names.has("host")) out.Host = host;
        if (!names.has("content-length")) out["Content-Length"] = String(body.length);
        for (const [k, v] of headers) out[k] = v;
        const req = httpsRequest({ host, port: 443, method, path: url, headers: out, agent: false }, (res) => {
          const queue: Buffer[] = [];
          let ended = false;
          let error: Error | null = null;
          let wake: (() => void) | null = null;
          const kick = () => {
            const w = wake;
            wake = null;
            w?.();
          };
          res.on("data", (d: Buffer) => {
            queue.push(d);
            kick();
          });
          res.on("end", () => {
            ended = true;
            kick();
          });
          res.on("error", (e) => {
            error = osError(e);
            kick();
          });
          res.on("aborted", () => {
            error = new IncompleteRead("IncompleteRead(0 bytes read)");
            kick();
          });
          const read1 = async (n: number): Promise<Uint8Array> => {
            while (queue.length === 0 && !ended && !error) await new Promise<void>((r) => (wake = r));
            if (queue.length > 0) {
              const head = queue[0]!;
              if (head.length <= n) return queue.shift() as Buffer;
              queue[0] = head.subarray(n);
              return head.subarray(0, n);
            }
            if (error) throw error;
            return new Uint8Array(0);
          };
          resolve({
            status: res.statusCode ?? 0,
            getheader: (name, dflt) => {
              const v = res.headers[name.toLowerCase()];
              if (v === undefined) return dflt ?? null;
              return Array.isArray(v) ? v.join(", ") : v;
            },
            read1,
            read: async (n) => {
              const parts: Uint8Array[] = [];
              let total = 0;
              while (total < n) {
                const p = await read1(n - total);
                if (p.length === 0) break;
                parts.push(p);
                total += p.length;
              }
              return Buffer.concat(parts);
            },
          });
        });
        req.setTimeout(timeoutS * 1000, () => req.destroy(Object.assign(new Error("timed out"), { code: "PYTIMEOUT" })));
        req.on("error", (e) => {
          const err = (e as { code?: string }).code === "PYTIMEOUT" ? new TimeoutError("timed out")
            : (e as { code?: string }).code === "ECONNRESET" && /socket hang up/.test(e.message)
              ? new RemoteDisconnected("Remote end closed connection without response") : osError(e);
          reject(err);
        });
        destroy = () => req.destroy();
        req.end(body);
      });
      // A request that fails before getresponse() is awaited must not become an unhandled rejection.
      pending.catch(() => {});
    },
    getresponse() {
      if (pending === null) throw new OSError("request() was not called");
      return pending;
    },
    close() {
      destroy();
    },
  };
}


/** Bind `n` loopback sockets on port 0 at once, read their ports, close them all: the free-port
 *  idiom of `socket.socket().bind(("127.0.0.1", 0))`, with all n held open together so the ports
 *  are distinct. */
export async function ephemeralPorts(n: number): Promise<number[]> {
  const servers = await Promise.all(Array.from({ length: n }, () => new Promise<Server>((res, rej) => {
    const s = netCreateServer();
    s.once("error", (e) => rej(osError(e)));
    s.listen(0, "127.0.0.1", () => res(s));
  })));
  const ports = servers.map((s) => (s.address() as { port: number }).port);
  await Promise.all(servers.map((s) => new Promise<void>((res) => s.close(() => res()))));
  return ports;
}
