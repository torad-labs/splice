import { describe, expect, test } from "bun:test";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { associateCallbacks, auditNative, classifyRequest, compareSegments, decodeSse, gatewayEvidence, inspectTrial, sourceEvidence } from "./analyze-delivery-v3.ts";

const context = { model: "sol", sessionId: "session-one", userTexts: ["inspect the fixture"] };
const title = () => ({ model: "sol", input: [{ role: "developer", content: "You are naming a coding session. Return a title." }], text: { format: { type: "json_schema", name: "structured_output", schema: { properties: { title: { type: "string" } } } } } });
const task = () => ({ model: "sol", input: [{ role: "user", content: "inspect the fixture" }], reasoning: { effort: "high", summary: "detailed", context: "all_turns" } });
const jsonl = (...rows: unknown[]) => rows.map(r => JSON.stringify(r)).join("\n") + "\n";
const claude = (uuid: string, content: unknown[], stop = "end_turn") => ({ type: "assistant", uuid, sessionId: "session-one", message: { id: uuid, role: "assistant", content, stop_reason: stop } });
const r = (text: string) => ({ kind: "reasoning" as const, text });
const a = (text: string) => ({ kind: "prose" as const, text });

describe("strict phases", () => {
  test("only the full known title signature is auxiliary, including response.create wrapper", () => {
    expect(classifyRequest(title(), "/responses", context).phase).toBe("title");
    expect(classifyRequest({ type: "response.create", response: title() }, "/responses", context).phase).toBe("title");
    const otherInstruction = title(); otherInstruction.input[0]!.content = "Name a report";
    expect(classifyRequest(otherInstruction, "/responses", context).phase).toBe("unknown");
    const extraProperty = title(); Object.assign(extraProperty.text.format.schema.properties, { description: { type: "string" } });
    expect(classifyRequest(extraProperty, "/responses", context).phase).toBe("unknown");
    const otherFormat = title(); otherFormat.text.format.name = "other";
    expect(classifyRequest(otherFormat, "/responses", context).phase).toBe("unknown");
  });
  test("settings/model alone do not silently attribute a request", () => {
    expect(classifyRequest(task(), "/responses", context).phase).toBe("task");
    expect(classifyRequest({ ...task(), input: [] }, "/responses", context).phase).toBe("unknown");
    expect(classifyRequest({ ...task(), model: "different" }, "/responses", context).phase).toBe("unknown");
    expect(classifyRequest({ ...task(), session_id: "someone-else" }, "/responses", context).phase).toBe("unknown");
    expect(classifyRequest({ ...task(), text: { format: { schema: {} } } }, "/responses", context).phase).toBe("unknown");
    expect(classifyRequest({ ...task(), generate: false }, "/responses", context).phase).toBe("preparation");
    expect(classifyRequest({}, "/alpha/search", context).phase).toBe("search");
    expect(classifyRequest({}, "/models", context).phase).toBe("inventory");
    expect(classifyRequest(task(), "/surprise", context).phase).toBe("unknown");
  });
});

describe("captured SSE bytes", () => {
  test("UTF8 split at every byte and complete-frame chunk timestamps", () => {
    const encoded = new TextEncoder().encode('data: {"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"α🙂"}}\r\n\r\n');
    const result = decodeSse([...encoded].map((b, i) => ({ bytes: Uint8Array.of(b), at: i })));
    expect(result.events).toHaveLength(1);
    expect(result.events[0]!.data.delta.thinking).toBe("α🙂");
    expect(result.events[0]!.at).toBe(encoded.length - 1);
    expect(result.malformed + result.truncated + result.invalidUtf8).toBe(0);
  });
  test("truncation, malformed JSON and invalid UTF8 remain explicit", () => {
    expect(decodeSse([{ bytes: new TextEncoder().encode('data: {"type":"unfinished"}'), at: 1 }]).truncated).toBe(1);
    expect(decodeSse([{ bytes: new TextEncoder().encode('data: {broken}\n\n'), at: 1 }]).malformed).toBe(1);
    expect(decodeSse([{ bytes: Uint8Array.of(0xff), at: 1 }]).invalidUtf8).toBe(1);
  });
});

