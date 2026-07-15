// Compaction detection + the shadow classifier + compact stats.
//
// v29's isCompactRequest was INVERTED against reality (Eli P0/P1 + the
// v2.1.207 binary trace): real compaction requests DO carry tools
// (Read+ToolSearch+MCP by default), and v29's first line rejected any body
// with tools — so the real shape could never match, and a compaction that ran
// with tools could answer with tool_use, gating the promote-to-text net off.
//
// classifyCompact is now a POSITIVE-marker classifier and tools-agnostic: the
// authoritative signal is Claude Code's verbatim summarizer system prompt
// (identical for auto + manual compact; streaming; no distinguishing header).
// On detect, the request builder strips tools upstream — forcing a text answer.
//
// The shadow classifier logs {has_marker, tool_count, sys_len} for EVERY
// request — the predicted-vs-observed instrument that gives ground truth on
// marker drift across Claude Code updates.
import { existsSync, mkdirSync, readFileSync } from 'node:fs';
import { appendFile as appendFileAsync } from 'node:fs/promises';
import { dirname } from 'node:path';
import { statePaths } from '../config.mjs';

/** Primary summarizer marker (kept for the canary test + shadow key). */
export const COMPACT_MARKER = 'tasked with summarizing conversations';

/** Every verbatim summarizer INSTRUCTION Claude Code 2.1.207 emits (binary-
 * traced: auto + manual + partial/microcompact). The instruction moved between
 * the system prompt and an appended user message across builds, so detection
 * checks BOTH fields. Each is an instruction ("your task is to create a detailed
 * summary…"), never the post-compaction resume ("this session is being
 * continued…") — so a resumed turn never false-matches (the v13/v24 misfire
 * class stays dead). On drift: add the new verbatim sentence here + a fixture. */
export const COMPACT_MARKERS = [
  'tasked with summarizing conversations',
  'your task is to create a detailed summary of this conversation',
  'your task is to create a detailed summary of the conversation',
  'your task is to create a detailed summary of the recent portion',
  'summarize this portion of a claude code session transcript',
];

export function systemText(body) {
  if (typeof body?.system === 'string') return body.system;
  if (Array.isArray(body?.system)) {
    return body.system.filter((b) => b?.type === 'text' && b.text).map((b) => b.text).join('\n');
  }
  return '';
}

export function lastUserTextOf(body) {
  const messages = body?.messages ?? [];
  for (let i = messages.length - 1; i >= 0; i--) {
    const msg = messages[i];
    if (msg?.role !== 'user') continue;
    if (typeof msg.content === 'string') return msg.content;
    if (Array.isArray(msg.content)) {
      const t = msg.content
        .filter((b) => b?.type === 'text' && b.text)
        .map((b) => b.text)
        .join('\n');
      if (t) return t;
    }
  }
  return '';
}

/** True when a verbatim summarizer instruction is present in the system prompt
 * OR the last user message (the two places the trigger instruction lives). Only
 * the last user message is scanned — never the whole transcript — so a summary
 * quoted in history can never re-trigger detection on later turns. */
export function markerPresent(body) {
  const hay = `${systemText(body)}\n${lastUserTextOf(body)}`.toLowerCase();
  return COMPACT_MARKERS.some((m) => hay.includes(m));
}

/**
 * Detect Claude Code's /compact summarization call (auto + manual) by positive
 * marker only. Tools-agnostic. Size heuristics stay dead (the v13/v24 bug
 * class: post-compact "This session is being continued…" resumes and long
 * toolless utility calls must never match).
 */
export function classifyCompact(body) {
  if (markerPresent(body)) return true;
  // Explicit compaction affordances some builds put user-side (positive
  // statements about compaction itself, not content heuristics).
  const lastUserText = lastUserTextOf(body);
  return /compaction agent should only produce text/i.test(lastUserText)
    || /tool use is not allowed during compaction/i.test(lastUserText);
}

// ── Shadow classifier (in-memory ring + stderr line) ─────────────────────────
const SHADOW_RING_MAX = 500;
const shadowRing = [];

export function recordShadow(body, compact) {
  const row = {
    ts: Date.now(),
    compact,
    has_marker: markerPresent(body),
    tool_count: Array.isArray(body?.tools) ? body.tools.length : 0,
    sys_len: systemText(body).length,
    model: String(body?.model ?? ''),
  };
  shadowRing.push(row);
  if (shadowRing.length > SHADOW_RING_MAX) shadowRing.shift();
  process.stderr.write(
    `[shadow-compact] compact=${row.compact} has_marker=${row.has_marker} tool_count=${row.tool_count} sys_len=${row.sys_len}\n`,
  );
  return row;
}

export function shadowTail(n = 100) {
  return shadowRing.slice(-n);
}

// ── Compact outcome stats (contract file — HUD + compact-stats reader) ───────
export function recordCompactStat(row) {
  queueMicrotask(async () => {
    try {
      const path = statePaths.compactStats();
      mkdirSync(dirname(path), { recursive: true });
      await appendFileAsync(path, `${JSON.stringify({ ts: Date.now(), ...row })}\n`);
    } catch { /* ignore */ }
  });
}

export function readCompactStats(tailN = 50) {
  const path = statePaths.compactStats();
  if (!existsSync(path)) return { total: 0, by_outcome: {}, tail: [] };
  let rows = [];
  try {
    rows = readFileSync(path, 'utf8').trim().split('\n').filter(Boolean).map((l) => {
      try { return JSON.parse(l); } catch { return null; }
    }).filter(Boolean);
  } catch { rows = []; }
  const byOutcome = {};
  for (const r of rows) {
    const k = r.outcome || 'unknown';
    byOutcome[k] = (byOutcome[k] || 0) + 1;
  }
  return { total: rows.length, by_outcome: byOutcome, tail: rows.slice(-tailN) };
}
