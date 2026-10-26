import * as grpc from "@grpc/grpc-js";
import * as loader from "@grpc/proto-loader";
import { createServer, type Server, type Socket } from "node:net";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { sha256 } from "./qualification";
import type { HostFrame, TraceEvent } from "./native-reference";

type Binding = { sessionId: string; metadata: grpc.Metadata; lease: any; subscription: any };
type PendingDelegate = { binding: Binding; invocationId?: string; notificationId?: string };
/** Independent official gRPC reference; never sends the lossy legacy stdio input_json representation. */
export class CurrentGrpcReference {
  readonly transcript: TraceEvent[] = [];
  readonly stderr: string[] = [];
  readonly rawGrpc: Array<{ at: number; direction: string; method: string; value: any }> = [];
  readonly startedAt = performance.now();
  private child: ChildProcessWithoutNullStreams;
  private bridge: Server;
  private sockets = new Set<Socket>();
  private client: any;
  private ready: Promise<void>;
  private bindings = new Map<string, Binding>();
  private delegates = new Map<string, PendingDelegate>();
  private cancelledDelegates = new Set<string>();
  private queued: HostFrame[] = [];
  private pending: Array<{ match: (frame: HostFrame) => boolean; resolve: (frame: HostFrame) => void; reject: (error: Error) => void; timer: ReturnType<typeof setTimeout> }> = [];
  private nextId = 0;
  private nextDelegate = 0;
  private failure?: Error;
  get pid(): number | undefined { return this.child.pid; }
  crashNativeHost = async () => { this.recordFault({ type: "harness/native-host-kill", pid: this.pid, signal: "SIGKILL" }); this.child.kill("SIGKILL"); };
  recordFault(frame: HostFrame): void { this.transcript.push({ at: performance.now() - this.startedAt, direction: "harness", frame }); }
  constructor(binary: string) {
    this.child = spawn(binary, ["--listen", "grpc+stdio://"], { stdio: "pipe" });
    this.child.stderr.on("data", bytes => this.stderr.push(bytes.toString()));
    this.child.stdin.on("error", error => this.fail(error)); this.child.on("error", error => this.fail(error));
    this.child.on("exit", (code, signal) => this.fail(new Error(`current native gRPC host exited: code=${code} signal=${signal}`)));
    this.bridge = createServer(socket => {
      this.sockets.add(socket); socket.pipe(this.child.stdin); this.child.stdout.pipe(socket);
      socket.on("error", () => {}); socket.on("close", () => this.sockets.delete(socket));
    });
    this.ready = this.start();
  }
  private async start(): Promise<void> {
    const schema = resolve(import.meta.dir, "../../integrations/codemode/src/main/resources/splice/codemode/native/grpc/codex.code_mode.v1.proto");
    if (sha256(await readFile(schema)) !== "6f2807bacb88e3ac501a17b38e361610e5f96539b9d656c4726261b55d1a4136") throw new Error("current gRPC reference schema does not match pinned source");
    const definition = loader.loadSync(schema, { longs: String, enums: Number, defaults: false, oneofs: true });
    const packageDefinition: any = grpc.loadPackageDefinition(definition);
    await new Promise<void>((accept, reject) => { this.bridge.once("error", reject); this.bridge.listen(0, "127.0.0.1", accept); });
    const address = this.bridge.address(); if (!address || typeof address === "string") throw new Error("gRPC bridge has no TCP address");
    this.client = new packageDefinition.codex.code_mode.v1.CodeModeHost(`127.0.0.1:${address.port}`, grpc.credentials.createInsecure());
    await new Promise<void>((accept, reject) => this.client.waitForReady(Date.now() + 5000, (error: Error | null) => error ? reject(error) : accept()));
  }
  private raw(direction: string, method: string, value: any): void {
    this.rawGrpc.push({ at: performance.now() - this.startedAt, direction, method, value });
  }
  private emit(frame: HostFrame): void {
    this.transcript.push({ at: performance.now() - this.startedAt, direction: "received", frame });
    const index = this.pending.findIndex(waiter => waiter.match(frame));
    if (index < 0) this.queued.push(frame);
    else { const waiter = this.pending.splice(index, 1)[0]!; clearTimeout(waiter.timer); waiter.resolve(frame); }
  }
  private fail(error: Error): void {
    this.failure ??= error;
    for (const waiter of this.pending.splice(0)) { clearTimeout(waiter.timer); waiter.reject(this.failure); }
  }
  wait(match: (frame: HostFrame) => boolean, timeoutMs = 5000): Promise<HostFrame> {
    const index = this.queued.findIndex(match); if (index >= 0) return Promise.resolve(this.queued.splice(index, 1)[0]!);
    if (this.failure) return Promise.reject(this.failure);
    return new Promise((accept, reject) => {
      const waiter = { match, resolve: accept, reject, timer: setTimeout(() => { const index = this.pending.indexOf(waiter); if (index >= 0) this.pending.splice(index, 1); reject(new Error("current gRPC reference observation timed out")); }, timeoutMs) };
      this.pending.push(waiter);
    });
  }
  async hello(): Promise<HostFrame> { await this.ready; return { type: "connection/ready", selectedVersion: 1, capabilities: [] }; }
  private async unary(method: string, request: any, binding: Binding): Promise<any> {
    this.raw("sent", method, request);
    return new Promise((accept, reject) => {
      let bufferedForNs: string | undefined;
      const call = this.client[method](request, binding.metadata, (error: any, response: any) => {
        this.raw("received", method, error ? { code: error.code, details: error.details } : response);
        if (error) reject(error); else accept({ ...response, bufferedForNs });
      });
      call.on("metadata", (headers: grpc.Metadata) => {
        this.raw("received", `${method}.metadata`, headers.getMap());
        const duration = headers.get("codex-code-mode-result-buffered-for-ns")[0];
        if (duration !== undefined) bufferedForNs = String(duration);
      });
    });
  }
  async open(name: string): Promise<void> {
    await this.ready; const metadata = new grpc.Metadata();
    const request = {}; this.raw("sent", "OpenSession", request); const lease = this.client.openSession(request);
    const binding: Binding = { sessionId: "", metadata, lease, subscription: undefined };
    lease.on("metadata", (headers: grpc.Metadata) => { const route = headers.get("x-code-mode-route")[0]; if (route) metadata.set("x-code-mode-route", route); this.raw("received", "OpenSession.metadata", headers.getMap()); });
    const opened = new Promise<void>((accept, reject) => {
      lease.on("error", (error: any) => { if (error.code !== grpc.status.CANCELLED) reject(error); });
      lease.on("data", (event: any) => {
        this.raw("received", "OpenSession", event);
        if (event.opened) { binding.sessionId = event.opened.sessionId; this.bindings.set(name, binding); accept(); }
        else if (event.notification) {
          const id = String(++this.nextDelegate); this.delegates.set(id, { binding, notificationId: event.notification.notificationId });
          this.emit({ type: "delegate/request", id, sessionId: name, request: { type: "notification/send", call_id: event.notification.callId, cell_id: event.notification.cellId, text: event.notification.text } });
        } else if (event.toolCallCancelled || event.notificationCancelled) {
          const actualId = event.toolCallCancelled?.invocationId ?? event.notificationCancelled?.notificationId;
          const delegate = [...this.delegates].find(([, item]) => item.invocationId === actualId || item.notificationId === actualId);
          if (delegate) { this.delegates.delete(delegate[0]); this.emit({ type: "delegate/cancel", id: delegate[0] }); }
          else this.cancelledDelegates.add(actualId);
        }
      });
    });
    await opened;
    binding.subscription = this.client.subscribeToToolCalls({ sessionId: binding.sessionId }, metadata);
    binding.subscription.on("error", (error: any) => { if (error.code !== grpc.status.CANCELLED) this.fail(error); });
    binding.subscription.on("data", (call: any) => {
      this.raw("received", "SubscribeToToolCalls", call);
      if (this.cancelledDelegates.delete(call.invocationId)) return;
      const present = Object.hasOwn(call, "inputJson"), input = present ? JSON.parse(Buffer.from(call.inputJson).toString()) : undefined;
      // This is Codex's function-argument bridge, independently applied to the exact optional gRPC bytes.
      if (call.toolKind === 1 && present && (input === null || typeof input !== "object" || Array.isArray(input))) {
        const namespace = call.toolName.namespace;
        const display = !namespace || namespace === "functions" ? call.toolName.name : namespace + call.toolName.name;
        void this.unary("completeToolCall", { sessionId: binding.sessionId, invocationId: call.invocationId,
          failed: { message: `tool \`${display}\` expects a JSON object for arguments` } }, binding).catch(error => this.fail(error)); return;
      }
      const id = String(++this.nextDelegate); this.delegates.set(id, { binding, invocationId: call.invocationId });
      this.emit({ type: "delegate/request", id, sessionId: name, request: { type: "tool/invoke", invocation: {
        cell_id: call.cellId, runtime_tool_call_id: call.runtimeToolCallId, tool_name: { name: call.toolName.name, namespace: call.toolName.namespace ?? null },
        tool_kind: call.toolKind === 2 ? "freeform" : "function", input_present: present, input: !present && call.toolKind === 1 ? {} : input,
      } } });
    });
    await new Promise<void>((accept, reject) => { binding.subscription.once("metadata", accept); binding.subscription.once("error", reject); });
  }
  private outcome(value: any, bufferedForNs = "0"): any {
    const payload = { cell_id: value.cellId, content_items: (value.contentItems ?? []).map((item: any) => item.text ? { type: "input_text", text: item.text.text ?? "" } : item.image ? { type: "input_image", image_url: item.image.imageUrl, ...(item.image.detail ? { detail: ["", "auto", "low", "high", "original"][item.image.detail] } : {}) } : { type: "input_audio", audio_url: item.audio.audioUrl }), code_mode_host_duration_ns: value.codeModeHostDurationNs ?? "0" };
    return value.completed ? { Result: { ...payload, error_text: value.completed.errorText ?? null, result_buffered_for_ns: bufferedForNs } } : value.terminated ? { Terminated: payload } : { Yielded: payload };
  }
  operation(request: HostFrame): { id: number; response: Promise<HostFrame> } {
    const id = ++this.nextId; const response = this.wait(frame => frame.type === "operation/response" && frame.id === id);
    this.transcript.push({ at: performance.now() - this.startedAt, direction: "sent", frame: { type: "operation/request", id, request } });
    void this.perform(id, request).catch(error => this.emit({ type: "operation/response", id, result: { status: "error", message: error.details ?? error.message } }));
    return { id, response };
  }
  private async perform(id: number, request: HostFrame): Promise<void> {
    if (request.method === "session/open") { await this.open(request.sessionId); this.emit({ type: "operation/response", id, result: { status: "ok", value: { type: "session/ready", sessionId: request.sessionId } } }); return; }
    const binding = this.bindings.get(request.sessionId); if (!binding) throw new Error(`unknown code-mode session: ${request.sessionId}`);
    const ok = (value: any) => this.emit({ type: "operation/response", id, result: { status: "ok", value } });
    if (request.method === "session/shutdown") {
      await this.unary("closeSession", { sessionId: binding.sessionId }, binding); binding.subscription.cancel(); binding.lease.cancel(); this.bindings.delete(request.sessionId); ok({ type: "session/closed", sessionId: request.sessionId }); return;
    }
    if (request.method === "session/execute") {
      const input = request.request, executionId = randomUUID();
      const args = { sessionId: binding.sessionId, executionId, toolCallId: input.tool_call_id, source: input.source,
        enabledTools: input.enabled_tools.map((tool: any) => ({ name: tool.name, toolName: { name: tool.tool_name.name, ...(tool.tool_name.namespace ? { namespace: tool.tool_name.namespace } : {}) },
          description: tool.description, kind: tool.kind === "freeform" ? 2 : 1, ...(tool.input_schema != null ? { inputSchemaJson: Buffer.from(JSON.stringify(tool.input_schema)) } : {}), ...(tool.output_schema != null ? { outputSchemaJson: Buffer.from(JSON.stringify(tool.output_schema)) } : {}) })),
        ...(input.yield_time_ms != null ? { yieldTimeMs: String(input.yield_time_ms) } : {}), ...(input.max_output_tokens != null ? { maxOutputTokens: String(input.max_output_tokens) } : {}) };
      this.raw("sent", "Execute", args); const stream = this.client.execute(args, binding.metadata);
      stream.on("data", (event: any) => {
        this.raw("received", "Execute", event);
        if (event.started) ok({ type: "execution/started", cellId: event.started.cellId });
        if (event.outcome) this.emit({ type: "execute/initialResponse", id, result: { status: "ok", value: this.outcome(event.outcome) } });
      });
      stream.on("error", (error: any) => { this.raw("received", "Execute.error", { code: error.code, details: error.details }); this.emit({ type: "operation/response", id, result: { status: "error", message: error.details } }); }); return;
    }
    const method = request.method === "session/wait" ? "wait" : "terminate";
    const output = await this.unary(method, { sessionId: binding.sessionId, cellId: request.request?.cell_id ?? request.cellId,
      ...(method === "wait" ? { waitId: randomUUID(), yieldTimeMs: String(request.request.yield_time_ms) } : {}) }, binding);
    ok({ type: "wait/completed", outcome: output.liveCell ? { LiveCell: this.outcome(output.liveCell, output.bufferedForNs) } : { MissingCell: this.outcome(output.missingCell, output.bufferedForNs) } });
  }
  send(frame: HostFrame): void {
    this.transcript.push({ at: performance.now() - this.startedAt, direction: "sent", frame });
    const held = this.delegates.get(String(frame.id)); if (!held) throw new Error("current gRPC reply has no owned delegate"); this.delegates.delete(String(frame.id));
    const request = held.notificationId ? { sessionId: held.binding.sessionId, notificationId: held.notificationId } : {
      sessionId: held.binding.sessionId, invocationId: held.invocationId,
      ...(frame.result.status === "ok" ? { succeeded: { outputJson: Buffer.from(JSON.stringify(frame.result.value.result)) } } : { failed: { message: frame.result.message } }),
    };
    void this.unary(held.notificationId ? "acknowledgeNotification" : "completeToolCall", request, held.binding).catch(error => this.fail(error));
  }
  async stop(): Promise<void> {
    for (const binding of this.bindings.values()) { binding.subscription?.cancel(); binding.lease.cancel(); }
    this.client?.close(); for (const socket of this.sockets) socket.destroy(); this.bridge.close(); this.child.stdin.end(); this.child.kill("SIGKILL");
  }
}
