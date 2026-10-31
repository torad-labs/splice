import assert from "node:assert/strict";
import { sha256 } from "./qualification";

/** This is a dedicated Linux scheduling witness. The ordinary delivered-notification case stays mandatory. */
export const preDispatchControl = {
  schema: 1,
  id: "cli-pre-dispatch/readiness-cancellation",
  sourceRevision: "360319d8dee62d87e2d59fc0c21805d79519cfed",
  sourceCliSha256: "9f373de14829ef33c372ae9751ec958394624be1be5d35f2031fcdcef7055a0c",
  nativeHostSha256: "522935d4392ec9674fab60b239b5495992c6a93e4deb65113a216b6f5451ccd4",
  sourceUnit: {
    id: "codex-rs/core/src/tools/code_mode/delegate.rs#function_item@10757",
    sha256: "ef71ef5a3668becd82c622f98d387c891188c62765cc3cd2c2e30a6e37fc8ef3",
    file: "codex-rs/core/src/tools/code_mode/delegate.rs", start: 10757, end: 11068,
  },
  symbols: {
    ready: "_RNvMNtNtNtCsak7ms9wL4Bg_10codex_core5tools9code_mode8delegateNtB2_22CodeModeDispatchBroker28mark_cell_ready_for_dispatch",
    wait: "_RNCNCNvNtNtNtCsak7ms9wL4Bg_10codex_core5tools9code_mode8delegate34wait_until_cell_ready_for_dispatch00Bb_",
    remove: "_RNvNtNtNtCsak7ms9wL4Bg_10codex_core5tools9code_mode8delegate20remove_dispatch_gate",
  },
  scheduling: {
    startup: "ptrace exec catchpoint through same-PID env wrapper",
    applicationBreakpoints: "hardware execution; ready entry before mutex, actual select-poll closure entry, remove entry",
    loaderDisclosure: "GDB internal shared-library loader software breakpoint may patch loader instructions; visible in raw MI",
    readyIgnoreCount: 1, tokioWorkers: 4, memoryBytes: 4 * 1024 ** 3, swapBytes: 0, timeoutMs: 180_000,
  },
  candidateBoundary: "cancellable public notifications permit before provider delivery; actual observe entry after ownership registration",
  candidateAction: "ordinary request cancellation followed by authenticated production HTTP interruption of exact owned cell",
  initialPrompt: "Run the fixed local dispatch-gate probe in this disposable workspace.",
  resumePrompt: "Continue the fixed local probe after cancellation before dispatch readiness.",
  scripts: [
    'store("stable-store","completed-before-pre-ready-cancellation"); text("baseline-stored");',
    '// @exec: {"yield_time_ms":30000}\nstore("interrupted-store","uncommitted-before-pre-ready-cancellation"); notify("PRE_READY_NOTICE_MUST_NOT_DELIVER_@NONCE@"); await new Promise(r=>setTimeout(r,30000)); text("MUST_NOT_RUN_AFTER_PRE_READY_CANCEL_@NONCE@");',
    'text({tag:"after-pre-ready-cancellation",stable:load("stable-store"),interrupted:load("interrupted-store")});',
  ],
  resumedValue: '{"tag":"after-pre-ready-cancellation","stable":"completed-before-pre-ready-cancellation"}',
} as const;
export const preDispatchInputSha256 = sha256(JSON.stringify(preDispatchControl));
export function preDispatchScenario(nonce: string) {
  assert.match(nonce, /^[a-f0-9]{32}$/);
  return { schema: 1, nonce, scripts: preDispatchControl.scripts.map(source => source.replaceAll("@NONCE@", nonce)),
    notice: `PRE_READY_NOTICE_MUST_NOT_DELIVER_${nonce}`, forbidden: `MUST_NOT_RUN_AFTER_PRE_READY_CANCEL_${nonce}`,
    resumePrompt: preDispatchControl.resumePrompt };
}

