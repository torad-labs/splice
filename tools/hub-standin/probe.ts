import { Agent, request } from "node:http";
import { performance } from "node:perf_hooks";

export interface CounterReading {
  count: number;
  pid: number;
  instance: string;
}

export interface Sample {
  at: number;
  latencyMs: number;
  ok: boolean;
  status?: number;
  error?: string;
  reading?: CounterReading;
}

interface HttpReply {
  status: number;
  session?: string;
  body: string;
}

const MAX_REPLY_BYTES = 64 * 1024;

// Only counter results and transport error codes leave this module. Authorization,
// URLs and arbitrary response bodies never enter the receipt or event log.
export class Probe {
  private readonly agent = new Agent({ keepAlive: true, maxSockets: 1 });
  private session: string | undefined;
  private nextId = 1;
  private active = true;
  private last: CounterReading | undefined;
  private streamExpected = false;
  private streamRequest: ReturnType<typeof request> | undefined;
  private readonly streamAgent = new Agent({ keepAlive: true, maxSockets: 1 });
  streamFaults = 0;
  streamEvents = 0;

  get sessionId(): string | undefined { return this.session; }
  readonly samples: Sample[] = [];

  constructor(
    private readonly endpoint: URL,
    private readonly bearer: () => Promise<string>,
    private readonly headers: Record<string, string>,
    private readonly onSample: (sample: Sample) => void,
  ) {
    if (endpoint.hostname !== "127.0.0.1" || endpoint.protocol !== "http:" || endpoint.hash || endpoint.username || endpoint.password) {
      throw new Error("The stand-in probe requires a plain loopback HTTP address");
    }
  }

  async post(body: unknown, token?: string): Promise<HttpReply> {
    if (!this.active) throw Object.assign(new Error("stand-in probe stopped"), { code: "PROBE_STOPPED" });
    const authorization = token ?? await this.bearer();
    const payload = JSON.stringify(body);
    return await new Promise<HttpReply>((resolve, reject) => {
      let bytes = 0;
      const chunks: Buffer[] = [];
      const req = request(this.endpoint, {
        method: "POST",
        agent: this.agent,
        headers: {
          ...this.headers,
          Authorization: `Bearer ${authorization}`,
          "Content-Type": "application/json",
          Accept: "application/json, text/event-stream",
          "Content-Length": Buffer.byteLength(payload),
          ...(this.session ? { "Mcp-Session-Id": this.session } : {}),
        },
      }, (response) => {
        response.on("data", (chunk: Buffer) => {
          bytes += chunk.length;
          if (bytes > MAX_REPLY_BYTES) {
            req.destroy(Object.assign(new Error("stand-in reply exceeds its bound"), { code: "REPLY_TOO_LARGE" }));
          } else {
            chunks.push(chunk);
          }
        });
        response.once("error", reject);
        response.once("aborted", () => reject(Object.assign(new Error("aborted response"), { code: "ECONNRESET" })));
        response.once("end", () => {
          const session = response.headers["mcp-session-id"];
          resolve({ status: response.statusCode ?? 0, session: typeof session === "string" ? session : undefined, body: Buffer.concat(chunks).toString("utf8") });
        });
      });
      const timer = setTimeout(() => req.destroy(Object.assign(new Error("stand-in request deadline"), { code: "ETIMEDOUT" })), 2500);
      req.once("close", () => clearTimeout(timer));
      req.once("error", reject);
      req.end(payload);
    });
  }

  private result(reply: HttpReply, id: number): Record<string, unknown> {
    if (reply.status < 200 || reply.status >= 300) throw Object.assign(new Error("HTTP refusal"), { code: `HTTP_${reply.status}`, status: reply.status });
    const frames = reply.body.trim().startsWith("{") ? [reply.body] : reply.body.split(/\r?\n\r?\n/)
      .map((frame) => frame.split(/\r?\n/).filter((line) => line.startsWith("data:")).map((line) => line.slice(5).trimStart()).join("\n"))
      .filter(Boolean);
    const envelope = frames.map((frame) => JSON.parse(frame) as Record<string, unknown>).find((frame) => frame.id === id);
    if (!envelope || envelope.error || !envelope.result || typeof envelope.result !== "object") {
      throw Object.assign(new Error("stand-in RPC refused"), { code: "RPC_REFUSED" });
    }
    return envelope.result as Record<string, unknown>;
  }

