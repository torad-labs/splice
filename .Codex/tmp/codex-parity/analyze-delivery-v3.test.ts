import { describe, expect, test } from "bun:test";
import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { associateCallbacks, auditNative, classifyRequest, compareSegments, decodeSse, gatewayEvidence, inspectTrial, main, sourceEvidence } from "./analyze-delivery-v3.ts";

const context = { model: "sol", sessionId: "session-one", userTexts: ["inspect the fixture"] };
const title = () => ({ model: "sol", input: [{ role: "developer", content: "You are naming a coding session. Return a title." }], text: { format: { type: "json_schema", name: "structured_output", schema: { properties: { title: { type: "string" } } } } } });
const task = () => ({ model: "sol", input: [{ role: "user", content: "inspect the fixture" }], reasoning: { effort: "high", summary: "detailed", context: "all_turns" } });
const jsonl = (...rows: unknown[]) => rows.map(r => JSON.stringify(r)).join("\n") + "\n";
const claude = (uuid: string, content: unknown[], stop = "end_turn") => ({ type: "assistant", uuid, sessionId: "session-one", message: { id: uuid, role: "assistant", content, stop_reason: stop } });
const r = (text: string) => ({ kind: "reasoning" as const, text });
const a = (text: string) => ({ kind: "prose" as const, text });
const profile = { effort: "high", summary: "detailed", context: "all_turns" };
type FixtureRow = Record<string, any>;
function protocolTrial() {
  const dir = mkdtempSync(join(tmpdir(), "analysis-v3-r2-"));
  mkdirSync(join(dir, "requests")); mkdirSync(join(dir, "control"));
  const source = join(dir, "control", "native.jsonl");
  const native = jsonl(
    { type: "user", uuid: "u", sessionId: "session-one", message: { role: "user", content: "inspect the fixture" } },
    claude("a", [{ type: "thinking", thinking: "R" }, { type: "text", text: "done" }]),
  );
  writeFileSync(source, native); writeFileSync(join(dir, "events.jsonl"), native);
  writeFileSync(join(dir, "interactive-session.json"), JSON.stringify({ invocation_mode: "interactive-pty-v1", completed: true, timedOut: false, exit: 0, completion: { sessionId: "session-one", sourceFiles: [source] } }));
  const req = (at: number, connection: number, path: string, body?: unknown): FixtureRow => ({ kind: "request", at_ms: at, connection, method: body === undefined ? "GET" : "POST", path, transport: "http", text: body === undefined ? "" : JSON.stringify(body) });
  const frame = (at: number, data: unknown): FixtureRow => ({ kind: "response_frame", at_ms: at, connection: 1, text: JSON.stringify(data) });
  const chunk = (at: number, connection: number, text: string): FixtureRow => ({ kind: "response_chunk", at_ms: at, connection, raw_base64: Buffer.from(text).toString("base64") });
  const sse = (at: number, data: unknown) => chunk(at, 1, `data: ${JSON.stringify(data)}\n\n`);
  const upstream: FixtureRow[] = [req(0, 1, "/responses", task()),
    frame(10, { type: "response.output_item.done", output_index: 0, item: { type: "reasoning", id: "r", summary: [{ type: "summary_text", text: "R" }] } }),
    frame(20, { type: "response.output_item.done", output_index: 1, item: { type: "message", id: "m", content: [{ type: "output_text", text: "done" }] } }),
    frame(30, { type: "response.completed", response: { id: "root", output: [] } }),
  ];
  const client: FixtureRow[] = [req(0, 1, "/v1/messages", { model: "claude-codex--sol", messages: [{ role: "user", content: "inspect the fixture" }] }),
    { kind: "response", at_ms: 1, connection: 1, status: 200, content_type: "text/event-stream" },
    sse(2, { type: "message_start" }), sse(10, { type: "content_block_start", index: 0, content_block: { type: "thinking", thinking: "R" } }),
    sse(11, { type: "content_block_stop", index: 0 }), sse(20, { type: "content_block_start", index: 1, content_block: { type: "text", text: "done" } }),
    sse(21, { type: "content_block_stop", index: 1 }), sse(30, { type: "message_stop" }),
  ];
  const save = () => { writeFileSync(join(dir, "requests/upstream.jsonl"), jsonl(...upstream)); writeFileSync(join(dir, "requests/client.jsonl"), jsonl(...client)); };
  return { dir, upstream, client, req, frame, chunk, save, close: () => rmSync(dir, { recursive: true, force: true }) };
}

