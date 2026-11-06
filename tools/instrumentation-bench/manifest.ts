import { createHash } from 'node:crypto';
import { cpus, platform, release, arch, totalmem } from 'node:os';

export const budget = Object.freeze({ revision: '2026-11-05-before-candidate', goodput_degradation_pct: 5,
  cpu_per_completion_degradation_pct: 5, added_p95_ms: 1, added_p95_pct: 5,
  aggregate_cache_bytes: 32 * 1024 ** 2, rollup_disk_bytes: 256 * 1024 ** 2,
  active_head_model_combinations_per_family: 256, sustained_extra_growth_bytes: 0,
  silent_loss: 0, maximum_baseline_cv: 0.10, profile_budget: 'separate-measurement' });
export const scenarios = ['steady', 'long', 'large-history', 'slow-client', 'tool-heavy', 'retry', 'refusal', 'cancel', 'bursty', 'reconnect'] as const;
export type Scenario = typeof scenarios[number];
export interface Manifest {
  schema_version: 1; experiment_stage?: 'calibration' | 'steady'; run_id: string; role: 'baseline' | 'candidate' | 'direct'; source_commit: string;
  artifact_sha256: string; process_build: string; boot_id: string; config_revision: string;
  hardware: unknown; os: unknown; jvm: unknown; seed: number; warmup_ms: number; duration_ms: number;
  repetitions: number; scenario: Scenario; protocol: 'anthropic' | 'responses';
  transport: 'sse' | 'http' | 'websocket'; streaming: boolean; input_bytes: number;
  upstream?: { protocol: 'anthropic' | 'responses'; transport: 'sse' | 'websocket' };
  collector_fault?: { mode: 'healthy' | 'slow' | 'unavailable'; delay_ms: number; max_active: number };
  retained_history?: { directory: string; receipt_sha256: string; source_commit: string };
  output_tokens_target: number; cache_state: 'cold' | 'warm'; objective_revision: string;
  objectives: { first_content_ms: number; completion_ms: number; queue_ms: number | null };
  load: { kind: 'concurrency' | 'arrival_rate'; value: number };
  collection: { mode: 'minimal' | 'local'; exports: boolean; profiles: boolean; polling: { interval_ms: number; path: string } | null };
  budgets: typeof budget;
  generator_limits: { max_inflight: number; max_iterations: number; timeout_ms: number; max_wire_bytes: number; max_schedule_delay_ms: number };
  target: { kind: 'direct' | 'proxy'; artifact_path?: string; launcher_sha256?: string };
  fixture: { first_wait_ms: number; chunk_wait_ms: number; chunks: number; slow_read_ms: number; cancel_after_ms: number; history_messages: number };
  provenance?: unknown;
}
export const hash = (value: string | Uint8Array) => createHash('sha256').update(value).digest('hex');
export function defaults(): Manifest {
  return { schema_version: 1, experiment_stage: 'calibration', run_id: 'example', role: 'direct', source_commit: 'unknown', artifact_sha256: 'unknown',
    process_build: 'unknown', boot_id: 'unknown', config_revision: 'unknown', hardware: 'unknown', os: 'unknown', jvm: 'unknown',
    seed: 17, warmup_ms: 1000, duration_ms: 5000, repetitions: 5, scenario: 'steady', protocol: 'anthropic', transport: 'sse', streaming: true,
    input_bytes: 1024, output_tokens_target: 4, cache_state: 'warm', objective_revision: 'fixture-objectives-v1',
    objectives: { first_content_ms: 1000, completion_ms: 2000, queue_ms: null }, load: { kind: 'concurrency', value: 1 },
    collection: { mode: 'local', exports: false, profiles: false, polling: null }, budgets: { ...budget },
    generator_limits: { max_inflight: 128, max_iterations: 100000, timeout_ms: 10000, max_wire_bytes: 4 * 1024 ** 2, max_schedule_delay_ms: 20 },
    target: { kind: 'direct' }, fixture: { first_wait_ms: 10, chunk_wait_ms: 5, chunks: 4, slow_read_ms: 0, cancel_after_ms: 15, history_messages: 2 } };
}
function requireValue(ok: unknown, message: string): asserts ok { if (!ok) throw new Error(message); }
function bounded(n: unknown, min: number, max: number) { return typeof n === 'number' && Number.isFinite(n) && n >= min && n <= max; }
export function validate(m: Manifest): Manifest {
  requireValue(m && typeof m === 'object' && m.schema_version === 1, 'schema_version must be 1');
  const fields = Object.keys(defaults()).concat('provenance', 'upstream', 'collector_fault', 'retained_history');
  requireValue(Object.keys(m).every(key => fields.includes(key)), 'unknown manifest field');
  const nested: Record<string, string[]> = { objectives: ['first_content_ms', 'completion_ms', 'queue_ms'], load: ['kind', 'value'], collection: ['mode', 'exports', 'profiles', 'polling'], target: ['kind', 'artifact_path', 'launcher_sha256'], generator_limits: Object.keys(defaults().generator_limits), fixture: Object.keys(defaults().fixture) };
  for (const [key, allowed] of Object.entries(nested)) { const value = (m as unknown as Record<string, unknown>)[key]; requireValue(value && typeof value === 'object' && Object.keys(value).every(field => allowed.includes(field)), `unknown ${key} field`); }
  requireValue(['calibration', 'steady'].includes(m.experiment_stage ?? 'calibration'), 'invalid experiment_stage');
  if (m.experiment_stage === 'steady') requireValue(m.warmup_ms >= 10000 && m.duration_ms >= 30000 && m.repetitions >= 5, 'steady acceptance requires >=10s warmup, >=30s measured and >=5 repetitions; JIT stability still needs evidence');
  requireValue(/^[A-Za-z0-9_.-]{1,100}$/.test(m.run_id), 'invalid run_id');
  for (const key of ['source_commit', 'artifact_sha256', 'process_build', 'boot_id', 'config_revision', 'objective_revision'] as const)
    requireValue(typeof m[key] === 'string' && /^[A-Za-z0-9_.:+-]{1,200}$/.test(m[key]), `invalid ${key}`);
  requireValue(['baseline', 'candidate', 'direct'].includes(m.role), 'invalid role');
  requireValue(scenarios.includes(m.scenario), 'invalid scenario');
  requireValue(['anthropic', 'responses'].includes(m.protocol), 'invalid protocol');
  requireValue(['sse', 'http', 'websocket'].includes(m.transport), 'invalid transport');
  requireValue(m.streaming === (m.transport !== 'http'), 'streaming and transport disagree');
  if (m.upstream) requireValue(Object.keys(m.upstream).every(k => ['protocol', 'transport'].includes(k)) && ['anthropic', 'responses'].includes(m.upstream.protocol) && ['sse', 'websocket'].includes(m.upstream.transport) && (m.upstream.transport !== 'websocket' || m.upstream.protocol === 'responses'), 'invalid upstream transport');
  if (m.collector_fault) requireValue(m.collection.exports && Object.keys(m.collector_fault).every(k => ['mode', 'delay_ms', 'max_active'].includes(k)) && ['healthy', 'slow', 'unavailable'].includes(m.collector_fault.mode) && bounded(m.collector_fault.delay_ms, 0, 10000) && bounded(m.collector_fault.max_active, 1, 32) && Number.isInteger(m.collector_fault.max_active), 'invalid collector fault');
  if (m.retained_history) requireValue(m.role === 'candidate' && Object.keys(m.retained_history).every(k => ['directory', 'receipt_sha256', 'source_commit'].includes(k)) && /^\/tmp\/splice-instrumentation-[A-Za-z0-9_./-]+$/.test(m.retained_history.directory) && /^[a-f0-9]{64}$/.test(m.retained_history.receipt_sha256) && m.retained_history.source_commit === m.source_commit, 'invalid retained history fixture');
  requireValue(bounded(m.seed, 0, 2 ** 32 - 1) && Number.isInteger(m.seed), 'invalid seed');
  for (const [name, min, max] of [['warmup_ms', 0, 3600000], ['duration_ms', 100, 3600000], ['repetitions', 1, 100], ['input_bytes', 256, 16 * 1024 ** 2], ['output_tokens_target', 1, 100000]] as const)
    requireValue(bounded(m[name], min, max) && Number.isInteger(m[name]), `invalid ${name}`);
  requireValue(m.role === 'direct' || m.repetitions >= 5, 'compared proxy points require >=5 repetitions');
  requireValue(m.role !== 'candidate' || (Math.ceil((m.warmup_ms + m.duration_ms) / 1000) + 4) * m.repetitions <= 10000, 'candidate telemetry point envelope exceeds 10000');
  requireValue(['cold', 'warm'].includes(m.cache_state), 'invalid cache_state');
  requireValue(['concurrency', 'arrival_rate'].includes(m.load?.kind) && bounded(m.load.value, 0.01, 100000), 'invalid load');
  requireValue(m.load.kind !== 'concurrency' || Number.isInteger(m.load.value), 'concurrency must be an integer');
  const g = m.generator_limits;
  requireValue(g && bounded(g.max_inflight, 1, 1024) && Number.isInteger(g.max_inflight), 'invalid max_inflight');
  requireValue(m.load.kind !== 'concurrency' || m.load.value <= g.max_inflight, 'concurrency exceeds generator cap');
  requireValue(bounded(g.max_iterations, 1, 500000) && Number.isInteger(g.max_iterations), 'invalid max_iterations');
  requireValue(bounded(g.timeout_ms, 50, 120000) && bounded(g.max_wire_bytes, 1024, 64 * 1024 ** 2) && bounded(g.max_schedule_delay_ms, 1, 10000), 'invalid generator limits');
  if (m.load.kind === 'arrival_rate') requireValue(Math.ceil(m.load.value * (m.duration_ms + m.warmup_ms) / 1000) * m.repetitions <= g.max_iterations, 'planned arrivals exceed max_iterations');
  requireValue(m.objectives && bounded(m.objectives.first_content_ms, 1, 3600000) && bounded(m.objectives.completion_ms, 1, 3600000) && (m.objectives.queue_ms === null || bounded(m.objectives.queue_ms, 0, 3600000)), 'invalid objectives');
  requireValue(m.objectives.queue_ms === null, 'queue SLO requires an instrumented queue adapter; generator delay is not server queue time');
  requireValue(JSON.stringify(m.budgets) === JSON.stringify(budget), 'budgets differ from the pre-candidate revision');
  requireValue(['minimal', 'local'].includes(m.collection?.mode) && typeof m.collection.exports === 'boolean' && typeof m.collection.profiles === 'boolean', 'invalid collection');
  requireValue((!m.collection.exports && !m.collection.profiles) || m.role === 'candidate', 'exports/profiles require a candidate artifact with the declared adapters');
  requireValue(m.role !== 'baseline' || m.collection.mode === 'local', 'legacy baseline has no minimal collection toggle');
  if (m.collection.polling) requireValue(bounded(m.collection.polling.interval_ms, 10, 60000) && ['/health', '/mgmt/status', '/mgmt/usage', ...(m.role === 'candidate' ? ['/api/perf/runtime', '/api/perf/schema', '/api/perf/summary?contract=1'] : [])].includes(m.collection.polling.path), 'invalid polling route/interval');
  requireValue(['direct', 'proxy'].includes(m.target?.kind), 'invalid target kind');
  requireValue(m.target.kind === 'direct' ? m.role === 'direct' : m.role !== 'direct', 'role and target disagree');
  if (m.target.kind === 'proxy') {
    requireValue(m.protocol === 'anthropic' && m.transport !== 'websocket', 'HeadServer client protocol is Anthropic; select upstream Responses/WS independently');
    requireValue(typeof m.target.artifact_path === 'string' && /^\/tmp\/splice-instrumentation-[A-Za-z0-9_./-]+$/.test(m.target.artifact_path) && m.target.artifact_path.endsWith('.jar'), 'proxy artifact must be in task-specific /tmp/splice-instrumentation-*');
    requireValue(/^[a-f0-9]{64}$/.test(m.artifact_sha256) && /^[a-f0-9]{40}$/.test(m.source_commit), 'proxy requires full verified artifact/source identity');
    if (m.role === 'baseline') requireValue(m.source_commit === '45cae7f4fcc3382ebc816fdfe52296aa8ce8a045', 'baseline must be unchanged source');
  }
  if (m.transport === 'websocket') requireValue(m.protocol === 'responses', 'WS fixture uses Responses only');
  for (const k of Object.keys(defaults().fixture)) { const n = m.fixture?.[k as keyof Manifest['fixture']]; requireValue(bounded(n, 0, 100000) && Number.isInteger(n), `invalid fixture.${k}`); }
  requireValue(m.fixture && m.fixture.chunks >= 1 && m.fixture.chunks <= 1000 && m.fixture.history_messages >= 1 && m.fixture.history_messages <= 10000, 'invalid fixture shape');
  requireValue(m.fixture.chunks === m.output_tokens_target, 'fixture emits exactly one synthetic token per chunk');
  requireValue((m.input_bytes + g.max_wire_bytes) * g.max_inflight <= 768 * 1024 ** 2, 'generator byte/concurrency envelope exceeds 768 MiB');
  if (m.collection.profiles) requireValue(m.warmup_ms + m.duration_ms + g.timeout_ms <= 300000, 'profile interval exceeds 300 seconds');
  if (m.scenario === 'cancel') requireValue(m.fixture.cancel_after_ms > 0 && m.fixture.cancel_after_ms < m.fixture.first_wait_ms, 'cancellation must precede fixture content');
  if (m.scenario === 'large-history') requireValue(m.fixture.history_messages >= 100, 'large-history requires >=100 history messages');
  if (m.scenario === 'slow-client') requireValue(m.fixture.slow_read_ms > 0, 'slow-client requires slow_read_ms');
  if (m.scenario === 'reconnect') requireValue(m.target.kind === 'direct' ? m.transport === 'websocket' : m.upstream?.transport === 'websocket', 'reconnect requires upstream WebSocket');
  return m;
}
export function environment() {
  return { hardware: { architecture: arch(), logical_cpus: cpus().length, cpu_model: cpus()[0]?.model ?? 'unknown', memory_bytes: totalmem() },
    os: { platform: platform(), release: release() }, generator: { bun: Bun.version, pid: process.pid } };
}
export function loopback(url: string) {
  const u = new URL(url);
  requireValue(['http:', 'ws:'].includes(u.protocol) && u.hostname === '127.0.0.1' && Number(u.port) > 1024 && !['3096', '3099'].includes(u.port) && !u.username && !u.password && !u.search && !u.hash, 'target must be an isolated loopback URL without credentials/query');
  return u;
}
