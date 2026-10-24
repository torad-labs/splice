import { createHash } from "node:crypto";

export type Row = Record<string, any>;
export type Segment = { kind: "reasoning" | "prose" | "tool"; text: string };
export type TimedEvent = { at: number; data: Row };
export const digest = (value: string | Uint8Array): string => createHash("sha256").update(value).digest("hex");

/** No trimming: LF+LF is the only declared transformation, and only between adjacent reasoning parts. */
export function compareSegments(source: Segment[], observed: Segment[]) {
  const canonical = (segments: Segment[]) => {
    const result: Segment[] = [];
    for (const segment of segments) {
      const last = result.at(-1);
      if (segment.kind === "tool") {
        if (last?.kind !== "tool") result.push({ kind: "tool", text: "" });
      } else if (last?.kind === segment.kind) {
        last.text += (segment.kind === "reasoning" ? "\n\n" : "") + segment.text;
      } else result.push({ ...segment });
    }
    return result;
  };
  const expected = canonical(source), actual = canonical(observed);
  const sanitized = (segments: Segment[]) => segments.map(s => ({ kind: s.kind, chars: s.text.length, sha256: digest(s.text) }));
  return { equal: JSON.stringify(expected) === JSON.stringify(actual), expected: sanitized(expected), actual: sanitized(actual), transformation: "LF+LF between adjacent reasoning parts only; adjacent tool callbacks are one boundary" };
}

export class SseReader {
  private decoder = new TextDecoder("utf-8", { fatal: true });
  private pending = "";
  malformed = 0;
  truncated = 0;
  invalidUtf8 = 0;
  push(bytes: Uint8Array, at: number): TimedEvent[] {
    try { this.pending += this.decoder.decode(bytes, { stream: true }); }
    catch { this.invalidUtf8++; this.pending = ""; this.decoder = new TextDecoder("utf-8", { fatal: true }); return []; }
    const events: TimedEvent[] = [];
    let boundary: RegExpExecArray | null;
    while ((boundary = /\r?\n\r?\n/.exec(this.pending))) {
      const frame = this.pending.slice(0, boundary.index);
      this.pending = this.pending.slice(boundary.index + boundary[0].length);
      const data = frame.split(/\r?\n/).filter(l => l.startsWith("data:")).map(l => l.slice(5).replace(/^ /, "")).join("\n");
      if (!data || data === "[DONE]") continue;
      try { const parsed = JSON.parse(data); if (!parsed || typeof parsed !== "object") this.malformed++; else events.push({ at, data: parsed }); }
      catch { this.malformed++; }
    }
    return events;
  }
  finish() {
    try { this.pending += this.decoder.decode(); } catch { this.invalidUtf8++; }
    if (this.pending.trim()) this.truncated++;
    this.pending = "";
    return { malformed: this.malformed, truncated: this.truncated, invalidUtf8: this.invalidUtf8 };
  }
}
export function decodeSse(chunks: { bytes: Uint8Array; at: number }[]) {
  const reader = new SseReader();
  const events = chunks.flatMap(c => reader.push(c.bytes, c.at));
  return { events, ...reader.finish() };
}