describe("r3 actual Anthropic title output contracts", () => {
  const schema = { type: "object", properties: { title: { type: "string" } }, required: ["title"] };
  const native = { type: "json_schema", schema };
  const body = (extra: FixtureRow) => ({ model: "claude-codex--sol", system: [{ type: "text", text: "You are naming a coding session. Return a title." }],
    messages: [{ role: "user", content: "inspect the fixture" }], stream: false, ...extra });
  const titleResponse = (f: ReturnType<typeof protocolTrial>, request: FixtureRow) => {
    f.client.push(f.req(40, 9, "/v1/messages", request),
      { kind: "response", at_ms: 41, connection: 9, status: 200, content_type: "application/json" },
      f.chunk(42, 9, JSON.stringify({ id: "msg-title", type: "message", role: "assistant", content: [{ type: "text", text: '{"title":"fixture"}' }], stop_reason: "end_turn" })));
    f.save();
  };
  test.each(["output_config", "output_format"] as const)("real /v1/messages %s title normalizes missing schema name canonically", async shape => {
    const f = protocolTrial();
    try {
      titleResponse(f, body(shape === "output_config" ? { output_config: { format: native } } : { output_format: native }));
      const report = await inspectTrial(f.dir, "splice", "sol", profile);
      const aux = report.downstreamRequests.find(x => x.requestAt === 40)!;
      expect(aux.route).toBe("/v1/messages"); expect(aux.phase).toBe("title");
      expect(aux.evidence.structuredOutput).toBe(true); expect(aux.evidence.namingDeveloper).toBe(true);
      expect(report.sourceToGateway?.equal).toBe(true); expect(report.qualified).toBe(true);
    } finally { f.close(); }
  });
  test("malformed native formats and unknown title lookalikes remain unknown, even with matching task text", async () => {
    const malformed: unknown[] = [null, "json_schema", [], { type: "json_object", schema }, { type: "json_schema", schema: [] },
      { type: "json_schema", schema: null }, { ...native, name: "other" }, { ...native, name: "" },
      { type: "json_schema", schema: { properties: { title: { type: "number" } } } },
      { type: "json_schema", schema: { properties: { title: { type: "string" }, other: { type: "string" } } } }];
    for (const format of malformed) for (const shape of ["output_config", "output_format"]) {
      const f = protocolTrial();
      try {
        titleResponse(f, body(shape === "output_config" ? { output_config: { format } } : { output_format: format }));
        const report = await inspectTrial(f.dir, "splice", "sol", profile);
        expect(report.downstreamRequests.find(x => x.requestAt === 40)!.phase).toBe("unknown");
        expect(report.qualified).toBe(false);
      } finally { f.close(); }
    }
  });
  test("output_config.format takes precedence including JSON null; absent format falls back to legacy field", async () => {
    for (const [extra, expected] of [
      [{ output_config: { format: native }, output_format: { ...native, name: "wrong" } }, "title"],
      [{ output_config: { format: null }, output_format: native }, "unknown"],
      [{ output_config: {}, output_format: native }, "title"],
    ] as const) {
      const f = protocolTrial();
      try {
        titleResponse(f, body(extra));
        expect((await inspectTrial(f.dir, "splice", "sol", profile)).downstreamRequests.find(x => x.requestAt === 40)!.phase).toBe(expected);
      } finally { f.close(); }
    }
  });
});

describe("r3 canonical scalar names", () => {
  test("schema-name defaulting and strict=true follow JsonScalars without relaxing the developer instruction", async () => {
    const native = { type: "json_schema", schema: { properties: { title: { type: "string" } } } };
    for (const [name, expected, defaulted] of [[null, "title", true], [{}, "title", true], [false, "unknown", false], [0, "unknown", false], ["structured_output", "title", false]] as const) {
      const f = protocolTrial();
      try {
        const request = { system: "You are naming a coding session", messages: [{ role: "user", content: "inspect the fixture" }], stream: false,
          output_config: { format: { ...native, name, strict: false } } };
        f.client.push(f.req(40, 9, "/v1/messages", request), { kind: "response", at_ms: 41, connection: 9, status: 200, content_type: "application/json" }, f.chunk(42, 9, '{"content":[]}'));
        f.save();
        const aux: FixtureRow = (await inspectTrial(f.dir, "splice", "sol", profile)).downstreamRequests.find(x => x.requestAt === 40)!;
        expect(aux.phase).toBe(expected); expect(aux.nativeOutputFormat.defaultNameApplied).toBe(defaulted); expect(aux.nativeOutputFormat.strict).toBe(true);
        request.system = "An unknown naming instruction";
        f.client[f.client.length - 3]!.text = JSON.stringify(request); f.save();
        expect((await inspectTrial(f.dir, "splice", "sol", profile)).downstreamRequests.find(x => x.requestAt === 40)!.phase).toBe("unknown");
      } finally { f.close(); }
    }
  });
});