  async initialize(): Promise<void> {
    this.session = undefined;
    const id = this.nextId++;
    const reply = await this.post({ jsonrpc: "2.0", id, method: "initialize", params: {
      protocolVersion: "2025-03-26", capabilities: {}, clientInfo: { name: "splice-native-standin", version: "1" },
    } });
    this.result(reply, id);
    this.session = reply.session;
    const notified = await this.post({ jsonrpc: "2.0", method: "notifications/initialized" });
    if (notified.status < 200 || notified.status >= 300) throw Object.assign(new Error("initialize notification refused"), { code: `HTTP_${notified.status}` });
    const listingId = this.nextId++;
    const listing = this.result(await this.post({ jsonrpc: "2.0", id: listingId, method: "tools/list" }), listingId);
    if (!Array.isArray(listing.tools) || !listing.tools.some((tool) => tool && typeof tool === "object" && tool.name === "count")) {
      throw Object.assign(new Error("stand-in tool absent"), { code: "TOOL_ABSENT" });
    }
  }

  async once(): Promise<Sample> {
    const started = performance.now();
    let sample: Sample;
    try {
      const id = this.nextId++;
      const result = this.result(await this.post({ jsonrpc: "2.0", id, method: "tools/call", params: { name: "count", arguments: {} } }), id);
      const content = result.content as Array<{ type?: string; text?: string }> | undefined;
      const text = content?.find((item) => item.type === "text")?.text;
      if (typeof text !== "string" || result.isError) throw Object.assign(new Error("invalid counter result"), { code: "COUNTER_INVALID" });
      const reading = JSON.parse(text) as CounterReading;
      if (!Number.isSafeInteger(reading.count) || reading.count < 1 || !Number.isSafeInteger(reading.pid) || reading.pid < 1 || typeof reading.instance !== "string") {
        throw Object.assign(new Error("invalid counter fields"), { code: "COUNTER_INVALID" });
      }
      if (this.last && (reading.pid !== this.last.pid || reading.instance !== this.last.instance || reading.count <= this.last.count)) {
        throw Object.assign(new Error("server state changed"), { code: "STATE_CHANGED" });
      }
      this.last = reading;
      sample = { at: performance.now(), latencyMs: performance.now() - started, ok: true, reading };
    } catch (error) {
      const failure = error as { code?: unknown; status?: unknown };
      sample = {
        at: performance.now(), latencyMs: performance.now() - started, ok: false,
        error: typeof failure.code === "string" && /^[A-Z0-9_]+$/.test(failure.code) ? failure.code : "PROBE_FAILED",
        ...(typeof failure.status === "number" ? { status: failure.status } : {}),
      };
    }
    this.samples.push(sample);
    if (this.samples.length > 20_000) throw new Error("The stand-in probe exceeded its sample bound");
    this.onSample(sample);
    return sample;
  }

  async openStream(): Promise<void> {
    if (!this.session) throw new Error("The stand-in needs an initialized session before opening its stream");
    const token = await this.bearer();
    this.streamExpected = true;
    await new Promise<void>((resolveStream, reject) => {
      const req = request(this.endpoint, { method: "GET", agent: this.streamAgent, headers: {
        ...this.headers, Authorization: `Bearer ${token}`, "Mcp-Session-Id": this.session!, Accept: "text/event-stream",
      } }, (response) => {
        clearTimeout(timer);
        if (response.statusCode !== 200) {
          response.resume();
          reject(new Error("The stand-in stream was refused"));
          return;
        }
        response.on("data", () => { this.streamEvents++; });
        response.once("end", () => { if (this.streamExpected) this.streamFaults++; });
        response.once("error", () => { if (this.streamExpected) this.streamFaults++; });
        response.once("aborted", () => { if (this.streamExpected) this.streamFaults++; });
        resolveStream();
      });
      this.streamRequest = req;
      const timer = setTimeout(() => req.destroy(new Error("stand-in stream handshake deadline")), 2500);
      req.once("error", () => {
        clearTimeout(timer);
        if (this.streamExpected) this.streamFaults++;
        reject(new Error("The stand-in stream transport failed"));
      });
      req.end();
    });
  }

  async run(): Promise<void> {
    while (this.active) {
      const sample = await this.once();
      if (!sample.ok && this.active) {
        // Re-initialize just the same endpoint. Recovery does not reset the expected
        // counter process or waive a failure already recorded in the receipt.
        try { await this.initialize(); } catch { /* the next call records continued failure */ }
      }
      if (this.active) await new Promise((resolve) => setTimeout(resolve, 20));
    }
  }

  stop(): void {
    this.active = false;
    this.streamExpected = false;
    this.streamRequest?.destroy();
    this.streamAgent.destroy();
    this.agent.destroy();
  }
}
