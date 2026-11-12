import { createHash, randomUUID } from "node:crypto";
import { spawn } from "node:child_process";
import { readFile, stat, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { isDeepStrictEqual } from "node:util";
import { Probe } from "./probe";

type Row = Record<string, unknown>;
export type ReaderMode = "native-json" | "jvm-core";
/** Only fixed messages authored by this fixture are safe to copy into qualification receipts. */
export class CaptureFailure extends Error {}
interface Identity { readonly callbackId: string; readonly rpcId: string; readonly tool: string; readonly arguments: Row; }
export interface DelayedCapture { readonly identity: Identity; readonly request: Promise<boolean>; }
export interface CaptureProof { readonly phase: string; readonly callbackId: string; readonly outcome: string; readonly packetSha256: string; readonly nativeCarrier: "PASS"; readonly coreReader: "PASS" | "PENDING"; }
export interface CaptureEnvironment {
  readonly state: string;
  readonly peerLog: string;
  readonly java: string;
  readonly jar: string;
  readonly readerSource: string;
  readonly readerMode: ReaderMode;
  readonly receiptDir: string;
}

function row(value: unknown): Row {
  if (value === null || typeof value !== "object" || Array.isArray(value)) throw new Error("Capture proof requires a complete JSON object");
  return value as Row;
}

function invariant(ok: unknown, message: string): asserts ok {
  if (!ok) throw new CaptureFailure(message);
}

function hash(value: string): string { return createHash("sha256").update(value).digest("hex"); }

async function until(check: () => Promise<boolean>, message: string, milliseconds = 15_000): Promise<void> {
  const deadline = performance.now() + milliseconds;
  while (performance.now() < deadline) {
    if (await check()) return;
    await Bun.sleep(50);
  }
  throw new CaptureFailure(message);
}

/** Qualifies actual relayed packets and the on-disk carrier, keeping the full reader obligation explicit. */
export class CaptureProbe {
  private readonly probe: Probe;
  private readonly identities: Identity[] = [];
  readonly proofs: CaptureProof[] = [];

  constructor(endpoint: URL, bearer: () => Promise<string>, headers: Record<string, string>, private readonly environment: CaptureEnvironment) {
    this.probe = new Probe(endpoint, bearer, headers, () => {});
  }

  async initialize(): Promise<void> { await this.probe.initialize(); }
  stop(): void { this.probe.stop(); }

  async retainDebugEvidence(): Promise<void> {
    for (const [source, name] of [
      [this.environment.peerLog, "capture-peer.jsonl"],
      [join(this.environment.state, "mcp-hub", "front.json"), "native-front-state.json"],
    ]) {
      try { await writeFile(join(this.environment.receiptDir, name!), await readFile(source!), { mode: 0o600 }); }
      catch (error) { if (!(error && typeof error === "object" && "code" in error && error.code === "ENOENT")) throw error; }
    }
    const front = row(JSON.parse(await readFile(join(this.environment.state, "mcp-hub", "front.json"), "utf8")));
    const owners: Row[] = [];
    for (const socket of Object.keys(row(front.holders))) {
      invariant(socket.startsWith(join(this.environment.state, "mcp-hub") + "/") && /\/[a-f0-9]{16}\.sock$/.test(socket), "Debug evidence refused an external holder path");
      const record = row(JSON.parse(await readFile(socket.replace(/\.sock$/, ".json"), "utf8")));
      const pid = Number(record.pid);
      if (!Number.isSafeInteger(pid) || pid < 2) continue;
      const procStat = await readFile(`/proc/${pid}/stat`, "utf8"), cgroup = await readFile(`/proc/${pid}/cgroup`, "utf8");
      const executable = await stat(`/proc/${pid}/exe`);
      owners.push({ record, procStat, cgroup, executableIdentity: `${executable.dev}:${executable.ino}` });
    }
    await writeFile(join(this.environment.receiptDir, "native-holder-owners.json"), JSON.stringify(owners, null, 2) + "\n", { mode: 0o600 });
  }

  async prove(phase: string): Promise<void> {
    for (const tool of ["capture", "rpc_error"]) {
      const identity = this.identity(tool);
      const reply = await this.probe.post(this.packet(identity));
      invariant(reply.status === 200, "Native capture call was not served");
      const actual = this.envelope(reply.body, identity.rpcId);
      const observed = await this.observed(identity);
      invariant(isDeepStrictEqual(actual, { ...observed, id: identity.rpcId }), "HTTP result differs from the actual peer packet after RPC id remapping");
      await this.verify(identity, observed, phase);
    }
  }

  async beforeRecovery(): Promise<DelayedCapture> {
    const identity = this.identity("capture", { delayMs: 6_000 });
    const request = this.probe.post(this.packet(identity), undefined, 12_000).then(() => true, () => false);
    await until(async () => (await this.events()).some((event) => event.phase === "started" && this.matches(event, identity)), "The native peer did not start its recovery call");
    const front = row(JSON.parse(await readFile(join(this.environment.state, "mcp-hub", "front.json"), "utf8")));
    const calls = row(row(front.relay).calls);
    invariant(Object.values(calls).some((call) => {
      const saved = row(call).tool_result;
      return saved !== null && typeof saved === "object" && !Array.isArray(saved) && row(saved).callbackId === identity.callbackId &&
        row(saved).server === "counter" && row(saved).tool === identity.tool && isDeepStrictEqual(row(saved).arguments, identity.arguments);
    }), "The front did not durably retain the actual callback identity");
    return { identity, request };
  }

  async afterRecovery(delayed: DelayedCapture): Promise<void> {
    const packet = await this.observed(delayed.identity);
    await until(async () => {
      try { await readFile(this.path(delayed.identity), "utf8"); return true; } catch { return false; }
    }, "The recovered front did not capture the peer's actual delayed reply");
    invariant(!(await delayed.request), "The old HTTP connection unexpectedly survived an abrupt front kill");
    await this.verify(delayed.identity, packet, "outstanding callback after cold-front recovery");
    // This is the same initialized MCP session, without a fresh initialize or a synthetic receipt write.
    await this.prove("new callbacks after cold-front recovery");
    for (const identity of this.identities) await this.verify(identity, await this.observed(identity), "retained carrier after recovery");
  }

  private identity(tool: string, extra: Row = {}): Identity {
    const identity = { callbackId: `toolu_splice_${randomUUID()}`, rpcId: `capture-${randomUUID()}`, tool,
      arguments: { marker: randomUUID(), value: 0, flag: false, empty: [], ...extra } };
    this.identities.push(identity);
    return identity;
  }

  private packet(identity: Identity): Row {
    return { jsonrpc: "2.0", id: identity.rpcId, method: "tools/call", params: {
      name: identity.tool, arguments: identity.arguments, _meta: { "claudecode/toolUseId": identity.callbackId },
    } };
  }

  private envelope(body: string, id: string): Row {
    const frames = body.trim().startsWith("{") ? [body] : body.split(/\r?\n\r?\n/)
      .map((frame) => frame.split(/\r?\n/).filter((line) => line.startsWith("data:")).map((line) => line.slice(5).trimStart()).join("\n")).filter(Boolean);
    const answer = frames.map((frame) => row(JSON.parse(frame))).find((value) => value.id === id);
    invariant(answer, "Native capture reply omitted its exact RPC request id");
    return answer;
  }

  private async events(): Promise<Row[]> {
    try {
      const text = await readFile(this.environment.peerLog, "utf8"), lines = text.split("\n");
      if (!text.endsWith("\n")) lines.pop();
      return lines.filter(Boolean).map((line) => row(JSON.parse(line)));
    }
    catch (error) { if (error && typeof error === "object" && "code" in error && error.code === "ENOENT") return []; throw error; }
  }

  private matches(event: Row, identity: Identity): boolean {
    const request = row(event.request), params = row(request.params), meta = row(params._meta);
    return request.id !== undefined && meta["claudecode/toolUseId"] === identity.callbackId && params.name === identity.tool && isDeepStrictEqual(params.arguments, identity.arguments);
  }

  private async observed(identity: Identity): Promise<Row> {
    let answer: Row | undefined;
    await until(async () => {
      const matches = (await this.events()).filter((event) => event.phase === "answered" && this.matches(event, identity));
      invariant(matches.length <= 1, "The fixture peer executed an owned callback more than once");
      if (matches[0]) answer = row(matches[0].answer);
      return answer !== undefined;
    }, "The actual MCP peer did not publish its complete response");
    return answer!;
  }

  private path(identity: Identity): string { return join(this.environment.state, "mcp-tool-results", `${hash(identity.callbackId)}.json`); }

  private async verify(identity: Identity, packet: Row, phase: string): Promise<void> {
    const path = this.path(identity);
    const record = row(JSON.parse(await readFile(path, "utf8")));
    const metadata = await stat(path);
    invariant((metadata.mode & 0o777) === 0o600, "The native carrier was not created with mode 0600");
    invariant(record.schema === 1 && record.callbackId === identity.callbackId, "Native receipt schema or callback differs");
    invariant(record.server === "counter" && record.tool === identity.tool, "Native receipt changed actual server or tool");
    invariant(isDeepStrictEqual(record.arguments, identity.arguments), "Native receipt changed actual arguments");
    invariant(typeof record.capturedAtMs === "number" && record.capturedAtMs > 0, "Native receipt has no capture time");
    const expected = packet.result !== undefined ? { type: "RawResult", value: packet.result } : { type: "RpcError", error: packet.error };
    invariant(isDeepStrictEqual(record.outcome, expected), "Native carrier differs from the peer's whole typed outcome");
    let coreReader: "PASS" | "PENDING" = "PENDING";
    let coreRecord: Row | undefined;
    if (this.environment.readerMode === "jvm-core") {
      coreRecord = await this.readCore(identity);
      invariant(isDeepStrictEqual(coreRecord, record), "The real JVM core reader changed the native receipt");
      const mismatch = await this.readCore({ ...identity, arguments: { wrong: true } });
      invariant(mismatch.status === "refused", "The real JVM reader accepted mismatched arguments");
      coreReader = "PASS";
    }
    await writeFile(join(this.environment.receiptDir, `capture-${hash(identity.callbackId)}.json`),
      JSON.stringify({ peerPacket: packet, nativeRecord: record, ...(coreRecord ? { coreRecord } : {}) }, null, 2) + "\n", { mode: 0o600 });
    await writeFile(join(this.environment.receiptDir, "capture-peer.jsonl"), await readFile(this.environment.peerLog), { mode: 0o600 });
    this.proofs.push({ phase, callbackId: identity.callbackId, outcome: String(expected.type), packetSha256: hash(JSON.stringify(packet)), nativeCarrier: "PASS", coreReader });
    await writeFile(join(this.environment.receiptDir, "capture-proof.json"), JSON.stringify({ readerMode: this.environment.readerMode,
      sourceQualification: coreReader === "PASS" ? "READER_CHECKED" : "UNQUALIFIED", expectedCoreReaderChecks: this.proofs.length, proofs: this.proofs }, null, 2) + "\n", { mode: 0o600 });
  }

  private async readCore(identity: Identity): Promise<Row> {
    return await new Promise((resolve, reject) => {
      const child = spawn(this.environment.java, ["-Xmx256m", "--class-path", this.environment.jar, this.environment.readerSource,
        this.environment.state, identity.callbackId, JSON.stringify(identity.arguments)], { stdio: ["ignore", "pipe", "pipe"] });
      let output = "";
      child.stdout.on("data", (chunk: Buffer) => { output += chunk.toString(); if (output.length > 64 * 1024) child.kill("SIGKILL"); });
      child.stderr.resume();
      const timer = setTimeout(() => child.kill("SIGKILL"), 15_000);
      child.once("error", () => { clearTimeout(timer); reject(new Error("The core reader fixture could not start")); });
      child.once("exit", (code) => { clearTimeout(timer); if (code !== 0) reject(new Error("The real candidate core reader fixture failed"));
        else { try { resolve(row(JSON.parse(output))); } catch { reject(new Error("The real core reader returned no complete JSON receipt")); } } });
    });
  }
}