describe("r2 protocol and explicit profile regressions", () => {
  test("CLI refuses a partial bench.json expectation and requires explicit three-field profile", async () => {
    const run = mkdtempSync(join(tmpdir(), "analysis-v3-cli-"));
    try {
      writeFileSync(join(run, "manifest.json"), JSON.stringify({ config: { model: "sol", effort: "high" } }));
      writeFileSync(join(run, "results.json"), "[]");
      const missing = join(run, "no-profile.json"), declared = join(run, "explicit-profile.json");
      expect(await main([run, missing])).toBe(2); expect(existsSync(missing)).toBe(false);
      expect(await main([run, declared, "--expected-profile", "high-detailed-all_turns-v1"])).toBe(1);
      expect((await Bun.file(declared).json()).expected_reasoning_profile).toEqual(profile);
    } finally { rmSync(run, { recursive: true, force: true }); }
  });
  test("two valid GET/models JSON responses are auxiliary, not truncated SSE", async () => {
    const f = protocolTrial();
    try {
      for (const connection of [10, 11]) for (const rows of [f.upstream, f.client]) rows.unshift(
        f.req(-30 + connection, connection, "/models"),
        { kind: "response", at_ms: -19 + connection, connection, status: 200, content_type: "application/json" },
        f.chunk(-18 + connection, connection, '{"data":[{"id":"sol"}]}'),
      );
      f.save(); const report = await inspectTrial(f.dir, "splice", "sol", profile);
      expect(report.captureDiagnostics.model.truncatedFrames).toBe(0);
      expect(report.captureDiagnostics.gateway?.truncatedFrames).toBe(0);
      expect(report.totals.phases.inventory).toBe(2); expect(report.qualified).toBe(true);
    } finally { f.close(); }
  });
  test("strict JSON titles are separated from task gateway text, unknown downstream requests block", async () => {
    const f = protocolTrial();
    try {
      for (const rows of [f.upstream, f.client]) rows.push(f.req(40, 9, "/responses", { ...title(), stream: false }),
        { kind: "response", at_ms: 41, connection: 9, status: 200, content_type: "application/json" },
        f.chunk(42, 9, '{"id":"title","output":[{"type":"message","content":[{"type":"output_text","text":"auxiliary title"}]}]}'));
      f.upstream.push(f.req(43, 12, "/responses", { ...title(), stream: true }),
        { kind: "response", at_ms: 44, connection: 12, status: 200, content_type: "text/event-stream" },
        f.chunk(45, 12, 'data: {"type":"response.completed","response":{"id":"stream-title","output":[{"type":"message","id":"title-m","content":[{"type":"output_text","text":"auxiliary SSE title"}]}]}}\n\n'));
      f.client.push(f.req(43, 12, "/responses", { ...title(), stream: true }),
        { kind: "response", at_ms: 44, connection: 12, status: 200, content_type: "text/event-stream" },
        f.chunk(45, 12, [{ type: "message_start" }, { type: "content_block_start", index: 0, content_block: { type: "text", text: "auxiliary SSE title" } }, { type: "content_block_stop", index: 0 }, { type: "message_stop" }].map(e => `data: ${JSON.stringify(e)}\n\n`).join("")));
      f.save(); let report = await inspectTrial(f.dir, "splice", "sol", profile);
      expect(report.qualified).toBe(true); expect(report.sourceToGateway?.equal).toBe(true);
      f.client.push(f.req(50, 15, "/v1/messages", { messages: [{ role: "user", content: "unrelated request" }] }),
        { kind: "response", at_ms: 51, connection: 15, status: 200, content_type: "application/json" }, f.chunk(52, 15, '{"content":[]}'));
      f.save(); report = await inspectTrial(f.dir, "splice", "sol", profile);
      expect(report.qualified).toBe(false); expect(report.checks.noUnknownDownstreamRequests).toBe(false);
    } finally { f.close(); }
  });
  test("invalid inventory JSON and explicitly truncated SSE still fail", async () => {
    const f = protocolTrial();
    try {
      f.upstream.push(f.req(40, 9, "/models"), { kind: "response", at_ms: 41, connection: 9, status: 200, content_type: "application/json" }, f.chunk(42, 9, '{"data":'));
      f.save(); const invalid = await inspectTrial(f.dir, "splice", "sol", profile);
      expect(invalid.captureDiagnostics.model.malformedJson).toBe(1); expect(invalid.qualified).toBe(false);
      f.upstream.splice(-3, 3, f.req(40, 9, "/responses", task()), { kind: "response", at_ms: 41, connection: 9, status: 200, content_type: "text/event-stream" }, f.chunk(42, 9, 'data: {"type":"response.created"}'));
      f.save(); const report = await inspectTrial(f.dir, "splice", "sol", profile);
      expect(report.captureDiagnostics.model.truncatedFrames).toBe(1); expect(report.qualified).toBe(false);
      f.upstream[f.upstream.length - 3]!.path = "/unrecognized";
      f.save(); const unknown = await inspectTrial(f.dir, "splice", "sol", profile);
      expect(unknown.totals.phases.unknown).toBe(1); expect(unknown.captureDiagnostics.model.truncatedFrames).toBe(1);
      expect(unknown.qualified).toBe(false);
    } finally { f.close(); }
  });
  test("missing expected profile or missing actual summary/context cannot vacuously agree", async () => {
    const f = protocolTrial();
    try {
      f.save(); expect((await inspectTrial(f.dir, "splice", "sol")).qualified).toBe(false);
      expect((await inspectTrial(f.dir, "splice", "sol", { effort: "high" })).qualified).toBe(false);
      f.upstream[0]!.text = JSON.stringify({ ...task(), reasoning: { effort: "high" } });
      f.save(); expect((await inspectTrial(f.dir, "splice", "sol", profile)).qualified).toBe(false);
    } finally { f.close(); }
  });
  test("only a captured known parent can prove inherited effective reasoning", async () => {
    const f = protocolTrial();
    try {
      const message = f.upstream.splice(2, 1)[0]!;
      f.upstream.push(f.req(40, 2, "/responses", { model: "sol", input: [], previous_response_id: "root" }), { ...message, connection: 2, at_ms: 45 },
        { ...f.frame(50, { type: "response.completed", response: { id: "child", output: [] } }), connection: 2 });
      f.save(); expect((await inspectTrial(f.dir, "splice", "sol", profile)).qualified).toBe(true);
      f.upstream[3]!.text = JSON.stringify({ ...task(), reasoning: {}, previous_response_id: "uncaptured" });
      f.save(); expect((await inspectTrial(f.dir, "splice", "sol", profile)).qualified).toBe(false);
    } finally { f.close(); }
  });
});

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