export type PreDispatchEvent = { kind: string; at?: number; atMs?: number; value: Record<string, any> };
export type PreDispatchFacts = {
  runId: string; inputSha256: string; sourcePid: number; profilePid: number; sourceStartTicks: string;
  sourceCliSha256: string; candidateJarSha256: string; expectedCandidateJarSha256: string;
  sourceUnit: { id: string; sha256: string }; sourceCount: number;
  sourceEvents: PreDispatchEvent[]; candidateEvents: PreDispatchEvent[];
  sourceTaskRequests: number; candidateTaskRequests: number;
  sourceResumedValue: string; candidateResumedValue: string;
  sourceNoticeDelivered: boolean; candidateNoticeDelivered: boolean; durableNoticeCount: number;
  sourceCompleted: boolean; candidateCompleted: boolean; loaderBreakpointDisclosed: boolean;
};
/** Derived from bound raw controls and ordinary responses, never from case labels or a broad-suite result. */
export function assertPreDispatchFacts(facts: PreDispatchFacts): void {
  const scenario = preDispatchScenario(facts.runId);
  assert.equal(facts.inputSha256, preDispatchInputSha256, "stable input recipe changed");
  assert.ok(Number.isSafeInteger(facts.sourcePid) && facts.sourcePid > 0);
  assert.equal(facts.profilePid, facts.sourcePid, "profile PID differs from actual MI inferior");
  assert.match(facts.sourceStartTicks, /^\d+$/);
  assert.equal(facts.sourceCliSha256, preDispatchControl.sourceCliSha256);
  assert.equal(facts.candidateJarSha256, facts.expectedCandidateJarSha256);
  assert.deepEqual(facts.sourceUnit, { id: preDispatchControl.sourceUnit.id, sha256: preDispatchControl.sourceUnit.sha256 });
  assert.equal(facts.sourceCount, 1, "the actual gate removal must have one positive exact-source entry");
  assert.equal(facts.loaderBreakpointDisclosed, true, "debugger loader mechanics were not disclosed");
  const once = (events: PreDispatchEvent[], kind: string) => {
    const found = events.filter(event => event.kind === kind); assert.equal(found.length, 1, `missing/duplicate ${kind}`); return found[0]!;
  };
  const sourceKinds = ["attested_inferior", "gate_wait_false_observed", "pty_interrupt_before_ready", "removed_before_ready_released", "pty_resume_after_abort", "pty_submit_resume"];
  const source = sourceKinds.map(kind => once(facts.sourceEvents, kind));
  assert.ok(source.every((event, index) => index === 0 || facts.sourceEvents.indexOf(event) > facts.sourceEvents.indexOf(source[index - 1]!)), "source controls were reordered");
  assert.deepEqual(source.map(event => event.at), [...source.map(event => event.at)].sort((a, b) => a! - b!));
  assert.equal(source[0]!.value.pid, facts.sourcePid); assert.equal(source[0]!.value.startTicks, facts.sourceStartTicks);
  assert.equal(Buffer.from(source[2]!.value.raw_base64, "base64").toString(), "\x03");
  assert.equal(Buffer.from(source[4]!.value.raw_base64, "base64").toString(), preDispatchControl.resumePrompt);
  assert.equal(Buffer.from(source[5]!.value.raw_base64, "base64").toString(), "\r");
  const pending = once(facts.candidateEvents, "actual_notification_pending");
  const owned = once(facts.candidateEvents, "both_owned_boundaries_observed");
  const cancelled = once(facts.candidateEvents, "ordinary_request_cancelled");
  const retired = once(facts.candidateEvents, "actual_notification_delivery_cancelled");
  const http = once(facts.candidateEvents, "production_http_interrupt");
  const resumed = once(facts.candidateEvents, "resumed_native_success");
  const late = once(facts.candidateEvents, "late_permit_released");
  const candidate = [pending, owned, cancelled, retired, http, resumed, late];
  assert.ok(candidate.every((event, index) => index === 0 || facts.candidateEvents.indexOf(event) > facts.candidateEvents.indexOf(candidate[index - 1]!)), "candidate controls were reordered");
  const time = (event: PreDispatchEvent) => event.atMs!;
  assert.ok(time(pending) <= time(owned) && time(owned) < time(cancelled) && time(cancelled) < time(retired) &&
    time(retired) <= time(http) && time(http) < time(resumed) && time(resumed) <= time(late), "candidate cancellation/permit order differs");
  assert.ok(facts.candidateEvents.some(event => event.kind === "native_observe_entry" && event.value.target &&
    event.value.cellId === pending.value.cellId && time(event) <= time(owned)), "provider ownership was not observed");
  assert.equal(owned.value.cellId, pending.value.cellId); assert.equal(owned.value.callId, pending.value.callId);
  assert.equal(pending.value.callId, `pre-ready-exec-1-${facts.runId}`); assert.equal(pending.value.text, scenario.notice);
  assert.equal(owned.value.session, `pre-ready-session-${facts.runId}`); assert.equal(owned.value.conversation, `pre-ready-conversation-${facts.runId}`);
  assert.equal(cancelled.value.pendingDeliveryJobActive, true, "request cancellation alone was substituted for authoritative interruption");
  assert.equal(retired.value.cellId, pending.value.cellId); assert.equal(retired.value.callId, pending.value.callId);
  assert.equal(http.value.boundary, "authenticated-production-control-http");
  assert.equal(http.value.method, "POST"); assert.equal(http.value.anonymousStatus, 401); assert.equal(http.value.sessionKeyStatus, 401); assert.equal(http.value.status, 200);
  assert.equal(http.value.path, `/api/heads/native-interrupt-probe/sessions/${encodeURIComponent(owned.value.session)}/interrupt`);
  assert.deepEqual(http.value.request, { conversation_key: owned.value.conversation });
  assert.deepEqual(http.value.response, { head: "native-interrupt-probe", session: owned.value.session,
    conversation_key: owned.value.conversation, cells: [{ cell_id: pending.value.cellId, terminated: true, failure: null }] });
  assert.deepEqual(late.value, { forwarded: 0, cancelled: true });
  assert.equal(facts.sourceTaskRequests, 4); assert.equal(facts.candidateTaskRequests, 4);
  assert.equal(facts.sourceResumedValue, preDispatchControl.resumedValue); assert.equal(facts.candidateResumedValue, preDispatchControl.resumedValue);
  assert.equal(facts.sourceNoticeDelivered, false); assert.equal(facts.candidateNoticeDelivered, false); assert.equal(facts.durableNoticeCount, 0);
  assert.equal(facts.sourceCompleted, true); assert.equal(facts.candidateCompleted, true);
}
