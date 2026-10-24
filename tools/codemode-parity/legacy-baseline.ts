import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { explicitYieldSource } from "./programs";

export async function observeLegacyBaseline(java: string, jar: string): Promise<{
  response: any; transcript: Array<{ at: number; direction: string; frame: any }>; stderr: string[];
}> {
  const started = performance.now();
  const child = spawn(java, ["-Xmx512m", "-cp", jar, "splice.codemode.CodeModeWorker", "host"], { stdio: "pipe" });
  let bytes = Buffer.alloc(0), failure: Error | undefined;
  const frames: any[] = [], transcript: Array<{ at: number; direction: string; frame: any }> = [], stderr: string[] = [];
  let pending: { resolve: (frame: any) => void; reject: (error: Error) => void; timer: ReturnType<typeof setTimeout> } | undefined;
  const fail = (error: Error) => { failure = error; if (pending) { clearTimeout(pending.timer); pending.reject(error); pending = undefined; } };
  child.stderr.on("data", (chunk: Buffer) => stderr.push(chunk.toString("utf8")));
  child.on("error", fail);
  child.on("exit", (code, signal) => fail(new Error(`legacy worker exited: code=${code} signal=${signal}`)));
  child.stdout.on("data", (chunk: Buffer) => {
    bytes = Buffer.concat([bytes, chunk]);
    while (bytes.length >= 4) {
      const length = bytes.readUInt32BE(0);
      if (length > 64 * 1024 * 1024) { fail(new Error("invalid legacy frame size")); child.kill(); return; }
      if (bytes.length < length + 4) return;
      let frame: any;
      try { frame = JSON.parse(bytes.subarray(4, length + 4).toString("utf8")); }
      catch { fail(new Error("malformed legacy worker response")); child.kill(); return; }
      bytes = bytes.subarray(length + 4);
      transcript.push({ at: performance.now() - started, direction: "received", frame });
      if (pending) { const waiter = pending; pending = undefined; clearTimeout(waiter.timer); waiter.resolve(frame); }
      else frames.push(frame);
    }
  });
  const next = async (): Promise<any> => {
    if (frames.length) return frames.shift();
    if (failure) throw failure;
    return new Promise((resolve, reject) => {
      pending = { resolve, reject, timer: setTimeout(() => { pending = undefined; reject(new Error("legacy worker did not answer within 15000 ms")); }, 15000) };
    });
  };
  try {
    assert.equal((await next()).type, "ready");
    const frame = { session: 1, cell: 1, request: 1,
      payload: { type: "start", source: explicitYieldSource, tools: [], descriptions: {} } };
    const payload = Buffer.from(JSON.stringify(frame)), header = Buffer.alloc(4);
    header.writeUInt32BE(payload.length);
    transcript.push({ at: performance.now() - started, direction: "sent", frame });
    child.stdin.write(Buffer.concat([header, payload]));
    return { response: await next(), transcript, stderr };
  } finally {
    child.stdin.end();
    if (child.exitCode === null && child.signalCode === null) await new Promise<void>(resolve => {
      const timer = setTimeout(() => child.kill("SIGKILL"), 2000);
      child.once("exit", () => { clearTimeout(timer); resolve(); });
    });
  }
}

if (import.meta.main) {
  const [java, jar] = process.argv.slice(2);
  if (!java || !jar) throw new Error("usage: bun tools/codemode-parity/legacy-baseline.ts /absolute/java /absolute/splice.jar");
  const observed = await observeLegacyBaseline(java, jar);
  console.log(JSON.stringify({ case: "runtime.explicit-yield", ...observed }));
  assert.equal(observed.response.payload?.type, "yielded", "Codex yields a resumable cell here; the installed implementation must do the same");
}
