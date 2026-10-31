import { describe, expect, test } from "bun:test";
import { assertPreDispatchFacts, preDispatchControl, preDispatchInputSha256, preDispatchScenario, type PreDispatchFacts } from "./current-pre-dispatch-contract";

function observedFacts(): PreDispatchFacts {
  const nonce = "a".repeat(32), cell = "native-generation:2", call = `pre-ready-exec-1-${nonce}`, session = `pre-ready-session-${nonce}`, conversation = `pre-ready-conversation-${nonce}`;
  return { runId: nonce, inputSha256: preDispatchInputSha256, sourcePid: 4312, profilePid: 4312, sourceStartTicks: "34567",
    sourceCliSha256: preDispatchControl.sourceCliSha256, candidateJarSha256: "b".repeat(64), expectedCandidateJarSha256: "b".repeat(64),
    sourceUnit: { id: preDispatchControl.sourceUnit.id, sha256: preDispatchControl.sourceUnit.sha256 }, sourceCount: 1,
    sourceEvents: [
      { kind: "attested_inferior", at: 1, value: { pid: 4312, startTicks: "34567" } },
      { kind: "gate_wait_false_observed", at: 2, value: {} },
      { kind: "pty_interrupt_before_ready", at: 3, value: { raw_base64: "Aw==" } },
      { kind: "removed_before_ready_released", at: 4, value: {} },
      { kind: "pty_resume_after_abort", at: 5, value: { raw_base64: Buffer.from(preDispatchControl.resumePrompt).toString("base64") } },
      { kind: "pty_submit_resume", at: 6, value: { raw_base64: "DQ==" } },
    ],
    candidateEvents: [
      { kind: "native_observe_entry", atMs: 0, value: { cellId: cell, target: true } },
      { kind: "actual_notification_pending", atMs: 1, value: { cellId: cell, callId: call, text: preDispatchScenario(nonce).notice } },
      { kind: "both_owned_boundaries_observed", atMs: 2, value: { cellId: cell, callId: call, session, conversation } },
      { kind: "ordinary_request_cancelled", atMs: 3, value: { pendingDeliveryJobActive: true } },
      { kind: "actual_notification_delivery_cancelled", atMs: 4, value: { cellId: cell, callId: call } },
      { kind: "production_http_interrupt", atMs: 5, value: { boundary: "authenticated-production-control-http", method: "POST",
        path: `/api/heads/native-interrupt-probe/sessions/${session}/interrupt`, request: { conversation_key: conversation }, anonymousStatus: 401, sessionKeyStatus: 401, status: 200,
        response: { head: "native-interrupt-probe", session, conversation_key: conversation, cells: [{ cell_id: cell, terminated: true, failure: null }] } } },
      { kind: "resumed_native_success", atMs: 6, value: { outcome: "Success" } },
      { kind: "late_permit_released", atMs: 7, value: { forwarded: 0, cancelled: true } },
    ], sourceTaskRequests: 4, candidateTaskRequests: 4, sourceResumedValue: preDispatchControl.resumedValue, candidateResumedValue: preDispatchControl.resumedValue,
    sourceNoticeDelivered: false, candidateNoticeDelivered: false, durableNoticeCount: 0, sourceCompleted: true, candidateCompleted: true, loaderBreakpointDisclosed: true };
}
const changed = (change: (facts: PreDispatchFacts) => void) => { const facts = observedFacts(); change(facts); return facts; };
describe("dedicated pre-dispatch evidence boundary", () => {
  test("the stable contract is independent of a fresh nonce", () => {
    const first = preDispatchScenario("1".repeat(32)), second = preDispatchScenario("2".repeat(32));
    expect(first.scripts.map(source => source.replaceAll(first.nonce, "@NONCE@"))).toEqual(second.scripts.map(source => source.replaceAll(second.nonce, "@NONCE@")));
    expect(() => assertPreDispatchFacts(observedFacts())).not.toThrow();
  });
  const invalid: Array<[string, (facts: PreDispatchFacts) => void]> = [
    ["profile belongs to a different process", facts => { facts.profilePid++; }],
    ["inferior was not the attested process", facts => { facts.sourceEvents[0]!.value.pid++; }],
    ["source body hash differs", facts => { facts.sourceUnit.sha256 = "0".repeat(64); }],
    ["qualified source name belongs to another namespace", facts => { facts.sourceUnit.id = facts.sourceUnit.id.replace("code_mode", "other_namespace"); }],
    ["source binary differs", facts => { facts.sourceCliSha256 = "0".repeat(64); }],
    ["candidate jar differs", facts => { facts.candidateJarSha256 = "0".repeat(64); }],
    ["the source function was never entered", facts => { facts.sourceCount = 0; }],
    ["source control happened in the wrong order", facts => { facts.sourceEvents[3]!.at = 2; }],
    ["same-timestamp control rows were reordered", facts => { facts.sourceEvents[2]!.at = facts.sourceEvents[3]!.at; [facts.sourceEvents[2], facts.sourceEvents[3]] = [facts.sourceEvents[3]!, facts.sourceEvents[2]!]; }],
    ["a tool packet replaced genuine Ctrl-C", facts => { facts.sourceEvents[2]!.value.raw_base64 = Buffer.from("terminate").toString("base64"); }],
    ["candidate observed a different owner", facts => { facts.candidateEvents[0]!.value.cellId = "another-cell"; }],
    ["callback cancellation belongs to another call", facts => { facts.candidateEvents[4]!.value.callId = "another-call"; }],
    ["all callback controls consistently substituted a different exec", facts => { for (const event of facts.candidateEvents) if (event.value.callId) event.value.callId = "another-call"; }],
    ["request cancellation alone was substituted", facts => { facts.candidateEvents[3]!.value.pendingDeliveryJobActive = false; }],
    ["no actual authenticated200 outcome", facts => { facts.candidateEvents[5]!.value.status = 503; }],
    ["anonymous caller could interrupt", facts => { facts.candidateEvents[5]!.value.anonymousStatus = 200; }],
    ["authenticated request terminated another cell", facts => { facts.candidateEvents[5]!.value.response.cells[0].cell_id = "another-cell"; }],
    ["permit was released before resumed native completion", facts => { facts.candidateEvents[7]!.atMs = 5; }],
    ["late permit forwarded the stale callback", facts => { facts.candidateEvents[7]!.value.forwarded = 1; }],
    ["notice escaped to the current client", facts => { facts.candidateNoticeDelivered = true; }],
    ["notice was durably retained despite cancellation", facts => { facts.durableNoticeCount = 1; }],
    ["interrupted store was committed", facts => { facts.candidateResumedValue = '{"stable":"completed-before-pre-ready-cancellation","interrupted":"uncommitted-before-pre-ready-cancellation"}'; }],
    ["resumed native execution did not complete", facts => { facts.candidateCompleted = false; }],
    ["GDB internal loader breakpoint was undisclosed", facts => { facts.loaderBreakpointDisclosed = false; }],
  ];
  for (const [name, change] of invalid) test(name, () => { expect(() => assertPreDispatchFacts(changed(change))).toThrow(); });
});