describe("r4 completion snapshots with delayed matching deltas", () => {
  for (const completion of ["item", "text"] as const) {
    const snapshot = (text: string): FixtureRow => completion === "item"
      ? { type: "response.output_item.done", output_index: 0, item: { type: "message", id: "m", content: [{ type: "output_text", text }] } }
      : { type: "response.output_text.done", output_index: 0, item_id: "m", content_index: 0, text };
    const delta = (text: string): FixtureRow => ({ type: "response.output_text.delta", output_index: 0, item_id: "m", content_index: 0, delta: text });
    const evidence = (events: FixtureRow[]) => sourceEvidence([...events,
      { type: "response.completed", response: { output: [] } },
    ].map((data, at) => ({ at, data })));

    for (const suffix of [[" world"], [" ", "wor", "ld"]]) {
      test(`${completion} snapshot accepts a prefix followed by ${suffix.length} matching late chunks exactly once`, () => {
        const result = evidence([delta("Hello"), snapshot("Hello world"), ...suffix.map(delta)]);
        expect(result.segments).toEqual([a("Hello world")]);
        expect(result.inconsistencies).toBe(0);
        expect(result.unclosedReadableParts).toBe(0);
      });
    }
    test(`${completion} snapshot accepts the whole matching text arriving late`, () => {
      const result = evidence([snapshot("Hello world"), delta("Hello world")]);
      expect(result.segments).toEqual([a("Hello world")]);
      expect(result.inconsistencies).toBe(0);
    });
    for (const replay of [["Hello"], ["He", "llo"], ["H", "e", "l", "l", "o"]]) {
      test(`${completion} fully observed completion validates ${replay.length} known replay chunks without duplicating output`, () => {
        const result = evidence([delta("Hello"), snapshot("Hello"), ...replay.map(delta)]);
        expect(result.segments).toEqual([a("Hello")]);
        expect(result.inconsistencies).toBe(0);
        expect(result.items[0]!.parts[0]!.snapshotChars).toBe(5);
      });
    }
    test(`${completion} replay reset still rejects contradictory bytes`, () => {
      const result = evidence([delta("Hello"), snapshot("Hello"), delta("He"), delta("wrong")]);
      expect(result.segments).toEqual([a("Hello")]);
      expect(result.inconsistencies).toBeGreaterThan(0);
    });
    test(`${completion} completed replay does not admit a following unknown byte`, () => {
      const result = evidence([delta("Hello"), snapshot("Hello"), delta("He"), delta("llo"), delta("!")]);
      expect(result.segments).toEqual([a("Hello")]);
      expect(result.inconsistencies).toBeGreaterThan(0);
    });
    test(`${completion} snapshot rejects an incompatible observed prefix`, () => {
      const result = evidence([delta("Hallo"), snapshot("Hello world")]);
      expect(result.segments).toEqual([a("Hello world")]);
      expect(result.inconsistencies).toBeGreaterThan(0);
    });
    test(`${completion} snapshot rejects contradictory late bytes even with sparse terminal output`, () => {
      const result = evidence([snapshot("Hello world"), delta("Hello"), delta(" wrong")]);
      expect(result.segments).toEqual([a("Hello world")]);
      expect(result.inconsistencies).toBeGreaterThan(0);
    });
    test(`${completion} snapshot rejects late bytes exceeding its completed text`, () => {
      const result = evidence([delta("Hello world"), snapshot("Hello world"), delta("!")]);
      expect(result.segments).toEqual([a("Hello world")]);
      expect(result.inconsistencies).toBeGreaterThan(0);
    });
    test(`${completion} completed text cannot be replaced by a conflicting completion`, () => {
      const result = evidence([snapshot("Hello world"), snapshot("Hello wrong")]);
      expect(result.inconsistencies).toBeGreaterThan(0);
    });
  }
  test("distinct completed message identities at a reused output index retain both canonical texts", () => {
    const events = [
      { type: "response.output_text.delta", output_index: 0, item_id: "first", delta: "Hello" },
      { type: "response.output_item.done", output_index: 0, item: { type: "message", id: "first", content: [{ type: "output_text", text: "Hello" }] } },
      { type: "response.output_item.added", output_index: 0, item: { type: "message", id: "second", content: [] } },
      { type: "response.output_text.delta", output_index: 0, item_id: "second", delta: "Wor" },
      { type: "response.output_item.done", output_index: 0, item: { type: "message", id: "second", content: [{ type: "output_text", text: "World" }] } },
      { type: "response.output_text.delta", output_index: 0, item_id: "second", delta: "ld" },
      { type: "response.completed", response: { output: [] } },
    ].map((data, at) => ({ at, data }));
    const result = sourceEvidence(events);
    expect(result.segments).toEqual([a("Hello"), a("World")]);
    expect(result.inconsistencies).toBe(0);
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
      const clientReq = (at: number, connection: number) => ({ ...req(at, connection, { model: "claude-codex--sol", messages: [{ role: "user", content: "inspect the fixture" }] }), path: "/v1/messages", transport: "http" });
      writeFileSync(join(dir, "requests/client.jsonl"), jsonl(
        clientReq(0, 1), sse(1, 1, { type: "message_start" }),
        sse(10, 1, { type: "content_block_start", index: 0, content_block: { type: "thinking", thinking: "R" } }), sse(11, 1, { type: "content_block_stop", index: 0 }),
        sse(70, 1, { type: "content_block_start", index: 1, content_block: { type: "tool_use", id: "c", name: "Read" } }), sse(71, 1, { type: "content_block_stop", index: 1 }), sse(72, 1, { type: "message_stop" }),
        clientReq(100, 2), sse(101, 2, { type: "message_start" }),
        sse(110, 2, { type: "content_block_start", index: 0, content_block: { type: "text", text: "done" } }), sse(111, 2, { type: "content_block_stop", index: 0 }), sse(120, 2, { type: "message_stop" }),
      ));
      const report = await inspectTrial(dir, "splice", "sol", profile);
      expect(report.totals.phases.title).toBe(1); expect(report.totals.taskSummaryDenominator).toBe(2);
      expect(report.dispatch[0]!.firstToolAfterItemMs).toBe(50);
      expect(report.sourceToNative.equal).toBe(true); expect(report.sourceToGateway?.equal).toBe(true);
      expect(report.qualified).toBe(true);
      writeFileSync(join(dir, "interactive-session.json"), JSON.stringify({ ...session, exit: 1 }));
      expect((await inspectTrial(dir, "splice", "sol", profile)).qualified).toBe(false);
      writeFileSync(join(dir, "interactive-session.json"), JSON.stringify(session));
      writeFileSync(nativeFile, "different source bytes\n");
      expect((await inspectTrial(dir, "splice", "sol", profile)).qualified).toBe(false);
    } finally { rmSync(dir, { recursive: true, force: true }); }
  });
});