export function auditNative(text: string, kind: "claude" | "codex", expectedSessionId?: string) {
  const rows = new Map<string, Row>();
  const sessions = new Set<string>();
  let latestClaudeStop: string | null = null;
  let malformedRows = 0, truncatedRows = 0, fabricatedRows = 0, revisions = 0, duplicateSnapshots = 0, ambiguousRows = 0;
  const lines = text.split("\n");
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]!; if (!line.trim()) continue;
    let row: Row;
    try { row = JSON.parse(line); if (!row || typeof row !== "object" || Array.isArray(row)) throw Error("row"); }
    catch { if (i === lines.length - 1 && !text.endsWith("\n")) truncatedRows++; else malformedRows++; continue; }
    if (["stream_event", "init", "result"].includes(row.type) || (row.type === "system" && row.subtype === "init")) { fabricatedRows++; continue; }
    if (row.isSidechain === true || row.parent_tool_use_id) { ambiguousRows++; continue; }
    if (kind === "claude" && row.type === "user" && row.message?.role === "user") latestClaudeStop = null;
    if (kind === "claude" && row.type === "assistant" && row.message?.role === "assistant") latestClaudeStop = typeof row.message.stop_reason === "string" ? row.message.stop_reason : null;
    const id = kind === "claude" ? row.sessionId ?? row.session_id : row.type === "session_meta" ? row.payload?.id : undefined;
    if (typeof id === "string") sessions.add(id);
    let key: string;
    if (kind === "claude") {
      if (!["user", "assistant"].includes(row.type)) { rows.set(`other:${i}`, row); continue; }
      const identity = row.uuid ?? row.message?.id;
      if (!identity) ambiguousRows++;
      key = identity ? `${row.type}:${identity}` : `unidentified:${i}`;
    } else key = row.type === "response_item" && row.payload?.id ? `item:${row.payload.id}` : `row:${i}`;
    if (rows.has(key)) { revisions++; if (JSON.stringify(rows.get(key)) === JSON.stringify(row)) duplicateSnapshots++; }
    // Stable record identity, never text, defines replacement; Map preserves the original record position.
    rows.set(key, row);
  }
  const segments: Segment[] = [], userTexts: string[] = [];
  const blocks: { recordIdSha256: string; index: number; type: string; chars: number | null; sha256: string | null }[] = [];
  let recordIdSha256 = "", blockIndex = 0;
  let thinkingBlocks = 0, thinkingCharacters = 0, proseBlocks = 0, proseCharacters = 0, opaqueBlocks = 0, emptyThinkingBlocks = 0, toolCalls = 0, toolResults = 0, hiddenReasoningItems = 0;
  let finalStop: string | null = latestClaudeStop;
  const block = (b: Row) => {
    const readable = b.type === "thinking" ? b.thinking : ["text", "output_text", "summary_text"].includes(b.type) ? b.text : null;
    blocks.push({ recordIdSha256, index: blockIndex++, type: typeof b.type === "string" ? b.type : "unknown",
      chars: typeof readable === "string" ? readable.length : null, sha256: typeof readable === "string" ? digest(readable) : null });
    if (["thinking", "summary_text"].includes(b.type)) {
      const value = b.thinking ?? b.text;
      if (typeof value !== "string") { ambiguousRows++; return; }
      thinkingBlocks++; thinkingCharacters += value.length; if (!value.length) emptyThinkingBlocks++;
      segments.push({ kind: "reasoning", text: value });
    } else if (b.type === "redacted_thinking") opaqueBlocks++;
    else if (["text", "output_text"].includes(b.type)) {
      if (typeof b.text !== "string") { ambiguousRows++; return; }
      proseBlocks++; proseCharacters += b.text.length; segments.push({ kind: "prose", text: b.text });
    } else if (["tool_use", "function_call", "custom_tool_call"].includes(b.type)) { toolCalls++; segments.push({ kind: "tool", text: "" }); }
    else if (["tool_result", "function_call_output", "custom_tool_call_output"].includes(b.type)) toolResults++;
  };
  for (const [identity, row] of rows) {
    recordIdSha256 = digest(identity); blockIndex = 0;
    if (kind === "claude") {
      const message = row.message;
      if (row.type === "assistant" && message?.role === "assistant") {
        if (!Array.isArray(message.content)) { ambiguousRows++; continue; }
        for (const b of message.content) block(b);
      } else if (row.type === "user" && message?.role === "user") {
        if (typeof message.content === "string") userTexts.push(message.content);
        else if (Array.isArray(message.content)) {
          const user = message.content.filter((b: Row) => b.type === "text").map((b: Row) => b.text).join("");
          if (user) userTexts.push(user);
          for (const b of message.content) if (b.type === "tool_result") toolResults++;
        }
      }
    } else if (row.type === "response_item") {
      const item = row.payload;
      if (item?.type === "reasoning") {
        for (const part of item.summary ?? []) block(part);
        if (item.encrypted_content) hiddenReasoningItems++;
        // Raw reasoning content is not the summary-text protocol audited here; retain an unknown, not an empty pass.
        if (item.content?.length) ambiguousRows++;
      } else if (item?.type === "message" && item.role === "assistant") for (const b of item.content ?? []) block(b);
      else if (item?.type === "message" && item.role === "user") {
        finalStop = null;
        const user = (item.content ?? []).filter((b: Row) => ["input_text", "text"].includes(b.type)).map((b: Row) => b.text).join("");
        if (user) userTexts.push(user);
      } else if (item) block(item);
    } else if (row.type === "event_msg") {
      if (["task_complete", "turn_aborted", "error", "task_started"].includes(row.payload?.type)) finalStop = row.payload.type;
    }
  }
  const sessionMismatch = sessions.size !== 1 || (!!expectedSessionId && !sessions.has(expectedSessionId));
  const completed = ["end_turn", "task_complete"].includes(finalStop ?? "") && !sessionMismatch && !(malformedRows + truncatedRows + fabricatedRows + ambiguousRows);
  return { kind, sourceSha256: digest(text), sessionIds: [...sessions], sessionMismatch, records: rows.size, revisions, duplicateSnapshots,
    malformedRows, truncatedRows, fabricatedRows, ambiguousRows, thinkingBlocks, thinkingCharacters, proseBlocks, proseCharacters,
    opaqueBlocks, hiddenReasoningItems, emptyThinkingBlocks, toolCalls, toolResults, finalStop, completed, segments, userTexts, blocks };
}