describe("genuine native transcripts", () => {
  test("Claude updates same UUID once, never deduplicates distinct equal-text blocks", () => {
    const first = claude("one", [{ type: "thinking", thinking: "R" }]);
    const audit = auditNative(jsonl(first, first, claude("one", [{ type: "thinking", thinking: "R2" }]), claude("two", [{ type: "thinking", thinking: "R2" }])), "claude", "session-one");
    expect(audit.thinkingCharacters).toBe(4);
    expect(audit.thinkingBlocks).toBe(2);
    expect(audit.revisions).toBe(2);
    expect(audit.duplicateSnapshots).toBe(1);
    expect(compareSegments([r("R2")], audit.segments).equal).toBe(false);
  });
  test("real blocks, tool boundaries, stop state and opaque/empty are counted", () => {
    const audit = auditNative(jsonl(
      claude("a", [{ type: "thinking", thinking: "R1" }, { type: "text", text: "A" }, { type: "thinking", thinking: "R2" }, { type: "tool_use", id: "c", name: "Read" }], "tool_use"),
      { type: "user", uuid: "u", sessionId: "session-one", message: { role: "user", content: [{ type: "tool_result", tool_use_id: "c", content: "ok" }] } },
      claude("b", [{ type: "thinking", thinking: "" }, { type: "redacted_thinking", data: "synthetic" }, { type: "text", text: "done" }]),
    ), "claude", "session-one");
    expect(audit.toolCalls).toBe(1); expect(audit.toolResults).toBe(1);
    expect(audit.opaqueBlocks).toBe(1); expect(audit.emptyThinkingBlocks).toBe(1);
    expect(audit.finalStop).toBe("end_turn");
    expect(audit.segments.slice(0, 4).map(s => s.kind)).toEqual(["reasoning", "prose", "reasoning", "tool"]);
  });
  test("Codex persistence task_complete is native evidence, not fabricated stream events", () => {
    const audit = auditNative(jsonl(
      { type: "session_meta", payload: { id: "session-one" } },
      { type: "response_item", payload: { type: "reasoning", id: "r", summary: [{ type: "summary_text", text: "R" }] } },
      { type: "response_item", payload: { type: "message", id: "a", role: "assistant", content: [{ type: "output_text", text: "done" }] } },
      { type: "event_msg", payload: { type: "task_complete", last_agent_message: "done" } },
    ), "codex", "session-one");
    expect(audit.thinkingCharacters).toBe(1); expect(audit.finalStop).toBe("task_complete");
    expect(audit.completed).toBe(true);
  });
  test("encrypted Codex persistence alone does not fabricate an opaque UI block", () => {
    const audit = auditNative(jsonl(
      { type: "session_meta", payload: { id: "session-one" } },
      { type: "response_item", payload: { type: "reasoning", id: "r", summary: [], encrypted_content: "synthetic" } },
      { type: "event_msg", payload: { type: "task_complete", last_agent_message: "done" } },
    ), "codex", "session-one");
    expect(audit.opaqueBlocks).toBe(0);
    expect(audit.hiddenReasoningItems).toBe(1);
    expect(audit.thinkingCharacters).toBe(0);
  });
  test("fake stream_event, wrong identity and malformed/truncated records cannot qualify", () => {
    expect(auditNative(jsonl({ type: "stream_event", event: { type: "message_stop" } }), "claude", "session-one").fabricatedRows).toBe(1);
    expect(auditNative(jsonl({ type: "system", subtype: "init", session_id: "session-one" }), "claude", "session-one").fabricatedRows).toBe(1);
    expect(auditNative(jsonl(claude("a", [])), "claude", "wrong").sessionMismatch).toBe(true);
    const audit = auditNative(jsonl(claude("a", [])) + '{"type":', "claude", "session-one");
    expect(audit.truncatedRows).toBe(1); expect(audit.completed).toBe(false);
  });
  test("a later user turn invalidates old completion; a later revision can complete the current turn", () => {
    const final = claude("a", [{ type: "text", text: "done" }]);
    const user = { type: "user", uuid: "new", sessionId: "session-one", message: { role: "user", content: "new turn" } };
    expect(auditNative(jsonl(final, user), "claude", "session-one").completed).toBe(false);
    expect(auditNative(jsonl(final, user, final), "claude", "session-one").completed).toBe(true);
  });
});

