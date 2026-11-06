import type { Manifest } from './manifest';
import type { Sample } from './observe';
export interface Interval { mean: number | null; low: number | null; high: number | null; n: number; cv: number | null }
export function interval(xs: number[]): Interval {
  if (!xs.length) return { mean: null, low: null, high: null, n: 0, cv: null };
  const mean = xs.reduce((a, b) => a + b, 0) / xs.length;
  if (xs.length === 1) return { mean, low: null, high: null, n: 1, cv: null };
  const sd = Math.sqrt(xs.reduce((a, x) => a + (x - mean) ** 2, 0) / (xs.length - 1));
  const critical = xs.length <= 30 ? [0, 12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228, 2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086, 2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045][xs.length - 1]! : 1.959963984540054 + (1.959963984540054 ** 3 + 1.959963984540054) / (4 * (xs.length - 1)) + (5 * 1.959963984540054 ** 5 + 16 * 1.959963984540054 ** 3 + 3 * 1.959963984540054) / (96 * (xs.length - 1) ** 2);
  const width = critical * sd / Math.sqrt(xs.length);
  return { mean, low: mean - width, high: mean + width, n: xs.length, cv: mean === 0 ? null : sd / Math.abs(mean) };
}
export function tails(values: number[]) {
  const xs = [...values].sort((a, b) => a - b);
  const q = (p: number) => xs.length ? xs[Math.max(0, Math.ceil(xs.length * p) - 1)] ?? null : null;
  return { n: xs.length, p50: q(.5), p95: q(.95), p99: q(.99), max: xs.at(-1) ?? null };
}
export interface Repetition {
  repetition: number; observed_duration_ms: number; drain_ms: number; cpu_ms: number | null;
  rss_start_bytes: number | null; rss_end_bytes: number | null; disk_start_bytes: number | null; disk_end_bytes: number | null;
}
export interface Extras { repetitions: Repetition[]; fixture: unknown; polling: { count: number; failed: number; bytes: number; elapsed_ms: number }; partial_reason: string | null; exports?: unknown; profiles?: unknown[] }
export function summarize(m: Manifest, all: Sample[], extras: Extras) {
  const rows = all.filter(row => row.phase === 'steady');
  const duration = m.duration_ms * m.repetitions / 1000;
  const count = (p: (r: Sample) => boolean) => rows.filter(p).length;
  const eligible = rows.filter(r => r.outcome === 'completed' && r.terminal_correct);
  const good = (r: Sample) => r.outcome === 'completed' && r.terminal_correct && r.first_content_at_ms !== null && r.terminal_at_ms !== null
    && r.first_content_at_ms - r.scheduled_at_ms <= m.objectives.first_content_ms && r.terminal_at_ms - r.scheduled_at_ms <= m.objectives.completion_ms;
  const per = extras.repetitions.map(rep => {
    const rs = rows.filter(r => r.repetition === rep.repetition); const complete = rs.filter(r => r.outcome === 'completed' && r.terminal_correct);
    return { ...rep, offered: rs.length, completed: complete.length, goodput_rps: rs.filter(good).length / (m.duration_ms / 1000),
      cpu_ms_per_completion: rep.cpu_ms !== null && complete.length ? rep.cpu_ms / complete.length : null,
      completion_p95_ms: tails(complete.map(r => r.terminal_at_ms! - r.scheduled_at_ms)).p95 };
  });
  const counters = { offered: rows.length, accepted: count(r => r.accepted === true), accepted_unknown: count(r => r.accepted === null), completed: eligible.length,
    failed: count(r => r.outcome === 'failed'), refused: count(r => r.outcome === 'refused'), cancelled: count(r => r.outcome === 'cancelled'), pending: count(r => r.outcome === 'pending'),
    generator_dropped: count(r => r.outcome === 'generator_dropped') };
  const wireInvalid = count(r => ['completed', 'failed'].includes(r.outcome) && !r.terminal_correct);
  const generatorTails = tails(rows.flatMap(r => r.generator_delay_ms === null ? [] : [r.generator_delay_ms]));
  const saturated = counters.generator_dropped > 0 || (generatorTails.p95 ?? 0) > m.generator_limits.max_schedule_delay_ms;
  const repeated = per.length === m.repetitions;
  const missing = m.load.kind === 'arrival_rate' ? Math.max(0, Math.ceil(m.duration_ms * m.load.value / 1000) * m.repetitions - rows.length) : null;
  return { schema_version: 1, experiment_stage: m.experiment_stage ?? 'calibration', run_id: m.run_id, status: extras.partial_reason || !repeated || wireInvalid || counters.pending || counters.generator_dropped || missing ? 'partial' : 'complete',
    partial_reason: extras.partial_reason, requested_duration_ms: m.duration_ms * m.repetitions,
    observed_duration_ms: extras.repetitions.reduce((n, r) => n + r.observed_duration_ms, 0), counts: counters,
    rates_per_second: Object.fromEntries(Object.entries(counters).map(([k, v]) => [k, v / duration])),
    missing_generator_iterations: missing, dropped_generator_iterations: counters.generator_dropped,
    goodput: { objective_revision: m.objective_revision, eligible: eligible.length, successful: rows.filter(good).length, rps: rows.filter(good).length / duration,
      interval_95: interval(per.map(r => r.goodput_rps)), population: 'steady arrivals, completed by bounded drain; arrival-to-content/completion objectives' },
    first_content_ms: tails(rows.flatMap(r => r.first_content_at_ms === null ? [] : [r.first_content_at_ms - r.scheduled_at_ms])),
    completion_ms: tails(eligible.map(r => r.terminal_at_ms! - r.scheduled_at_ms)), queue_ms: { ...tails([]), reason: 'server admission measurement unavailable; generator delay is separate' },
    bytes: rows.reduce((n, r) => n + (r.bytes ?? 0), 0), events: rows.reduce((n, r) => n + (r.events ?? 0), 0), tokens: rows.reduce((n, r) => n + (r.tokens ?? 0), 0),
    missing_bytes: count(r => r.bytes === null), missing_tokens: count(r => r.tokens === null),
    cpu_ms_per_completion: interval(per.flatMap(r => r.cpu_ms_per_completion === null ? [] : [r.cpu_ms_per_completion])),
    runtime_resources: { source: m.target.kind === 'proxy' ? 'Linux /proc process CPU/RSS plus task state directory size; CPU includes steady interval and bounded drain' : 'unavailable: mock and generator share a process',
      gc_ms: null, allocation_bytes: null, reason: 'requires instrumented runtime/JFR adapter', repetitions: per },
    telemetry_loss: { value: null, reason: 'requires candidate sink counter adapter; no inference from request completion' },
    polling: extras.polling, export_cost: { collector: extras.exports ?? null, cpu_ms: null, reason: 'collector bytes/time measured; exporter CPU requires instrumented counter adapter' }, profiles: extras.profiles ?? [],
    wire: { invalid: wireInvalid, terminal_correct: count(r => r.terminal_correct), hashes_recorded: count(r => r.wire_sha256 !== null), fixture: extras.fixture },
    confidence_intervals: { method: 'two-sided Student t over repetition means; independent repetitions assumption; no request-level pseudoreplication', completion_p95_ms: interval(per.flatMap(r => r.completion_p95_ms === null ? [] : [r.completion_p95_ms])) },
    generator: { delay_ms: generatorTails, saturated, max_inflight: m.generator_limits.max_inflight, achieved_offered_rps: rows.length / duration,
      limitations: ['Bun mock and generator share one event loop', 'synthetic output token counts are scripted usage, not tokenizer estimates', 'arrival schedule continues at fixed deadlines; overloaded slots are explicitly dropped', 'no inference of sustainable capacity from short runs', 'cache state is scripted upstream cache usage, not real provider cache verification'] } };
}
