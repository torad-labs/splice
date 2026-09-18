// Sample turns for design captures (CONTRACTS.md section 4, the fixture rule): loaded only in DEV
// behind ?fixture=board, imported by name at runtime so the dist carries no sample bytes, and
// labeled with a grey "sample data" holder edge whenever it is in use.
//
// The rows are shaped like the daemon's own perf rows (PerfStats.record), including the two states
// a healthy machine rarely shows together: a turn that lost telemetry on the way to disk, and one
// that died before its first mark, which is what makes the waterfall's honest gaps visible.
import type { InflightTurn, PerfSummaryPayload, TurnRow } from '@entities/perf';

const SEC = 1000;
const now = Date.now();

function at(minutesAgo: number): number {
  return now - minutesAgo * 60 * SEC;
}

const landed: TurnRow[] = [
  {
    head: 'claude-deepseek', ts: at(1), model: 'deepseek-flash', outcome: 'ok', compact: false,
    session: '3f67533e', recv: 1, parse: 4, build: 5, gate: 0, headers: 2980, first_byte: 2990,
    first_frame: 2992, first_delta: 4100, stream_end: 12800, finish: 12820, total: 12820,
    attempts: 1, retries: 0, refreshes: 0, backoff_ms: 0, req_bytes: 1770596, upstream_req_bytes: 1762420,
    frames_out: 269, in_tokens: 453608, cached_tokens: 453376, cache_write_tokens: 0, out_tokens: 97,
    inflight: 8, async_io_drops: 0, tools_eager: 4, tools_deferred: 173, search_rounds: 0,
  },
  {
    head: 'claude-deepseek', ts: at(3), model: 'deepseek-flash', outcome: 'ok', compact: true,
    session: 'c8692118', recv: 2, parse: 7, build: 7, gate: 120, headers: 2365, first_byte: 2373,
    first_frame: 2400, first_delta: 2609, stream_end: 6099, finish: 6110, total: 6110,
    attempts: 1, retries: 0, req_bytes: 1612748, upstream_req_bytes: 1610033, frames_out: 684,
    in_tokens: 183789, cached_tokens: 182528, out_tokens: 712, inflight: 7, async_io_drops: 3,
    tools_eager: 4, tools_deferred: 173,
  },
  {
    head: 'claudex', ts: at(7), model: 'gpt-5.2-codex', outcome: 'conn-reset', compact: false,
    session: '7bce4f3c', recv: 2, parse: 6, build: 6, gate: 40, headers: 2400, first_byte: 2420,
    first_frame: 2440, first_delta: 5100,
    attempts: 2, retries: 1, backoff_ms: 800, post_send_retries: 1, req_bytes: 2977199,
    upstream_req_bytes: 2957303, in_tokens: 742774, cached_tokens: 742528, out_tokens: 12,
    inflight: 9, async_io_drops: 0,
  },
  {
    head: 'claudex', ts: at(11), model: 'gpt-5.2-codex', outcome: 'ok', compact: false,
    session: 'e06d9af8', recv: 1, parse: 3, build: 4, gate: 12, headers: 1840, first_byte: 1848,
    first_frame: 1856, first_delta: 2200, stream_end: 9910, finish: 9925, total: 9925,
    attempts: 1, retries: 0, req_bytes: 1572805, upstream_req_bytes: 1562903, frames_out: 899,
    in_tokens: 394638, cached_tokens: 394112, out_tokens: 924, inflight: 6, async_io_drops: 0,
  },
  {
    head: 'claude-grok', ts: at(14), model: 'grok-4.6', outcome: 'ok', compact: false,
    recv: 1, parse: 2, build: 2, gate: 0, headers: 610, first_byte: 618, first_frame: 620,
    first_delta: 900, stream_end: 4300, finish: 4310, total: 4310,
    attempts: 1, retries: 0, in_tokens: 121004, cached_tokens: 0, cache_write_tokens: 94000,
    out_tokens: 511, inflight: 3, async_io_drops: 0, search_rounds: 2,
  },
  {
    head: 'claude-grok', ts: at(19), model: 'grok-4.6', outcome: 'rate-limited', compact: false,
    session: 'cbde92be', recv: 1, parse: 2, build: 3, gate: 240000, headers: 240320,
    attempts: 3, retries: 2, backoff_ms: 64000, refreshes: 1, in_tokens: 88000, out_tokens: 0,
    inflight: 2, async_io_drops: 0,
  },
];

const inflight: InflightTurn[] = [
  { head: 'claude-deepseek', label: 'design-builder4', compact: false, phase: 'streaming', ageMs: 12 * SEC, idleMs: 400, streamIdleMs: 300000 },
  { head: 'claude-deepseek', label: 'design-builder3', compact: false, phase: 'streaming', ageMs: 8 * SEC, idleMs: 1200, streamIdleMs: 300000 },
  { head: 'claudex', label: 'relay-orchestrator', compact: true, phase: 'connect', ageMs: 96 * SEC, idleMs: 331000, streamIdleMs: 300000 },
  { head: 'claude-grok', label: 'gs-scout-claude', compact: false, phase: 'streaming', ageMs: 31 * SEC, idleMs: 2200, streamIdleMs: 300000 },
];

const summary: PerfSummaryPayload = {
  window: '24h',
  heads: [
    {
      key: 'claude-deepseek', label: 'claude-deepseek', window: '24h', count: 1841, empty: false,
      coverage_known: true, clamped: false, covers_ms: 24 * 3600 * SEC,
      time_before_first_byte_ms: { count: 1841, p50: 2380, p95: 6420, max: 41000 },
      time_streaming_ms: { count: 1802, p50: 7100, p95: 24800, max: 240000 },
      total_ms: { count: 1841, p50: 9800, p95: 31200, max: 900031 },
      outcomes: { ok: 1798, 'conn-reset': 30, cancelled: 13 }, failure_share: 0.023,
      failure_shares: { 'conn-reset': 0.016, cancelled: 0.007 }, unattributed: 0,
      retries: 41, refreshes: 3, cache_hit_ratio: 0.982, peak_inflight: 9, io_drops_in_window: 12,
    },
    {
      key: 'claudex', label: 'claudex', window: '24h', count: 604, empty: false,
      coverage_known: true, clamped: false, covers_ms: 22 * 3600 * SEC,
      time_before_first_byte_ms: { count: 604, p50: 1840, p95: 5100, max: 22100 },
      total_ms: { count: 604, p50: 7100, p95: 22400, max: 121000 },
      outcomes: { ok: 590, 'finish-degraded': 14 }, failure_share: 0.023,
      failure_shares: { 'finish-degraded': 0.023 }, unattributed: 0,
      retries: 9, refreshes: 1, cache_hit_ratio: 0.971, peak_inflight: 9, io_drops_in_window: 0,
    },
    {
      key: 'claude-grok', label: 'claude-grok', window: '24h', count: 0, empty: true,
      coverage_known: true, clamped: true, covers_ms: 3 * 3600 * SEC,
      note: 'the perf files hold 3h of rows, less than the window',
    },
  ],
};

export const fixture = { inflight, landed, summary };
