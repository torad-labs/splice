// rv-transcript: GET /api/sessions/{id}/transcript tells the reader what it needs to settle a live reply and to draw a
// reply whole: every message a reply made carries that reply's id (`message`, the id the feed's `turn` event names) and
// the model that wrote it (`model`); the first message a reply made carries its thinking (`thinking`), signatures never;
// and a compaction's summary is marked (`compact: true`). Every field the route sent before is sent as before. A Splice
// built from this checkout reads a made-up Claude Code transcript. Runs on Linux (the harness reads /proc).
import { afterAll, beforeAll, expect, test } from "bun:test";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { freePort, mockHead, Splice } from "../server/lib/harness.ts";

const ID = "5b7c0f3e-1d2a-4c8e-9f00-aaaa11112222";
const at = (s: number) => new Date(Date.UTC(2026, 10, 19, 15, 0, s)).toISOString();
const base = { sessionId: ID, cwd: "/work/harbor", version: "2.1.0", userType: "external", isSidechain: false };
const lines = [
  { ...base, type: "user", uuid: "u1", parentUuid: null, timestamp: at(0), message: { role: "user", content: "Fix the tide table parser." } },
  { ...base, type: "assistant", uuid: "a1", parentUuid: "u1", timestamp: at(1),
    message: { id: "msg_rv01", type: "message", role: "assistant", model: "claude-opus-5-5", content: [{ type: "thinking", thinking: "The parser skips the new column.", signature: "made-up-signature-zzzz" }] } },
  { ...base, type: "assistant", uuid: "a2", parentUuid: "a1", timestamp: at(2),
    message: { id: "msg_rv01", type: "message", role: "assistant", model: "claude-opus-5-5", content: [{ type: "text", text: "I will read the parser first." }] } },
  { ...base, type: "assistant", uuid: "a3", parentUuid: "a2", timestamp: at(3),
    message: { id: "msg_rv01", type: "message", role: "assistant", model: "claude-opus-5-5", content: [{ type: "tool_use", id: "toolu_rv01", name: "Read", input: { file_path: "/work/harbor/tide.ts" } }] } },
  { ...base, type: "user", uuid: "u2", parentUuid: "a3", timestamp: at(4),
    message: { role: "user", content: [{ type: "tool_result", tool_use_id: "toolu_rv01", content: "export function parse() {}" }] } },
  { ...base, type: "assistant", uuid: "a4", parentUuid: "u2", timestamp: at(5),
    message: { id: "msg_rv02", type: "message", role: "assistant", model: "claude-opus-5-5", content: [{ type: "text", text: "The parser reads the new column now." }] } },
  { ...base, type: "user", uuid: "u3", parentUuid: "a4", timestamp: at(6), isCompactSummary: true,
    message: { role: "user", content: "This session is being continued from a previous conversation. Summary: the parser was fixed." } },
  { ...base, type: "user", uuid: "u4", parentUuid: "u3", timestamp: at(7), message: { role: "user", content: "Thanks, now the map." } },
];

let s: Splice;
beforeAll(async () => {
  s = await Splice.create(mockHead({ key: "acme", command: "claude-acme", port: await freePort(), url: "http://127.0.0.1:9", model: "acme-large-2" }));
  const dir = join(s.home, ".claude", "projects", "-work-harbor");
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, `${ID}.jsonl`), `${lines.map((l) => JSON.stringify(l)).join("\n")}\n`);
  await s.start();
}, 1_800_000);
afterAll(async () => {
  await s?.dispose();
}, 60_000);

interface Wire {
  index: number;
  role: string;
  text: string;
  ts?: number;
  tool?: string;
  tool_use_id?: string;
  kind?: string;
  message?: string;
  model?: string;
  thinking?: string;
  compact?: boolean;
}

test("a reply's messages carry its id and model, its first message its thinking, and a compaction is marked", async () => {
  for (const query of ["before=end", "limit=100"]) {
    const res = await s.api(`/api/sessions/${ID}/transcript?${query}`);
    expect(res.status, query).toBe(200);
    const raw = await res.text();
    expect(raw).not.toContain("made-up-signature");
    const messages = (JSON.parse(raw) as { messages: Wire[] }).messages;
    const said = messages.find((m) => m.text === "I will read the parser first.")!;
    expect(said, query).toBeDefined();
    expect(said.role).toBe("assistant");
    expect(said.message).toBe("msg_rv01");
    expect(said.model).toBe("claude-opus-5-5");
    expect(said.thinking).toBe("The parser skips the new column.");
    const read = messages.find((m) => m.tool === "Read" && m.role === "assistant")!;
    expect(read.message).toBe("msg_rv01");
    expect(read.tool_use_id).toBe("toolu_rv01");
    expect(read.thinking).toBeUndefined();
    expect(JSON.parse(read.text)).toEqual({ file_path: "/work/harbor/tide.ts" });
    const result = messages.find((m) => m.role === "tool")!;
    expect(result.tool_use_id).toBe("toolu_rv01");
    expect(result.text).toBe("export function parse() {}");
    expect(result.message).toBeUndefined();
    const next = messages.find((m) => m.text === "The parser reads the new column now.")!;
    expect(next.message).toBe("msg_rv02");
    expect(next.thinking).toBeUndefined();
    const summary = messages.find((m) => m.text.includes("Summary: the parser was fixed."))!;
    expect(summary.role).toBe("system");
    expect(summary.compact).toBe(true);
    const person = messages.filter((m) => m.role === "user");
    expect(person.map((m) => m.text)).toEqual(["Fix the tide table parser.", "Thanks, now the map."]);
    for (const m of person) expect(m.compact === undefined && m.message === undefined && m.thinking === undefined).toBe(true);
    // the text messages the person reads are what they were: the thinking is not in any message's text
    expect(messages.some((m) => m.text.includes("skips the new column"))).toBe(false);
    for (const m of messages) expect(typeof m.index).toBe("number");
  }
});