describe("exact readable evidence", () => {
  test("duplicate gateway block identity after stop is not mistaken for a fresh block", () => {
    const events = [
      { type: "message_start" },
      { type: "content_block_start", index: 0, content_block: { type: "thinking", thinking: "R" } },
      { type: "content_block_stop", index: 0 },
      { type: "content_block_start", index: 0, content_block: { type: "thinking", thinking: "R" } },
    ].map((data, at) => ({ at, data }));
    expect(gatewayEvidence(events).duplicateBlocks).toBe(1);
  });
  test("source snapshots do not duplicate deltas or move R across prose with sparse terminal", () => {
    const events = [
      { type: "response.output_item.added", output_index: 0, item: { type: "message", id: "a", content: [] } },
      { type: "response.output_text.delta", item_id: "a", output_index: 0, content_index: 0, delta: "A" },
      { type: "response.output_item.done", output_index: 0, item: { type: "message", id: "a", content: [{ type: "output_text", text: "A" }] } },
      { type: "response.output_item.added", output_index: 1, item: { type: "reasoning", id: "r", summary: [] } },
      { type: "response.reasoning_summary_text.delta", item_id: "r", output_index: 1, summary_index: 0, delta: "R" },
      { type: "response.reasoning_summary_text.done", item_id: "r", output_index: 1, summary_index: 0, text: "R" },
      { type: "response.completed", response: { output: [{ type: "reasoning", id: "r", summary: [{ type: "summary_text", text: "R" }] }] } },
    ].map((data, at) => ({ at, data }));
    expect(sourceEvidence(events).segments).toEqual([a("A"), r("R")]);
    const inconsistent: { at: number; data: Record<string, unknown> }[] = structuredClone(events);
    inconsistent[5]!.data = { ...inconsistent[5]!.data, text: "different" };
    expect(sourceEvidence(inconsistent).inconsistencies).toBeGreaterThan(0);
  });
  test("unclosed readable parts and unmodeled raw-reasoning events are explicit unknown evidence", () => {
    const open = sourceEvidence([{ at: 0, data: { type: "response.reasoning_summary_text.delta", output_index: 0, item_id: "r", delta: "prefix" } }]);
    expect(open.unclosedReadableParts).toBe(1);
    const raw = sourceEvidence([{ at: 0, data: { type: "response.reasoning_text.delta", output_index: 0, item_id: "r", delta: "raw" } }]);
    expect(raw.unmodeledReadableEvents).toBe(1);
    expect(raw.rawReasoningCharacters).toBe(3);
  });
  test("only declared paragraph separators at adjacent reasoning part boundaries", () => {
    expect(compareSegments([r("R1"), r("R2")], [r("R1\n\nR2")]).equal).toBe(true);
    expect(compareSegments([r("R1"), r("R2")], [r("R1 R2")]).equal).toBe(false);
    expect(compareSegments([r("R1"), a("A"), r("R2")], [r("R1\n\nR2"), a("A")]).equal).toBe(false);
    expect(compareSegments([r("R")], [r("R"), r("R")]).equal).toBe(false);
    expect(compareSegments([r("R ")], [r("R")]).equal).toBe(false);
  });
  test("task callback window ignores title interleave, marks unknown interleave ambiguous", () => {
    const tasks = [{ requestAt: 0, itemDoneAt: 10, terminalAt: 100 }, { requestAt: 90, itemDoneAt: 95, terminalAt: 110 }];
    const tool = [{ at: 70, id: "client-call", name: "Bash" }];
    expect(associateCallbacks(tasks, tool, []).map(r => r.firstToolAfterItemMs)).toEqual([60, null]);
    expect(associateCallbacks(tasks, tool, [50])[0]!.ambiguous).toBe(true);
    expect(associateCallbacks(tasks, [{ at: 5, id: "early", name: "Bash" }], [])[0]!.firstToolAfterItemMs).toBe(-5);
    expect(associateCallbacks([{ ...tasks[0]!, nextTaskRequestAt: 60 }], tool, [])[0]!.firstToolAfterItemMs).toBeNull();
  });
  test("missing native and wire artifacts remain explicit unknown, not a zero-block pass", async () => {
    const dir = mkdtempSync(join(tmpdir(), "analysis-v3-missing-"));
    try {
      const report = await inspectTrial(dir, "splice", "sol");
      expect(report.qualified).toBe(false);
      expect(report.missingArtifacts).toContain("events.jsonl");
      expect(report.missingArtifacts).toContain("requests/client.jsonl");
    } finally { rmSync(dir, { recursive: true, force: true }); }
  });
  test("full artifact pipeline ignores a title between item.done and its real callback", async () => {
    const dir = mkdtempSync(join(tmpdir(), "analysis-v3-pipeline-"));
    try {
      mkdirSync(join(dir, "requests"));
      const nativeFile = join(dir, "control", "session.jsonl");
      const session = { invocation_mode: "interactive-pty-v1", completion: { sessionId: "session-one", sourceFiles: [nativeFile] }, completed: true, timedOut: false, exit: 0 };
      writeFileSync(join(dir, "interactive-session.json"), JSON.stringify(session));
      writeFileSync(join(dir, "events.jsonl"), jsonl(
        { type: "user", uuid: "u", sessionId: "session-one", message: { role: "user", content: "inspect the fixture" } },
        claude("a", [{ type: "thinking", thinking: "R" }, { type: "tool_use", id: "c", name: "Read" }], "tool_use"),
        claude("b", [{ type: "text", text: "done" }]),
      ));
      mkdirSync(join(dir, "control"));
      writeFileSync(nativeFile, await Bun.file(join(dir, "events.jsonl")).arrayBuffer().then(b => Buffer.from(b)));
      const req = (at: number, connection: number, body: unknown) => ({ kind: "request", at_ms: at, connection, path: "/responses", transport: "ws", text: JSON.stringify(body) });
      const frame = (at: number, connection: number, data: unknown) => ({ kind: "response_frame", at_ms: at, connection, text: JSON.stringify(data) });
      writeFileSync(join(dir, "requests/upstream.jsonl"), jsonl(
        req(0, 1, task()),
        frame(10, 1, { type: "response.output_item.done", output_index: 0, item: { type: "reasoning", id: "r", summary: [{ type: "summary_text", text: "R" }] } }),
        frame(20, 1, { type: "response.output_item.done", output_index: 1, item: { type: "custom_tool_call", id: "e", call_id: "exec" } }),
        req(30, 2, title()), frame(40, 2, { type: "response.completed", response: { id: "title", output: [] } }),
        frame(90, 1, { type: "response.completed", response: { id: "task-one", output: [] } }),
        req(100, 1, { ...task(), input: [], previous_response_id: "task-one" }),
        frame(110, 1, { type: "response.output_item.done", output_index: 0, item: { type: "message", id: "m", content: [{ type: "output_text", text: "done" }] } }),
        frame(120, 1, { type: "response.completed", response: { id: "task-two", output: [] } }),
      ));
      const sse = (at: number, connection: number, data: unknown) => ({ kind: "response_chunk", at_ms: at, connection, raw_base64: Buffer.from(`data: ${JSON.stringify(data)}\n\n`).toString("base64") });
      writeFileSync(join(dir, "requests/client.jsonl"), jsonl(
        req(0, 1, { messages: [] }), sse(1, 1, { type: "message_start" }),
        sse(10, 1, { type: "content_block_start", index: 0, content_block: { type: "thinking", thinking: "R" } }), sse(11, 1, { type: "content_block_stop", index: 0 }),
        sse(70, 1, { type: "content_block_start", index: 1, content_block: { type: "tool_use", id: "c", name: "Read" } }), sse(71, 1, { type: "content_block_stop", index: 1 }), sse(72, 1, { type: "message_stop" }),
        req(100, 2, { messages: [] }), sse(101, 2, { type: "message_start" }),
        sse(110, 2, { type: "content_block_start", index: 0, content_block: { type: "text", text: "done" } }), sse(111, 2, { type: "content_block_stop", index: 0 }), sse(120, 2, { type: "message_stop" }),
      ));
      const report = await inspectTrial(dir, "splice", "sol");
      expect(report.totals.phases.title).toBe(1); expect(report.totals.taskSummaryDenominator).toBe(2);
      expect(report.dispatch[0]!.firstToolAfterItemMs).toBe(50);
      expect(report.sourceToNative.equal).toBe(true); expect(report.sourceToGateway?.equal).toBe(true);
      expect(report.qualified).toBe(true);
      writeFileSync(join(dir, "interactive-session.json"), JSON.stringify({ ...session, exit: 1 }));
      expect((await inspectTrial(dir, "splice", "sol")).qualified).toBe(false);
      writeFileSync(join(dir, "interactive-session.json"), JSON.stringify(session));
      writeFileSync(nativeFile, "different source bytes\n");
      expect((await inspectTrial(dir, "splice", "sol")).qualified).toBe(false);
    } finally { rmSync(dir, { recursive: true, force: true }); }
  });
});
