#!/usr/bin/env bun
import { mkdir, open, rename, realpath } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
import { defaults, environment, hash, validate, type Manifest } from './manifest';
import { requestBytes, sleep, startUpstream } from './fixture';
import { initial, observe, type Sample } from './observe';
import { resource, startDesk } from './desk';
import { summarize, type Extras } from './summary';
import { analyze } from './analyze';
import { collector, startProfile } from './adapters';
import { startTelemetry, telemetrySummary, type TelemetryPoint } from "./telemetry";
import { queryHistory } from './history';
import type { DeskIdentity } from './identity';
const harnessAtLoad = Promise.all(['run.ts', 'manifest.ts', 'fixture.ts', 'observe.ts', 'desk.ts', 'summary.ts', 'analyze.ts', 'adapters.ts', 'identity.ts', 'control.ts', 'profile.ts', 'otlp.ts', 'telemetry.ts', 'history.ts', 'costs.ts', 'retained.ts', 'retained-model.ts'].map(f => Bun.file(join(import.meta.dir, f)).text())).then(files => hash(files.join('\n')));
export async function writeVerified(path: string, value: string) {
  const temporary = `${path}.${randomUUID()}.tmp`; const file = await open(temporary, 'wx', 0o600);
  try { await file.writeFile(value); await file.sync(); } finally { await file.close(); }
  await rename(temporary, path);
  if (hash(await Bun.file(path).text()) !== hash(value)) throw new Error('receipt_write_verification_failed');
}
export async function run(input: Manifest, directory: string, dry = false) {
  const m = structuredClone(validate(input)); m.experiment_stage ??= 'calibration'; delete m.provenance; m.hardware = 'unknown'; m.os = 'unknown'; m.jvm = 'unknown'; const body = requestBytes(m);
  const output = resolve(directory);
  if (!/^\/tmp\/splice-instrumentation-[A-Za-z0-9_./-]+$/.test(output)) throw new Error('output must be task-specific /tmp/splice-instrumentation-*');
  const experiment = { manifest: m, request_bytes: body.byteLength, request_sha256: hash(body),
    phases: ['warmup', 'steady', 'bounded-drain'], schedule: m.load.kind === 'arrival_rate' ? { interval_ms: 1000 / m.load.value, overload: 'record generator_dropped, keep original deadlines' } : { workers: m.load.value },
    planned_steady_arrivals: m.load.kind === 'arrival_rate' ? Math.ceil(m.load.value * m.duration_ms / 1000) * m.repetitions : null,
    fixture_script: { first_wait_ms: m.fixture.first_wait_ms, chunk_wait_ms: m.fixture.chunk_wait_ms, chunks: m.fixture.chunks, flush: 'each SSE event / each WS frame', request_content_recorded: false }, measured: false };
  await mkdir(output, { recursive: false, mode: 0o700 });
  if (!(await realpath(output)).startsWith('/tmp/splice-instrumentation-')) throw new Error('output escaped task directory');
  if (dry) { await writeVerified(join(output, 'manifest.json'), JSON.stringify(m, null, 2) + '\n'); await writeVerified(join(output, 'samples.jsonl'), ''); await writeVerified(join(output, 'plan.json'), JSON.stringify(experiment, null, 2) + '\n'); return { status: 'planned', measured: false }; }
  await writeVerified(join(output, 'manifest.json'), JSON.stringify(m, null, 2) + '\n');
  await writeVerified(join(output, 'samples.jsonl'), '');
  const env = environment(); m.hardware = env.hardware; m.os = env.os;
  const samples: Sample[] = []; const extras: Extras = { repetitions: [], fixture: null, polling: { count: 0, failed: 0, bytes: 0, elapsed_ms: 0 }, partial_reason: null };
  const upstream = startUpstream(m); const exporter = collector(m.collection.exports, m.generator_limits.max_wire_bytes, m.collector_fault); let desk: Awaited<ReturnType<typeof startDesk>> | undefined;
  const bootIds: string[] = []; const processIdentities: DeskIdentity[] = []; const origin = performance.now(); const now = () => performance.now() - origin;
  const telemetryPoints: TelemetryPoint[] = []; const telemetryBoundaries: { before: number; after: number; repetition: number; from_ms: number; until_ms: number }[] = [];
  let activeTelemetry: Awaited<ReturnType<typeof startTelemetry>> | null = null;
  let sequence = 0, polling = false, pollTask: Promise<void> | undefined;
  const startPolling = () => {
    if (!m.collection.polling || !desk) return;
    const { interval_ms, path } = m.collection.polling; const current = desk;
    polling = true;
    pollTask = (async () => { while (polling) {
      const started = now(); extras.polling.count++;
      try {
        await current.assertIdentity();
        const r = await fetch((path.startsWith('/api/') ? current.controlBase : current.pollBase) + path, { headers: current.mgmtKey ? { authorization: `Bearer ${current.mgmtKey}` } : {}, signal: AbortSignal.timeout(Math.min(2000, m.generator_limits.timeout_ms)), redirect: 'error' });
        if (!r.ok) extras.polling.failed++;
        const reader = r.body?.getReader(); let size = 0;
        if (reader) while (true) { const v = await reader.read(); if (v.done) break; size += v.value.byteLength; if (size > m.generator_limits.max_wire_bytes) { await reader.cancel(); extras.polling.failed++; break; } }
        extras.polling.bytes += size;
      } catch { extras.polling.failed++; }
      extras.polling.elapsed_ms += now() - started;
      if (polling) await sleep(Math.max(0, interval_ms - (now() - started)));
    } })();
  };
  const phase = async (repetition: number, name: Sample['phase'], duration: number) => {
    await desk!.assertIdentity();
    const from_epoch_ms = Date.now(); const start = now(), deadline = start + duration; const active = new Set<Promise<void>>(); let capped = false;
    const launch = (scheduled: number) => {
      if (sequence >= m.generator_limits.max_iterations) { capped = true; return; }
      const row = initial(sequence++, repetition, name, scheduled);
      if (active.size >= m.generator_limits.max_inflight) { row.outcome = 'generator_dropped'; row.accepted = false; row.error_class = 'generator_inflight_cap'; row.generator_delay_ms = now() - scheduled; samples.push(row); return; }
      let promise: Promise<void>;
      promise = observe(m, desk!.target, body, row, now).then(result => { samples.push(result); }).finally(() => active.delete(promise));
      active.add(promise);
    };
    if (m.load.kind === 'arrival_rate') {
      const step = 1000 / m.load.value;
      for (let slot = 0, count = Math.ceil(duration / step); slot < count; slot++) {
        const scheduled = start + slot * step; if (scheduled > now()) await sleep(scheduled - now());
        launch(scheduled); if (capped) break;
        if (slot % 256 === 255) await sleep(0);
      }
      if (now() < deadline) await sleep(deadline - now());
    } else {
      await Promise.all(Array.from({ length: m.load.value }, async () => {
        while (now() < deadline && !capped) {
          if (sequence >= m.generator_limits.max_iterations) { capped = true; break; }
          const row = initial(sequence++, repetition, name, now());
          samples.push(await observe(m, desk!.target, body, row, now));
        }
      }));
    }
    const offeredEnd = now(); await Promise.all(active); await desk!.assertIdentity();
    if (capped) extras.partial_reason = 'generator_iteration_limit';
    return { from_epoch_ms, until_epoch_ms: Date.now(), observed_duration_ms: offeredEnd - start, scheduling_elapsed_ms: offeredEnd - start, drain_ms: Math.max(0, now() - deadline) };
  };
  try {
    for (let repetition = 0; repetition < m.repetitions; repetition++) {
      if (!desk) {
        desk = await startDesk(m, upstream.url, join(output, `desk-${repetition}`), exporter.endpoint); bootIds.push(desk.bootId);
        m.boot_id = bootIds[0] ?? 'unknown'; m.jvm = desk.jvm; m.process_build = desk.processBuild; m.config_revision = desk.configHash;
        if (desk.identity) processIdentities.push(desk.identity);
        startPolling();
      }
      const profile = await startProfile(m, desk, repetition, output);
      const telemetry = m.role === 'candidate' ? await startTelemetry(m, desk, repetition) : null;
      activeTelemetry = telemetry;
      if (desk.history) { extras.history ??= []; extras.history.push(...await queryHistory(desk, desk.history.until_ms, repetition)); }
      if (telemetry) { extras.schemas ??= []; extras.schemas.push(await telemetry.schema()); await telemetry.sample(); telemetry.start(); }
      await phase(repetition, 'warmup', m.warmup_ms);
      await telemetry?.pause(); telemetry?.setPhase('steady');
      const telemetryBefore = telemetry?.points.length ?? 0; await telemetry?.sample(); telemetry?.start();
      const before = await resource(desk.pid, desk.state);
      const timing = await phase(repetition, 'steady', m.duration_ms);
      const after = await resource(desk.pid, desk.state);
      await telemetry?.pause(); const telemetryAfter = telemetry?.points.length ?? 0; await telemetry?.sample();
      if (telemetry) { const offset = telemetryPoints.length; telemetryPoints.push(...telemetry.points); telemetryBoundaries.push({ repetition, before: offset + telemetryBefore, after: offset + telemetryAfter, from_ms: timing.from_epoch_ms, until_ms: timing.until_epoch_ms }); }
      activeTelemetry = null;
      if (telemetry) { extras.query_summaries ??= []; extras.query_summaries.push(await telemetry.summary(timing.from_epoch_ms, timing.until_epoch_ms)); }
      if (profile) { await profile.stop(timing.from_epoch_ms, timing.until_epoch_ms); (extras.profiles ??= []).push(profile.receipt); if (profile.receipt.error_class) extras.partial_reason = 'profile_capture_partial'; }
      extras.repetitions.push({ repetition, ...timing, cpu_ms: before.cpu_ms !== null && after.cpu_ms !== null ? after.cpu_ms - before.cpu_ms : null,
        rss_start_bytes: before.rss_bytes, rss_end_bytes: after.rss_bytes, disk_start_bytes: before.disk_bytes, disk_end_bytes: after.disk_bytes });
      if (m.cache_state === 'cold') { polling = false; await pollTask; await desk.stop(); desk = undefined; }
      if (extras.partial_reason) break;
    }
  } catch (error) {
    const code = error instanceof Error ? error.message : 'run_failed';
    extras.partial_reason = /^[a-z0-9_]+$/.test(code) ? code : 'run_failed';
  } finally {
    await activeTelemetry?.pause();
    polling = false; await pollTask; if (desk) await desk.stop(); extras.fixture = upstream.stats(); await upstream.stop(); await exporter.stop(); extras.exports = exporter.counts; if (m.collection.exports && !exporter.counts.requests) extras.partial_reason ??= 'no_export_received'; if (extras.polling.failed) extras.partial_reason ??= 'polling_failed';
  }
  extras.telemetry = { points: telemetryPoints, ...telemetrySummary(telemetryPoints, telemetryBoundaries) };
  if (m.collection.exports && Object.values(exporter.counts.signals).some(signal => signal.records === 0)) extras.partial_reason ??= 'export_signal_missing';
  if (exporter.counts.wire_invalid || exporter.counts.evidence_overflow) extras.partial_reason ??= 'export_evidence_partial';
  if (upstream.stats().request_body_mismatches) extras.partial_reason ??= 'upstream_request_shape_invalid';
  if (m.upstream?.transport === 'websocket' && upstream.stats().websocket_requests === 0) extras.partial_reason ??= 'production_websocket_not_observed';
  m.provenance = { measured_at: new Date().toISOString(), environment: env, boot_ids: bootIds, process_identities: processIdentities, request_sha256: hash(body),
    supplied_identity: { source_commit: input.source_commit, artifact_sha256: input.artifact_sha256, process_build: input.process_build, config_revision: input.config_revision },
    harness_sha256: await harnessAtLoad };
  samples.sort((a, b) => a.sequence - b.sequence);
  const manifestBytes = JSON.stringify(m, null, 2) + '\n'; const sampleBytes = samples.map(row => JSON.stringify(row)).join('\n') + (samples.length ? '\n' : '');
  const summary = summarize(m, samples, extras);
  await writeVerified(join(output, 'manifest.json'), manifestBytes);
  await writeVerified(join(output, 'samples.jsonl'), sampleBytes);
  const observationBytes = JSON.stringify(extras, null, 2) + '\n';
  await writeVerified(join(output, 'observations.json'), observationBytes);
  await writeVerified(join(output, 'summary.json'), JSON.stringify(summary, null, 2) + '\n');
  await writeVerified(join(output, 'receipt.json'), JSON.stringify({ schema_version: 1, manifest_sha256: hash(manifestBytes), samples_sha256: hash(sampleBytes), observations_sha256: hash(observationBytes), sample_count: samples.length }, null, 2) + '\n');
  return summary;
}
const help = `Deterministic isolated instrumentation capacity harness (Bun)\n\n  bun tools/instrumentation-bench/run.ts run --manifest <json> --output </tmp/splice-instrumentation-.../run> [--dry-run]\n  bun tools/instrumentation-bench/run.ts analyze --baseline <run-dir> --candidate <run-dir>\n  bun tools/instrumentation-bench/run.ts example\n\nrun exits 2 for partial/wire-invalid receipts; analyze exits 2 for invalid, failed or inconclusive budgets.\nDry run writes the exact experiment plan with measured=false and empty samples; it is not benchmark evidence.\nOnly a verified jar in task-specific /tmp can become a proxy target. No installed daemon is contacted.\n`;
if (import.meta.main) {
  try {
    const args = process.argv.slice(2); const command = args.shift();
    if (!command || command === '--help') console.log(help);
    else if (command === 'example') console.log(JSON.stringify(defaults(), null, 2));
    else {
      const allowed = command === 'run' ? ['--manifest', '--output', '--dry-run'] : command === 'analyze' ? ['--baseline', '--candidate', '--retained-study'] : [];
      const opts: Record<string, string | boolean> = {};
      while (args.length) { const name = args.shift()!; if (!allowed.includes(name) || name in opts) throw new Error('invalid_arguments'); opts[name] = name === '--dry-run' ? true : args.shift() ?? ''; }
      if (command === 'run' && typeof opts['--manifest'] === 'string' && typeof opts['--output'] === 'string') {
        const result = await run(await Bun.file(opts['--manifest']).json(), opts['--output'], opts['--dry-run'] === true);
        console.log(JSON.stringify(result)); if (!['complete', 'planned'].includes(result.status)) process.exitCode = 2;
      } else if (command === 'analyze' && typeof opts['--baseline'] === 'string' && typeof opts['--candidate'] === 'string') {
        const result = await analyze(opts['--baseline'], opts['--candidate'], typeof opts['--retained-study'] === 'string' ? opts['--retained-study'] : undefined); console.log(JSON.stringify(result, null, 2)); if (result.status !== 'pass') process.exitCode = 2;
      } else throw new Error('invalid_arguments');
    }
  } catch (error) { console.error(JSON.stringify({ status: 'error', error_class: error instanceof Error && /^[a-z0-9_]+$/.test(error.message) ? error.message : 'invalid_input_or_io', detail: 'See README and validate the manifest; error payloads and request content are never recorded.' })); process.exitCode = 2; }
}
