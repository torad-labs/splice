// Sample log tail for design captures (CONTRACTS.md section 4, the fixture rule): loaded only in
// DEV behind ?fixture=tail, imported by name at runtime so the dist carries no sample bytes, and
// labeled with a grey "sample data" holder edge whenever it is in use.
//
// The lines are copied in SHAPE from the daemon's own log (the format the entity's parsers were
// written against: `[YYYY-MM-DD HH:MM:SS] [tag] message`), and they include the two states the
// page has to survive: a tagged subsystem line, and a line the daemon marked ERROR.
import type { LogsPayload } from '@entities/logs';

const lines = [
  '[2026-09-18 01:13:59] [claude-deepseek] perf outcome=ok compact=false model=deepseek-flash session=c8692118 recv=1 parse=6 build=7 gate=0 headers=3256 first_byte=3267 first_frame=3256 first_delta=4036 stream_end=4284 finish=4285 total=4285 | inflight=8 async_io_drops=0 req_bytes=1770596 upstream_req_bytes=1762420 attempts=1 frames_out=69 bytes_out=9326 sse_bytes_in=9258 events_in=69 content_frames_out=67 in_tokens=453608 out_tokens=97 cached_tokens=453376 cache_write_tokens=0',
  '[2026-09-18 01:13:59] [shadow-compact] compact=false has_marker=false tool_count=177 sys_len=3759',
  '[2026-09-18 01:14:01] [claude-deepseek] turn compact=false model=deepseek-flash latency=3052ms ok out=252 tool=true incomplete=false',
  '[2026-09-18 01:14:01] [claude-deepseek] cache: input=182346 cached=181248 hit=99% output=252 model=deepseek-flash',
  '[2026-09-18 01:14:01] [claude-deepseek] perf outcome=ok compact=false model=deepseek-flash session=3f67533e recv=0 parse=2 build=2 gate=0 headers=1215 first_byte=1235 first_frame=1215 first_delta=1918 stream_end=3052 finish=3052 total=3052 | inflight=8 async_io_drops=0 req_bytes=731248 upstream_req_bytes=728714 attempts=1',
  '[2026-09-18 01:14:02] [claude-deepseek] turn compact=false model=deepseek-flash latency=6099ms ok out=712 tool=true incomplete=false',
  '[2026-09-18 01:14:03] [claude-deepseek] perf outcome=ok compact=false model=deepseek-flash session=e06d9af8 recv=2 parse=7 build=7 gate=0 headers=3504 first_byte=3513 first_frame=3504 first_delta=4136 stream_end=8073 finish=8073 total=8073 | inflight=8 async_io_drops=0 req_bytes=1572805 upstream_req_bytes=1562903 attempts=1',
  '[2026-09-18 01:14:04] [mcp-host] server filesystem hosted pid=31110 sessions=1 streams=1',
  '[2026-09-18 01:14:05] [claude-deepseek] turn ERROR conn-reset compact=false latency=2827ms : chunked transfer encoding, state: READING_LENGTH',
  '[2026-09-18 01:14:05] [claude-deepseek] turn ERROR finish-degraded compact=false latency=9194ms tag=empty_message client received an error terminal',
  '[2026-09-18 01:14:06] [claudex] perf outcome=ok compact=false model=gpt-5.2-codex session=7bce4f3c recv=2 parse=6 build=6 gate=40 headers=2400 first_byte=2420 first_frame=2440 first_delta=5100 stream_end=9910 finish=9925 total=9925 | inflight=9 async_io_drops=0 attempts=1',
  '[2026-09-18 01:14:07] [claudex] turn compact=false model=gpt-5.2-codex latency=1840ms ok out=318 tool=true incomplete=false',
  '[2026-09-18 01:14:08] [claude-grok] turn ERROR rate-limited compact=false latency=240320ms : 429 from upstream, retry after 60s',
  '[2026-09-18 01:14:09] [claude-grok] retry attempt=2 backoff=64000ms reason=rate-limited',
  '[2026-09-18 01:14:11] [claudex] perf outcome=ok compact=false model=gpt-5.2-codex session=e06d9af8 recv=1 parse=3 build=4 gate=12 headers=1840 first_byte=1848 first_frame=1856 first_delta=2200 stream_end=9910 finish=9925 total=9925 | inflight=6 async_io_drops=0 attempts=1',
];

export const fixture = {
  payload: {
    key: 'claude-deepseek',
    path: '/home/user/.claude-codex/logs/daemon.log',
    lines,
  } satisfies LogsPayload,
};