/** Snapshot reconciliation keeps live item positions; sparse terminal arrays never reorder already seen items. */
export function sourceEvidence(events: TimedEvent[]) {
  const entries = new Map<string, { index: number; kind: Segment["kind"]; parts: Map<number, { delta: string; done?: string }> }>();
  const ids = new Map<number, string>(); let inconsistencies = 0;
  const entry = (e: Row, item?: Row) => {
    const index = Number.isFinite(e.output_index) ? e.output_index : entries.size;
    const id = item?.id ?? e.item_id ?? ids.get(index) ?? `index:${index}`;
    if (Number.isFinite(e.output_index)) ids.set(index, id);
    if (!entries.has(id)) entries.set(id, { index, kind: item?.type === "message" ? "prose" : ["custom_tool_call", "function_call"].includes(item?.type) ? "tool" : "reasoning", parts: new Map() });
    return entries.get(id)!;
  };
  const snapshot = (item: Row, e: Row) => {
    const target = entry(e, item);
    const parts = item.type === "reasoning" ? item.summary ?? [] : item.type === "message" ? item.content ?? [] : [];
    parts.forEach((p: Row, i: number) => {
      if (typeof p.text !== "string") return;
      const part = target.parts.get(i);
      if (part?.done !== undefined && part.done !== p.text) inconsistencies++;
      if (part?.delta && part.delta !== p.text) inconsistencies++;
      target.parts.set(i, { delta: part?.delta ?? "", done: p.text });
    });
  };
  for (const { data: e } of events) {
    if (["response.output_item.added", "response.output_item.done"].includes(e.type) && e.item) snapshot(e.item, e);
    else if (["response.reasoning_summary_text.delta", "response.reasoning_summary_text.done", "response.output_text.delta", "response.output_text.done"].includes(e.type)) {
      const target = entry(e); if (e.type.startsWith("response.output_text")) target.kind = "prose";
      const index = e.summary_index ?? e.content_index ?? 0;
      const part = target.parts.get(index) ?? { delta: "" };
      if (e.type.endsWith(".delta")) part.delta += e.delta ?? "";
      else { if (part.delta && part.delta !== e.text) inconsistencies++; part.done = e.text; }
      target.parts.set(index, part);
    } else if (["response.completed", "response.failed", "response.incomplete"].includes(e.type)) {
      (e.response?.output ?? []).forEach((item: Row, index: number) => {
        const existing = item.id ? entries.get(item.id) : undefined;
        snapshot(item, { output_index: existing?.index ?? index });
      });
    }
  }
  const segments: Segment[] = [];
  for (const item of [...entries.values()].sort((a, b) => a.index - b.index)) {
    if (item.kind === "tool") segments.push({ kind: "tool", text: "" });
    else for (const [, p] of [...item.parts].sort(([a], [b]) => a - b)) segments.push({ kind: item.kind, text: p.done ?? p.delta });
  }
  const raw = events.filter(e => typeof e.data.type === "string" && e.data.type.startsWith("response.reasoning_text."));
  return { segments, inconsistencies, readableParts: segments.filter(s => s.kind === "reasoning").length,
    items: [...entries].map(([id, item]) => ({ itemIdSha256: digest(id), outputIndex: item.index, kind: item.kind,
      parts: [...item.parts].map(([index, part]) => ({ index, deltaChars: part.delta.length, deltaSha256: digest(part.delta),
        snapshotChars: part.done?.length ?? null, snapshotSha256: part.done === undefined ? null : digest(part.done) })) })),
    unclosedReadableParts: [...entries.values()].reduce((n, item) => n + [...item.parts.values()].filter(p => p.done === undefined && p.delta.length > 0).length, 0),
    unmodeledReadableEvents: raw.length,
    rawReasoningCharacters: raw.filter(e => e.data.type.endsWith(".delta")).reduce((n, e) => n + (e.data.delta?.length ?? 0), 0),
  };
}

