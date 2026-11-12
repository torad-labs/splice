import assert from "node:assert/strict";
import type { TraceEvent } from "./native-reference";

/** A killed production child must produce its typed operation failure, rather than a hung adapter. */
export function assertCandidateWorkerLoss(transcript: TraceEvent[]): void {
  const killed = transcript.findIndex(event => event.direction === "harness" && event.frame.type === "harness/native-host-kill");
  assert.ok(killed >= 0, "candidate worker-loss proof has no actual owned-child kill");
  const wait = transcript.slice(killed + 1).find(event => event.direction === "sent" &&
    event.frame.type === "operation/request" && event.frame.request?.method === "session/wait");
  assert.ok(wait, "candidate worker loss was not observed through wait");
  const reply = transcript.slice(killed + 1).find(event => event.direction === "received" &&
    event.frame.type === "operation/response" && event.frame.id === wait.frame.id)?.frame;
  assert.ok(reply, "candidate adapter did not serialize its worker-loss operation response");
  assert.equal(reply.result?.status, "error", "worker loss became successful model output");
  assert.equal(reply.result?.errorType, "splice.upstream.failure.CodeModeWorkerLostException",
    "worker loss lost its distinct infrastructure exception type");
  assert.equal(reply.result?.message, "Code-mode worker was stopped; source was not rerun");
}
