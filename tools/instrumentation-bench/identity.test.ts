import { expect, test } from 'bun:test';
import { mkdir, mkdtemp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { defaults, hash, type Manifest } from './manifest';
import { analyze } from './analyze';
import { initial } from './observe';
import { assertProcessIdentity, waitForOwnedHealth, type DeskIdentity } from './identity';
import type { Extras } from './summary';

async function receiptFixture(root: string, role: 'baseline' | 'candidate') {
  const directory = join(root, role); await mkdir(directory);
  const artifact = join(directory, 'fixture.jar'); const artifactBytes = `synthetic analyzer identity fixture: ${role}`;
  await Bun.write(artifact, artifactBytes);
  const m = defaults(); m.role = role; m.run_id = role; m.experiment_stage = 'steady'; m.warmup_ms = 10000; m.duration_ms = 30000;
  m.source_commit = role === 'baseline' ? '45cae7f4fcc3382ebc816fdfe52296aa8ce8a045' : '1'.repeat(40);
  m.artifact_sha256 = hash(artifactBytes); m.target = { kind: 'proxy', artifact_path: artifact };
  m.process_build = `source:${m.source_commit}`; m.config_revision = hash(`synthetic config ${role}`);
  const artifactReceipt = JSON.stringify({ source_commit: m.source_commit, artifact_sha256: m.artifact_sha256, clean_source: true });
  await Bun.write(join(directory, 'identity.json'), artifactReceipt);
  const identity: DeskIdentity = { artifact_path: artifact, source_commit: m.source_commit, artifact_sha256: m.artifact_sha256,
    artifact_receipt_sha256: hash(artifactReceipt), process_build: m.process_build, config_revision: m.config_revision,
    base_config_sha256: hash('same base config'), raw_config_sha256: hash(`synthetic raw config ${role}`),
    pid: 1, start_ticks: '123', verification: 'linux-proc-listen-inodes', verified_at: '2026-11-05T00:00:00.000Z',
    listeners: [{ port: 40000, inode: '42', address: '0100007F' }, { port: 40001, inode: '43', address: '0100007F' }] };
  // These synthetic receipts test validation only; they are never published as measured evidence.
  m.provenance = { harness_sha256: hash('same test harness'), process_identities: [identity] };
  const samples = Array.from({ length: 5 }, (_, repetition) => ({ ...initial(repetition, repetition, 'steady', repetition * 40000),
    started_at_ms: repetition * 40000, first_content_at_ms: repetition * 40000 + 10, terminal_at_ms: repetition * 40000 + 20,
    accepted: true, outcome: 'completed', terminal_correct: true, bytes: 100, events: 5, tokens: 4,
    wire_sha256: hash('same wire'), content_sha256: hash('same content'), generator_delay_ms: 0 }));
  const extras: Extras = { repetitions: samples.map(row => ({ repetition: row.repetition, observed_duration_ms: 30000, drain_ms: 0,
    cpu_ms: 10, rss_start_bytes: 1, rss_end_bytes: 1, disk_start_bytes: 1, disk_end_bytes: 1 })),
    fixture: null, polling: { count: 0, failed: 0, bytes: 0, elapsed_ms: 0 }, partial_reason: null };
  const sampleBytes = samples.map(row => JSON.stringify(row)).join('\n') + '\n'; const observationBytes = JSON.stringify(extras);
  await Bun.write(join(directory, 'samples.jsonl'), sampleBytes); await Bun.write(join(directory, 'observations.json'), observationBytes);
  const save = async (manifest: Manifest = m) => {
    const bytes = JSON.stringify(manifest); await Bun.write(join(directory, 'manifest.json'), bytes);
    await Bun.write(join(directory, 'receipt.json'), JSON.stringify({ manifest_sha256: hash(bytes), samples_sha256: hash(sampleBytes), observations_sha256: hash(observationBytes), sample_count: samples.length }));
  };
  await save(); return { directory, manifest: m, artifact, artifactBytes, save };
}

test('analysis requires baseline/candidate roles and validated artifact/process/configuration binding', async () => {
  const root = await mkdtemp('/tmp/splice-instrumentation-binding-test-');
  try {
    const baseline = await receiptFixture(root, 'baseline'), candidate = await receiptFixture(root, 'candidate');
    const valid = await analyze(baseline.directory, candidate.directory);
    expect(valid.status).toBe('inconclusive'); expect(valid.reasons).toEqual([]); expect(valid.checks).not.toBeNull();
    await baseline.save({ ...baseline.manifest, role: 'candidate' });
    let result = await analyze(baseline.directory, candidate.directory);
    expect(result.reasons).toContain('baseline_role_required'); expect(result.checks).toBeNull();
    await baseline.save(); await candidate.save({ ...candidate.manifest, role: 'baseline', source_commit: baseline.manifest.source_commit });
    result = await analyze(baseline.directory, candidate.directory);
    expect(result.reasons).toContain('candidate_role_required'); expect(result.checks).toBeNull();
    await candidate.save();
    for (const changed of [{ process_build: 'source:' + '2'.repeat(40) }, { config_revision: hash('different config') }, { source_commit: '2'.repeat(40) }, { provenance: { harness_sha256: hash('same test harness') } }]) {
      await candidate.save({ ...candidate.manifest, ...changed });
      result = await analyze(baseline.directory, candidate.directory);
      expect(result.reasons).toContain('candidate_identity_unverified'); expect(result.checks).toBeNull();
    }
    await candidate.save(); await Bun.write(candidate.artifact, 'replaced artifact with same manifest labels');
    result = await analyze(baseline.directory, candidate.directory);
    expect(result.reasons).toContain('candidate_identity_unverified'); expect(result.checks).toBeNull();
    await Bun.write(candidate.artifact, candidate.artifactBytes);
    const differentBase = structuredClone(candidate.manifest);
    (differentBase.provenance as { process_identities: DeskIdentity[] }).process_identities[0]!.base_config_sha256 = hash('different base');
    await candidate.save(differentBase);
    result = await analyze(baseline.directory, candidate.directory);
    expect(result.reasons).toContain('incompatible_process_configuration'); expect(result.checks).toBeNull();
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('delayed dispatch without dropped iterations makes the run CLI nonzero', async () => {
  const root = await mkdtemp('/tmp/splice-instrumentation-delay-test-');
  try {
    const manifest = defaults(); manifest.warmup_ms = 0; manifest.duration_ms = 300; manifest.repetitions = 1;
    manifest.load = { kind: 'arrival_rate', value: 100 }; manifest.generator_limits.max_schedule_delay_ms = 1;
    await Bun.write(join(root, 'manifest.json'), JSON.stringify(manifest));
    // A competing event-loop workload delays deadlines without exhausting the128-request cap.
    await Bun.write(join(root, 'delay.ts'), 'setInterval(() => { const end = performance.now() + 30; while (performance.now() < end) {} }, 50).unref();');
    const cli = Bun.spawn([process.execPath, '--preload', join(root, 'delay.ts'), join(import.meta.dir, 'run.ts'), 'run', '--manifest', join(root, 'manifest.json'), '--output', join(root, 'run')], { stdout: 'pipe', stderr: 'pipe' });
    const [exit, stdout, stderr] = await Promise.all([cli.exited, new Response(cli.stdout).text(), new Response(cli.stderr).text()]);
    expect(stderr).toBe(''); expect(exit).toBe(2);
    const result = JSON.parse(stdout);
    expect(result.generator.saturated).toBe(true); expect(result.counts.generator_dropped).toBe(0); expect(result.missing_generator_iterations).toBe(0);
    expect(result.status).toBe('partial'); expect(result.partial_reason).toBe('generator_saturated');
  } finally { await rm(root, { recursive: true, force: true }); }
});

test.skipIf(process.platform !== 'linux')('a foreign healthy server cannot satisfy child readiness or receive a health probe', async () => {
  let foreignRequests = 0;
  const foreign = Bun.serve({ hostname: '127.0.0.1', port: 0, fetch() { foreignRequests++; return Response.json({ ok: true }); } });
  const idle = Bun.spawn([process.execPath, '-e', 'setInterval(() => {}, 1000)'], { stdout: 'ignore', stderr: 'pipe' });
  try {
    expect(foreign.port).toBeDefined();
    await expect(waitForOwnedHealth(idle.pid, foreign.port!, foreign.port!, () => idle.exitCode === null && idle.signalCode === null, 150)).rejects.toThrow('isolated_desk_identity_timeout');
    expect(foreignRequests).toBe(0);
  } finally { idle.kill(); await idle.exited; await foreign.stop(true); }
});

test.skipIf(process.platform !== 'linux')('readiness binds both live child sockets and rejects an exited owner', async () => {
  const child = Bun.spawn([process.execPath, '-e', 'const a = Bun.serve({hostname:"127.0.0.1",port:0,fetch:()=>Response.json({ok:true})}); const b = Bun.serve({hostname:"127.0.0.1",port:0,fetch:()=>Response.json({ok:true})}); console.log(JSON.stringify([a.port,b.port]));'], { stdout: 'pipe', stderr: 'pipe' });
  try {
    const reader = child.stdout.getReader(); let text = '';
    while (!text.includes('\n')) { const item = await reader.read(); if (item.done) throw new Error('child_server_failed'); text += new TextDecoder().decode(item.value); }
    reader.releaseLock(); const ports: number[] = JSON.parse(text.trim());
    const identity = await waitForOwnedHealth(child.pid, ports[0]!, ports[1]!, () => child.exitCode === null && child.signalCode === null, 2000);
    expect(identity.pid).toBe(child.pid); expect(identity.listeners.map(item => item.port)).toEqual(ports);
    await assertProcessIdentity(identity, () => child.exitCode === null && child.signalCode === null);
    child.kill(); await child.exited;
    await expect(assertProcessIdentity(identity, () => child.exitCode === null && child.signalCode === null)).rejects.toThrow('isolated_desk_exited');
  } finally { if (child.exitCode === null && child.signalCode === null) child.kill(); await child.exited; }
});

test.skipIf(process.platform !== 'linux')('task-owned wildcard listeners cannot qualify as loopback isolation', async () => {
  const child = Bun.spawn([process.execPath, '-e', 'const a = Bun.serve({hostname:"0.0.0.0",port:0,fetch:()=>Response.json({ok:true})}); const b = Bun.serve({hostname:"127.0.0.1",port:0,fetch:()=>Response.json({ok:true})}); console.log(JSON.stringify([a.port,b.port]));'], { stdout: 'pipe', stderr: 'pipe' });
  try {
    const reader = child.stdout.getReader(); let text = '';
    while (!text.includes('\n')) { const item = await reader.read(); if (item.done) throw new Error('child_server_failed'); text += new TextDecoder().decode(item.value); }
    reader.releaseLock(); const ports: number[] = JSON.parse(text.trim());
    await expect(waitForOwnedHealth(child.pid, ports[0]!, ports[1]!, () => child.exitCode === null && child.signalCode === null, 2000)).rejects.toThrow('process_listener_not_loopback');
  } finally { if (child.exitCode === null && child.signalCode === null) child.kill(); await child.exited; }
});