export function gatewayEvidence(events: TimedEvent[]) {
  const segments: Segment[] = [], tools: { at: number; id: string; name: string }[] = [];
  let active = new Map<number, { type: string; text: string; segment?: Segment }>();
  let seen = new Set<number>();
  let opaqueBlocks = 0, emptyThinkingBlocks = 0, duplicateBlocks = 0, malformed = 0, messages = 0, completed = 0;
  for (const { data: e, at } of events) {
    if (e.type === "message_start") { if (active.size) malformed++; active = new Map(); seen = new Set(); messages++; }
    else if (e.type === "content_block_start") {
      if (seen.has(e.index)) { duplicateBlocks++; continue; }
      seen.add(e.index);
      const b = e.content_block ?? {};
      if (b.type === "redacted_thinking") opaqueBlocks++;
      const kind = b.type === "thinking" ? "reasoning" : b.type === "text" ? "prose" : b.type === "tool_use" ? "tool" : undefined;
      const segment = kind ? { kind, text: b.thinking ?? b.text ?? "" } as Segment : undefined;
      if (segment) segments.push(segment);
      active.set(e.index, { type: b.type, text: segment?.text ?? "", segment });
      if (b.type === "tool_use") tools.push({ at, id: b.id, name: b.name });
    } else if (e.type === "content_block_delta") {
      const b = active.get(e.index);
      if (!b) { malformed++; continue; }
      const value = e.delta?.type === "thinking_delta" ? e.delta.thinking : e.delta?.type === "text_delta" ? e.delta.text : "";
      if (typeof value !== "string") { malformed++; continue; }
      b.text += value; if (b.segment) b.segment.text = b.text;
    } else if (e.type === "content_block_stop") {
      const b = active.get(e.index); if (!b) { malformed++; continue; }
      if (b.type === "thinking" && !b.text) emptyThinkingBlocks++;
      active.delete(e.index);
    } else if (e.type === "message_stop") { completed++; if (active.size) malformed++; }
  }
  return { segments, tools, opaqueBlocks, emptyThinkingBlocks, duplicateBlocks, malformed, messages, completed, openBlocks: active.size };
}
