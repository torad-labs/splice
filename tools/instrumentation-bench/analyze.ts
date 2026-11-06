import { join } from 'node:path';
import { hash, validate, type Manifest } from './manifest';
import { summarize, type Extras } from './summary';
import type { Sample } from './observe';
import { verifyRunBinding } from './identity';
export async function readRun(directory: string) {
  const manifest: Manifest = await Bun.file(join(directory, 'manifest.json')).json(); validate(manifest);
  const receipt = await Bun.file(join(directory, 'receipt.json')).json();
  const raw = await Bun.file(join(directory, 'samples.jsonl')).text();
  if (hash(raw) !== receipt.samples_sha256 || hash(await Bun.file(join(directory, 'manifest.json')).text()) !== receipt.manifest_sha256) throw new Error('receipt_digest_mismatch');
  const rows: Sample[] = raw.trim() ? raw.trimEnd().split('\n').map(line => JSON.parse(line)) : [];
  if (rows.length !== receipt.sample_count || new Set(rows.map(r => r.sequence)).size !== rows.length) throw new Error('sample_count_or_sequence_mismatch');
  const observationBytes = await Bun.file(join(directory, 'observations.json')).text();
  if (receipt.observations_sha256 !== hash(observationBytes)) throw new Error('observations_digest_mismatch');
  const extras: Extras = JSON.parse(observationBytes);
  const summary = summarize(manifest, rows, extras);
  return { manifest, summary, rows };
}
const comparable = (m: Manifest) => ({ scenario: m.scenario, protocol: m.protocol, transport: m.transport, streaming: m.streaming,
  input_bytes: m.input_bytes, output_tokens_target: m.output_tokens_target, cache_state: m.cache_state, seed: m.seed,
  warmup_ms: m.warmup_ms, duration_ms: m.duration_ms, fixture: m.fixture, load: m.load, objectives: m.objectives,
  objective_revision: m.objective_revision, hardware: m.hardware, os: m.os, jvm: m.jvm, target: m.target.kind, generator_limits: m.generator_limits, budgets: m.budgets });
