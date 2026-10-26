import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";

export type HostFrame = Record<string, any>;
export type TraceEvent = { at: number; direction: "sent" | "received" | "harness"; frame: HostFrame };
export type RuntimeDriver = Pick<NativeReference, "pid" | "transcript" | "stderr" | "recordFault" | "crashNativeHost" | "wait" | "operation" | "send" | "hello" | "open" | "stop">;

/** The reference is the real Codex host, not a JavaScript simulation of it. */
export class NativeReference {
  private readonly child: ChildProcessWithoutNullStreams;
  private bytes = Buffer.alloc(0);
  private readonly queued: HostFrame[] = [];
  private readonly pending: Array<{
    match: (frame: HostFrame) => boolean;
    resolve: (frame: HostFrame) => void;
    reject: (error: Error) => void;
    timer: ReturnType<typeof setTimeout>;
  }> = [];
  private nextId = 0;
  private failure: Error | undefined;
  readonly events: Array<{ at: number; frame: HostFrame }> = [];
  readonly transcript: TraceEvent[] = [];
  readonly startedAt = performance.now();
  readonly stderr: string[] = [];
  get pid(): number | undefined { return this.child.pid; }
  /** Fixture-owned fault injection; candidate runners replace this to kill only their pinned native child. */
  crashNativeHost: () => Promise<void> = async () => {
    if (!this.child.pid) throw new Error("native host has no process identity");
    this.recordFault({ type: "harness/native-host-kill", pid: this.child.pid, signal: "SIGKILL" });
    this.child.kill("SIGKILL");
  };
  recordFault(frame: HostFrame): void {
    this.transcript.push({ at: performance.now() - this.startedAt, direction: "harness", frame });
  }

  constructor(binary: string, args = ["--listen", "stdio"]) {
    this.child = spawn(binary, args, { stdio: "pipe" });
    this.child.stdout.on("data", (chunk: Buffer) => this.receive(chunk));
    this.child.stdin.on("error", error => this.fail(new Error(`native host transport write failed: ${error.message}`)));
    this.child.stderr.on("data", (chunk: Buffer) => this.stderr.push(chunk.toString("utf8")));
    this.child.on("error", (error) => this.fail(error));
    this.child.on("exit", (code, signal) => {
      this.fail(new Error(`native host exited: code=${code} signal=${signal}`));
    });
  }

  private fail(error: Error): void {
    this.failure ??= error;
    for (const waiter of this.pending.splice(0)) {
      clearTimeout(waiter.timer);
      waiter.reject(error);
    }
  }

  private receive(chunk: Buffer): void {
    this.bytes = Buffer.concat([this.bytes, chunk]);
    while (this.bytes.length >= 4) {
      const length = this.bytes.readUInt32LE(0);
      if (length > 64 * 1024 * 1024) {
        this.fail(new Error("native frame exceeds the pinned protocol limit"));
        this.child.kill();
        return;
      }
      if (this.bytes.length < length + 4) return;
      let frame: HostFrame;
      try {
        frame = JSON.parse(this.bytes.subarray(4, length + 4).toString("utf8"));
      } catch (error) {
        this.fail(error instanceof Error ? error : new Error(String(error)));
        return;
      }
      this.bytes = this.bytes.subarray(length + 4);
      this.events.push({ at: performance.now(), frame });
      this.transcript.push({ at: performance.now() - this.startedAt, direction: "received", frame });
      const index = this.pending.findIndex((waiter) => waiter.match(frame));
      if (index < 0) this.queued.push(frame);
      else {
        const [waiter] = this.pending.splice(index, 1);
        clearTimeout(waiter!.timer);
        waiter!.resolve(frame);
      }
    }
  }

  send(frame: HostFrame): void {
    if (this.failure) throw this.failure;
    this.transcript.push({ at: performance.now() - this.startedAt, direction: "sent", frame });
    const payload = Buffer.from(JSON.stringify(frame));
    const prefix = Buffer.alloc(4);
    prefix.writeUInt32LE(payload.length);
    this.child.stdin.write(Buffer.concat([prefix, payload]));
  }

  wait(match: (frame: HostFrame) => boolean, timeoutMs = 5_000): Promise<HostFrame> {
    const index = this.queued.findIndex(match);
    if (index >= 0) return Promise.resolve(this.queued.splice(index, 1)[0]!);
    if (this.failure) return Promise.reject(this.failure);
    return new Promise((resolve, reject) => {
      const waiter = {
        match,
        resolve,
        reject,
        timer: setTimeout(() => {
          const at = this.pending.indexOf(waiter);
          if (at >= 0) this.pending.splice(at, 1);
          reject(new Error(`native reference did not answer within ${timeoutMs} ms`));
        }, timeoutMs),
      };
      this.pending.push(waiter);
    });
  }

  async hello(): Promise<HostFrame> {
    this.send({ type: "connection/hello", supportedVersions: [1], requiredCapabilities: [], optionalCapabilities: [] });
    const ready = await this.wait((frame) => frame.type === "connection/ready" || frame.type === "connection/rejected");
    if (ready.type !== "connection/ready" || ready.selectedVersion !== 1) {
      throw new Error(`native handshake rejected: ${JSON.stringify(ready)}`);
    }
    return ready;
  }

  operation(request: HostFrame): { id: number; response: Promise<HostFrame> } {
    const id = ++this.nextId;
    const response = this.wait((frame) => frame.type === "operation/response" && frame.id === id);
    this.send({ type: "operation/request", id, request });
    return { id, response };
  }

  async open(sessionId: string): Promise<void> {
    const { response } = this.operation({ method: "session/open", sessionId });
    const frame = await response;
    if (frame.result?.status !== "ok") throw new Error(JSON.stringify(frame));
  }

  async stop(): Promise<void> {
    this.child.stdin.end();
    if (this.child.exitCode !== null || this.child.signalCode !== null) return;
    await new Promise<void>((resolve) => {
      const timer = setTimeout(() => this.child.kill("SIGKILL"), 2_000);
      this.child.once("exit", () => {
        clearTimeout(timer);
        resolve();
      });
    });
  }
}
