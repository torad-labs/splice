import { defaults, validate, type Manifest } from './manifest';
/** Preparation only. This module never starts a process, listener or workload. */
export function finalSchedule() {
  const points: { id: string; cohort: string; evidence: string; patch: Partial<Manifest>; prerequisite?: string }[] = [];
  for (const value of [1, 10, 50, 70, 100]) points.push({ id: `closed-${value}`, cohort: 'matched', evidence: 'closed-loop capacity and default collection overhead', patch: { load: { kind: 'concurrency', value } } });
  for (const value of [100, 250, 400, 500]) points.push({ id: `arrival-${value}`, cohort: 'matched', evidence: 'fixed offered deadlines, generator loss and saturation boundary', patch: { warmup_ms: 10000, duration_ms: 30000, load: { kind: 'arrival_rate', value } } });
  points.push(
    { id: 'long-c10', cohort: 'matched', evidence: 'long completion/chunk interval', patch: { scenario: 'long', output_tokens_target: 100, fixture: { ...defaults().fixture, first_wait_ms: 100, chunk_wait_ms: 10, chunks: 100 }, load: { kind: 'concurrency', value: 10 } } },
    { id: 'nonstream-c10', cohort: 'matched', evidence: 'nonstream terminal and usage parity', patch: { transport: 'http', streaming: false, load: { kind: 'concurrency', value: 10 } } },
    { id: 'cold-c10', cohort: 'matched', evidence: 'fresh JVM and script cache usage per repetition', patch: { cache_state: 'cold', load: { kind: 'concurrency', value: 10 } } },
    { id: 'large-request-history', cohort: 'matched', evidence: 'large client request history, distinct from retained telemetry history', patch: { scenario: 'large-history', input_bytes: 262144, fixture: { ...defaults().fixture, history_messages: 1000 }, load: { kind: 'concurrency', value: 10 } } },
    { id: 'slow-client', cohort: 'matched', evidence: 'bounded delayed downstream reads', patch: { scenario: 'slow-client', fixture: { ...defaults().fixture, slow_read_ms: 50 }, load: { kind: 'concurrency', value: 10 } } },
    ...(['bursty', 'retry', 'refusal'] as const).map(scenario => ({ id: scenario, cohort: 'matched', evidence: 'scripted failure/retry/upstream gaps; preserve attempts and actual terminals', patch: { scenario, load: { kind: 'concurrency' as const, value: 10 } } })),
    { id: 'cancel', cohort: 'matched', evidence: 'pre-content client cancellation', patch: { scenario: 'cancel', fixture: { ...defaults().fixture, first_wait_ms: 100, cancel_after_ms: 15 }, load: { kind: 'concurrency', value: 10 } } },
    ...(['sse', 'websocket'] as const).map(transport => ({ id: `responses-${transport}`, cohort: 'matched', evidence: 'real Responses provider conversion; assert actual requested upstream transport', patch: { upstream: { protocol: 'responses' as const, transport }, load: { kind: 'concurrency' as const, value: 10 } } })),
    { id: 'responses-reconnect', cohort: 'matched', evidence: 'production WebSocket 1011 before output; record retry/fallback and honest terminal', patch: { scenario: 'reconnect', upstream: { protocol: 'responses', transport: 'websocket' }, load: { kind: 'concurrency', value: 10 } } },
    { id: 'minimal-c10', cohort: 'candidate-mode-study', evidence: 'new collection disabled; legacy collection retained', patch: { collection: { ...defaults().collection, mode: 'minimal' }, load: { kind: 'concurrency', value: 10 } } },
    ...([1000, 10] as const).map(interval_ms => ({ id: `poll-${interval_ms}`, cohort: 'candidate-mode-study', evidence: 'authenticated production summary normal/stress polling cost', patch: { collection: { ...defaults().collection, polling: { interval_ms, path: '/api/perf/summary?contract=1' } }, load: { kind: 'concurrency' as const, value: 10 } } })),
    ...(['healthy', 'slow', 'unavailable'] as const).map(mode => ({ id: `exports-${mode}`, cohort: 'candidate-mode-study', evidence: 'parsed metrics/traces/logs, real exporter counters, bounded failure/pressure', patch: { collection: { ...defaults().collection, exports: true }, collector_fault: { mode, delay_ms: mode === 'slow' ? 3000 : 0, max_active: mode === 'slow' ? 1 : 8 }, load: { kind: 'concurrency' as const, value: 70 } } })),
    { id: 'profile-c10', cohort: 'candidate-mode-study', evidence: 'actual JFR artifact covering steady interval; separate profile overhead', patch: { collection: { ...defaults().collection, profiles: true }, load: { kind: 'concurrency', value: 10 } } },
    { id: 'retained-history', cohort: 'candidate-history-study', evidence: 'populated real-writer 24h/7d series, first/repeat read, coverage/resolution/work and retained-disk cost', patch: { cache_state: 'cold' }, prerequisite: 'compiled BenchmarkFixtureSeed entry; immutable verified fixture receipt bound to frozen candidate source' },
    { id: 'retained-state', cohort: 'candidate-retention-study', evidence: 'five fresh minimal/local pairs; five post-fill equal-work producer-paused live-heap/pool/disk points; fixed zero-growth decision', patch: {}, prerequisite: 'compiled BenchmarkRetainedFixture classes plus frozen candidate jar; authorized retained.ts driver, separate from inference timing' },
    { id: 'native-mcp', cohort: 'production-execution-study', evidence: 'real native worker and stdio MCP child, returned values, PID/executable/class hashes and observations', patch: {}, prerequisite: 'compiled BenchmarkExecutionFixture entry; isolated user.home/java.io.tmpdir; execute separately, not through run.ts' },
  );
  return { schema_version: 1, measured: false, authorization: 'root freeze required; this schedule does not authorize execution',
    execution: 'serial points, serial JVMs; alternate baseline/candidate order across matched point repetitions or paired complete runs; no competing heavy builds',
    minimum_repetitions: 5, default_warmup_ms: 20000, default_steady_ms: 60000,
    stability: 'lower bounds only: extend matched durations/repetitions if JIT, variance or confidence intervals remain unstable; never loosen fixed budgets',
    open_sweep: '500 rps is the initial bounded endpoint, not a guaranteed saturation point. If unsaturated, prepare an explicitly larger bounded generator envelope or additional points; do not claim capacity.',
    direct_controls: 'For each matched point create direct Anthropic control; for upstream Responses select direct Responses protocol and same upstream transport/timing. End-to-end p95 differences are not isolated proxy self-time.',
    scenario_count: points.length, points };
}
export function materialize(point: ReturnType<typeof finalSchedule>['points'][number], role: Manifest['role'], artifact?: { path: string; source_commit: string; sha256: string }): Manifest {
  if (point.cohort !== 'matched' && role !== 'candidate') throw new Error('study_requires_candidate_role');
  if (point.prerequisite) throw new Error('fixture_prerequisite_required');
  const manifest: Manifest = { ...defaults(), ...point.patch, experiment_stage: 'steady', run_id: `${point.id}-${role}`, role, warmup_ms: point.patch.warmup_ms ?? 20000, duration_ms: point.patch.duration_ms ?? 60000, repetitions: 5 };
  manifest.generator_limits = { ...manifest.generator_limits, max_iterations: 500000 };
  if (role !== 'direct') {
    if (!artifact) throw new Error('verified_artifact_receipt_required');
    manifest.target = { kind: 'proxy', artifact_path: artifact.path }; manifest.source_commit = artifact.source_commit; manifest.artifact_sha256 = artifact.sha256;
  } else if (manifest.upstream?.protocol === 'responses') {
    manifest.protocol = 'responses'; manifest.transport = manifest.upstream.transport; delete manifest.upstream;
  }
  return validate(manifest);
}
if (import.meta.main) console.log(JSON.stringify(finalSchedule(), null, 2));