export async function analyze(baseline: string, candidate: string) {
  const b = await readRun(baseline), c = await readRun(candidate);
  const reasons: string[] = [];
  if (b.manifest.role !== 'baseline') reasons.push('baseline_role_required');
  if (c.manifest.role !== 'candidate') reasons.push('candidate_role_required');
  const bindings = await Promise.allSettled([verifyRunBinding(b.manifest), verifyRunBinding(c.manifest)]);
  for (const [index, binding] of bindings.entries()) if (binding.status === 'rejected') reasons.push(`${index === 0 ? 'baseline' : 'candidate'}_identity_unverified`);
  const baselineBinding = bindings[0], candidateBinding = bindings[1];
  if (baselineBinding?.status === 'fulfilled' && candidateBinding?.status === 'fulfilled' && new Set([...baselineBinding.value, ...candidateBinding.value].map(id => id.base_config_sha256)).size !== 1) reasons.push('incompatible_process_configuration');
  if (b.manifest.experiment_stage !== 'steady' || c.manifest.experiment_stage !== 'steady') reasons.push('calibration_not_capacity_evidence');
  const provenanceB = b.manifest.provenance as any, provenanceC = c.manifest.provenance as any;
  if (provenanceB?.harness_sha256 !== provenanceC?.harness_sha256) reasons.push('different_harness_versions');
  if (b.manifest.collection.profiles !== c.manifest.collection.profiles) reasons.push('profile_overhead_requires_separate_envelope');
  if (b.manifest.repetitions < 5 || c.manifest.repetitions < 5) reasons.push('fewer_than_five_repetitions');
  if (JSON.stringify(comparable(b.manifest)) !== JSON.stringify(comparable(c.manifest))) reasons.push('incompatible_cohorts');
  if (b.summary.status !== 'complete' || c.summary.status !== 'complete') reasons.push('incomplete_runs');
  if (b.summary.generator.saturated || c.summary.generator.saturated) reasons.push('generator_saturation');
  const digestSet = (rows: Sample[]) => [...new Set(rows.filter(r => r.phase === 'steady' && r.outcome === 'completed').map(r => `${r.wire_sha256}:${r.content_sha256}:${r.tokens}`))].sort();
  if (JSON.stringify(digestSet(b.rows)) !== JSON.stringify(digestSet(c.rows))) reasons.push('wire_or_content_changed');
  if ((b.summary.goodput.interval_95.cv ?? Infinity) > b.manifest.budgets.maximum_baseline_cv) reasons.push('high_baseline_variance');
  if (reasons.length) return { schema_version: 1, status: 'invalid', reasons, baseline_run: b.manifest.run_id, candidate_run: c.manifest.run_id, checks: null, capacity_claim: null, note: 'Invalid roles, artifact/process/configuration binding or measurement cohorts cannot produce budget checks.' };
  const goodB = b.summary.goodput.interval_95, goodC = c.summary.goodput.interval_95;
  const goodput = goodB.mean && goodC.mean !== null ? (goodB.mean - goodC.mean) / goodB.mean * 100 : null;
  const goodputWorst = goodB.high && goodC.low !== null ? (goodB.high - goodC.low) / goodB.high * 100 : null;
  const cpuB = b.summary.cpu_ms_per_completion, cpuC = c.summary.cpu_ms_per_completion;
  const cpu = cpuB.mean && cpuC.mean !== null ? (cpuC.mean / cpuB.mean - 1) * 100 : null;
  const cpuWorst = cpuB.low && cpuC.high !== null ? (cpuC.high / cpuB.low - 1) * 100 : null;
  const pB = b.summary.confidence_intervals.completion_p95_ms, pC = c.summary.confidence_intervals.completion_p95_ms;
  const latency = pB.mean !== null && pC.mean !== null ? pC.mean - pB.mean : null;
  const latencyWorst = pB.low !== null && pC.high !== null ? pC.high - pB.low : null;
  const latencyBudget = pB.mean === null ? null : Math.max(b.manifest.budgets.added_p95_ms, pB.mean * b.manifest.budgets.added_p95_pct / 100);
  const decide = (point: number | null, worst: number | null, limit: number | null) => point === null || worst === null || limit === null ? 'unavailable' : point > limit ? 'fail' : worst > limit ? 'inconclusive' : 'pass';
  const checks = { goodput: { degradation_pct: goodput, conservative_95_upper_pct: goodputWorst, budget_pct: 5, status: decide(goodput, goodputWorst, 5) },
    cpu_per_completion: { degradation_pct: cpu, conservative_95_upper_pct: cpuWorst, budget_pct: 5, status: decide(cpu, cpuWorst, 5) },
    completion_p95: { added_ms: latency, conservative_95_upper_ms: latencyWorst, budget_ms: latencyBudget, status: decide(latency, latencyWorst, latencyBudget), limitation: 'end-to-end completion difference; not isolated proxy self-time' },
    sustained_extra_memory_disk_growth: { status: 'unavailable', reason: 'endpoint RSS/disk snapshots cannot establish sustained aggregate/cache or rollup growth; adapter required' },
    telemetry_loss: { status: 'unavailable', reason: 'sink-counter adapter required' } };
  const statuses = Object.values(checks).map(check => check.status);
  const status = reasons.length ? 'invalid' : statuses.includes('fail') ? 'fail' : statuses.some(s => ['inconclusive', 'unavailable'].includes(s)) ? 'inconclusive' : 'pass';
  return { schema_version: 1, status, reasons, baseline_run: b.manifest.run_id, candidate_run: c.manifest.run_id, checks,
    capacity_claim: null, note: 'A comparison cannot pass with unavailable required budgets. Direct and proxy are distinct cohorts; subtract matched direct measurements separately.' };
}
