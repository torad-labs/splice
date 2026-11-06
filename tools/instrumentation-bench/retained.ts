#!/usr/bin/env bun
import { lstat, mkdir, open, readFile, realpath } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { boundedBody } from './control';
import { defaults, environment, hash } from './manifest';
import { startTicks, verifyArtifact } from './identity';
import { diskSize } from './telemetry';
import { classDigest, compareRetained, parseHistogram, retainedProtocol, type RetainedManifest, type RetainedMeasurement, type RetainedRun } from './retained-model';
const mainClass = 'splice.app.instrumentation.BenchmarkRetainedFixture';
const artifactManifest = (m: RetainedManifest) => ({ ...defaults(), role: 'candidate' as const, source_commit: m.source_commit, artifact_sha256: m.artifact_sha256, target: { kind: 'proxy' as const, artifact_path: m.artifact_path } });
export async function fixtureClassReceipt(directory: string) {
  const root = await realpath(directory);
  if (!root.startsWith('/tmp/splice-instrumentation-')) throw new Error('fixture_classes_task_root_required');
  const entries: { path: string; sha256: string }[] = [];
  for await (const path of new Bun.Glob('splice/app/instrumentation/BenchmarkRetained*.class').scan(root)) {
    const absolute = join(root, path); const info = await lstat(absolute);
    if (entries.length >= 64 || !info.isFile() || info.size > 1024 ** 2 || await realpath(absolute) !== absolute) throw new Error('fixture_class_invalid');
    entries.push({ path, sha256: hash(await readFile(absolute)) });
  }
  if (!entries.some(e => e.path.endsWith('/BenchmarkRetainedFixture.class')) || !entries.some(e => e.path.endsWith('/BenchmarkRetainedOwners.class'))) throw new Error('fixture_classes_unverified');
  return { fixture_classes_root: root, fixture_classes_sha256: classDigest(entries), entries };
}
async function classes(m: RetainedManifest) { const result = await fixtureClassReceipt(m.fixture_classes_root); if (result.fixture_classes_sha256 !== m.fixture_classes_sha256) throw new Error('fixture_classes_unverified'); return result.entries; }
async function write(path: string, bytes: string) { const file = await open(path, 'wx', 0o600); try { await file.writeFile(bytes); await file.sync(); } finally { await file.close(); } if (hash(await readFile(path)) !== hash(bytes)) throw new Error('retained_receipt_write_failed'); }
function measurement(value: any, pid: number, mode: 'local' | 'minimal', source: string): RetainedMeasurement {
  if (value?.process_id !== pid || value?.producer_paused !== true || !['ready', 'paused'].includes(value.status) || value.identity?.build !== `source:${source}`) throw new Error('retained_fixture_identity_mismatch');
  const m = value.measurement;
  for (const key of ['virtual_ms', 'produced_hours', 'requests', 'observation_reserved_bytes', 'observation_limit_bytes', 'work_leased_bytes', 'work_limit_bytes', 'composition_reserved_bytes', 'query_count', 'query_refused', 'cursor_count']) if (!Number.isSafeInteger(m?.[key]) || m[key] < 0) throw new Error('retained_fixture_counter_missing');
  if (m.mode !== mode || !Array.isArray(m.sinks) || m.sinks.length > 16) throw new Error('retained_fixture_shape_invalid');
  for (const sink of m.sinks) for (const key of ['failed', 'dropped', 'pending']) if (!Number.isSafeInteger(sink[key]) || sink[key] < 0) throw new Error('retained_fixture_sink_invalid');
  return m;
}
async function probe(command: string[], assertOwned: () => Promise<void>, limit = 4 * 1024 ** 2) {
  await assertOwned(); const started = performance.now();
  const child = Bun.spawn(command, { stdout: 'pipe', stderr: 'pipe', env: { PATH: process.env.PATH ?? '/usr/bin:/bin' } });
  const timer = setTimeout(() => child.kill('SIGKILL'), 60000);
  try {
    const [stdout, stderr, exit] = await Promise.all([boundedBody(new Response(child.stdout), limit), boundedBody(new Response(child.stderr), 65536), child.exited]);
    if (exit !== 0 || stderr.length) throw new Error('retained_probe_failed'); await assertOwned();
    return { text: new TextDecoder('utf-8', { fatal: true }).decode(stdout), elapsed_ms: performance.now() - started };
  } finally { clearTimeout(timer); if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL'); }
}
async function pointDisk(root: string) {
  const result: Record<string, number> = {};
  for (const [name, path] of Object.entries({ aggregate: 'perf-aggregates', evidence: 'perf-evidence', cursor: 'perf-evidence-cursors' })) result[name] = await diskSize(join(root, path), root);
  return result;
}
async function runProcess(run: RetainedRun, pair: number, mode: 'local' | 'minimal', output: string, start: number) {
  const m = run.manifest, root = join(output, `pair-${pair}-${mode}`);
  for (const name of ['home', 'tmp', 'state']) await mkdir(join(root, name), { recursive: true, mode: 0o700 });
  const java = run.java_executable; const jcmd = join(dirname(java), 'jcmd');
  if (hash(await readFile(java)) !== run.java_sha256) throw new Error('retained_java_changed');
  const args = [java, ...retainedProtocol.java_flags, `-Duser.home=${join(root, 'home')}`, `-Djava.io.tmpdir=${join(root, 'tmp')}`, '-cp', `${m.artifact_path}:${m.fixture_classes_root}`, mainClass, root, mode, String(start), m.source_commit];
  const process = Bun.spawn(args, { cwd: root, stdin: 'pipe', stdout: 'pipe', stderr: Bun.file(join(root, 'fixture.stderr')), env: { PATH: '/usr/bin:/bin', SPLICE_STATE_DIR: join(root, 'state'), XDG_CONFIG_HOME: join(root, 'home/config'), XDG_DATA_HOME: join(root, 'home/data'), XDG_CACHE_HOME: join(root, 'home/cache'), XDG_STATE_HOME: join(root, 'home/state') } });
  const reader = process.stdout.getReader();
  try {
    const ticks = await startTicks(process.pid);
    const assertOwned = async () => {
      if (process.exitCode !== null || process.signalCode !== null || await startTicks(process.pid) !== ticks) throw new Error('retained_process_lost');
      const actual = (await readFile(`/proc/${process.pid}/cmdline`, 'utf8')).split('\0').filter(Boolean);
      if (JSON.stringify(actual) !== JSON.stringify(args) || await realpath(`/proc/${process.pid}/exe`) !== java) throw new Error('retained_process_cmdline_changed');
    };
    run.process_bindings.push({ pid: process.pid, start_ticks: ticks, args, pair, mode, verification: 'spawned-child-start-ticks-exact-cmdline' });
    let buffer = '';
    const line = async () => {
      while (!buffer.includes('\n')) { const next = await reader!.read(); if (next.done) throw new Error('retained_fixture_exited'); buffer += new TextDecoder().decode(next.value); if (buffer.length > 256 * 1024) throw new Error('retained_fixture_reply_limit'); }
      const at = buffer.indexOf('\n'), raw = buffer.slice(0, at); buffer = buffer.slice(at + 1); return measurement(JSON.parse(raw), process.pid, mode, m.source_commit);
    };
    const exchange = async (command?: string) => {
      await assertOwned(); if (command) { process.stdin.write(command + '\n'); await process.stdin.flush(); }
      let timeout: ReturnType<typeof setTimeout> | undefined;
      try { const result = await Promise.race([line(), new Promise<never>((_, reject) => { timeout = setTimeout(() => reject(new Error('retained_fixture_timeout')), 20 * 60000); })]); await assertOwned(); return result; } finally { clearTimeout(timeout); }
    };
    await exchange(); await exchange(`advance ${retainedProtocol.fill_hours}`);
    // Prime attach/JVM diagnostic classes before measured post-fill points; neither probe is in inference timing.
    await probe([jcmd, String(process.pid), 'GC.run'], assertOwned);
    await probe([jcmd, String(process.pid), 'GC.class_histogram'], assertOwned);
    for (let point = 0; point < retainedProtocol.post_points; point++) {
      await exchange(`advance ${retainedProtocol.step_hours}`);
      const gc = await probe([jcmd, String(process.pid), 'GC.run'], assertOwned);
      if (!gc.text.includes('Command executed successfully')) throw new Error('retained_gc_unconfirmed');
      const histogram = await probe([jcmd, String(process.pid), 'GC.class_histogram'], assertOwned);
      const heap = parseHistogram(histogram.text); const histogram_path = join(root, `histogram-${point}.txt`); await write(histogram_path, histogram.text);
      const fixture = await exchange('sample'); const disk = await pointDisk(join(root, 'state')); await assertOwned();
      run.points.push({ pair, mode, point, pid: process.pid, start_ticks: ticks, fixture, heap, histogram_sha256: hash(histogram.text), histogram_path, probe_ms: gc.elapsed_ms + histogram.elapsed_ms, disk });
    }
    process.stdin.write('stop\n'); await process.stdin.flush(); process.stdin.end();
    const timer = setTimeout(() => process.kill('SIGKILL'), 10000); try { if (await process.exited !== 0) throw new Error('retained_fixture_shutdown_failed'); } finally { clearTimeout(timer); }
  } finally { if (process.exitCode === null && process.signalCode === null) process.kill('SIGKILL'); await process.exited; await reader?.cancel(); }
}
export async function runRetained(m: RetainedManifest, directory: string, authorizedFreeze: string) {
  if (m.schema_version !== 1 || authorizedFreeze !== m.source_commit || JSON.stringify(m.protocol) !== JSON.stringify(retainedProtocol)) throw new Error('retained_freeze_protocol_required');
  const identity = await verifyArtifact(artifactManifest(m)); const fixtureClasses = await classes(m);
  m = { ...m, artifact_path: identity.artifact_path, fixture_classes_root: await realpath(m.fixture_classes_root) };
  const output = resolve(directory); if (!output.startsWith('/tmp/splice-instrumentation-')) throw new Error('retained_task_directory_required');
  await mkdir(output, { mode: 0o700 }); if (await realpath(output) !== output) throw new Error('retained_output_symlink');
  const java = await realpath(Bun.which('java') ?? '/missing/java');
  const version = Bun.spawn([java, '-version'], { stdout: 'pipe', stderr: 'pipe', env: { PATH: '/usr/bin:/bin' } });
  const versionTimer = setTimeout(() => version.kill('SIGKILL'), 10000);
  let jvm: string;
  try {
    const [stdout, stderr, exit] = await Promise.all([boundedBody(new Response(version.stdout), 65536), boundedBody(new Response(version.stderr), 65536), version.exited]);
    jvm = new TextDecoder('utf-8', { fatal: true }).decode(stderr).trim();
    if (exit !== 0 || stdout.length || !jvm) throw new Error('retained_java_version_unverified');
  } finally { clearTimeout(versionTimer); if (version.exitCode === null && version.signalCode === null) version.kill('SIGKILL'); }
  const run: RetainedRun = { schema_version: 1, status: 'partial', error_class: null, manifest: m, environment: environment(), jvm, java_executable: java, java_sha256: hash(await readFile(java)), points: [], process_bindings: [], artifact_receipt_sha256: identity.artifact_receipt_sha256, fixture_classes: fixtureClasses, measured_at: new Date().toISOString(), histogram_command: 'owned PID GC.run then GC.class_histogram; no -all; no heap dump', harness_sha256: hash(await Bun.file(import.meta.path).text() + await Bun.file(join(import.meta.dir, 'retained-model.ts')).text()) };
  const start = Math.floor((Date.now() - 50 * 86400000) / 3600000) * 3600000;
  try {
    for (let pair = 0; pair < retainedProtocol.repetitions; pair++) for (const mode of pair % 2 ? ['local', 'minimal'] as const : ['minimal', 'local'] as const) { await verifyArtifact(artifactManifest(m)); await classes(m); await runProcess(run, pair, mode, output, start); }
    run.status = 'complete';
  } catch (error) { run.error_class = error instanceof Error && /^[a-z0-9_]+$/.test(error.message) ? error.message : 'retained_experiment_failed'; }
  const raw = JSON.stringify(run, null, 2) + '\n'; await write(join(output, 'retained.json'), raw); await write(join(output, 'retained.sha256'), hash(raw));
  const result = compareRetained(run); await write(join(output, 'comparison.json'), JSON.stringify(result, null, 2) + '\n'); return result;
}
export async function readRetainedEvidence(directory: string, candidate: { source_commit: string; artifact_sha256: string }) {
  const root = await realpath(directory); if (!root.startsWith('/tmp/splice-instrumentation-')) throw new Error('retained_receipt_path_invalid');
  const file = Bun.file(join(root, 'retained.json')); if (file.size > 8 * 1024 ** 2) throw new Error('retained_receipt_limit');
  const raw = await file.text(); if (hash(raw) !== (await Bun.file(join(root, 'retained.sha256')).text()).trim()) throw new Error('retained_receipt_digest');
  const run: RetainedRun = JSON.parse(raw);
  if (typeof run.jvm !== 'string' || !run.jvm || hash(await readFile(run.java_executable)) !== run.java_sha256) throw new Error('retained_java_binding');
  if (run.manifest.source_commit !== candidate.source_commit || run.manifest.artifact_sha256 !== candidate.artifact_sha256 || (await verifyArtifact(artifactManifest(run.manifest))).artifact_receipt_sha256 !== run.artifact_receipt_sha256 || classDigest(await classes(run.manifest)) !== classDigest(run.fixture_classes)) throw new Error('retained_artifact_binding');
  for (const point of run.points) {
    const path = await realpath(point.histogram_path); if (!path.startsWith(root + '/') || Bun.file(path).size > 4 * 1024 ** 2) throw new Error('retained_histogram_path');
    const text = await Bun.file(path).text(); if (hash(text) !== point.histogram_sha256 || JSON.stringify(parseHistogram(text)) !== JSON.stringify(point.heap)) throw new Error('retained_histogram_changed');
    const binding = run.process_bindings.find(b => b.pair === point.pair && b.mode === point.mode && b.pid === point.pid && b.start_ticks === point.start_ticks);
    const processRoot = join(root, `pair-${point.pair}-${point.mode}`);
    if (binding?.verification !== 'spawned-child-start-ticks-exact-cmdline' || binding.args[0] !== run.java_executable || !binding.args.includes(mainClass) || !binding.args.includes(`${run.manifest.artifact_path}:${run.manifest.fixture_classes_root}`) || !binding.args.includes(`-Duser.home=${join(processRoot, 'home')}`) || !binding.args.includes(`-Djava.io.tmpdir=${join(processRoot, 'tmp')}`) || retainedProtocol.java_flags.some(flag => !binding.args.includes(flag))) throw new Error('retained_process_binding');
  }
  return { ...compareRetained(run), environment: run.environment, jvm: run.jvm, java_flags: run.manifest.protocol.java_flags, artifact_sha256: run.manifest.artifact_sha256 };
}
if (import.meta.main) {
  try {
    const [command, manifest, output, authorizedFreeze] = process.argv.slice(2);
    if (command === 'classes') { if (!manifest) throw new Error('fixture_classes_path_missing'); console.log(JSON.stringify(await fixtureClassReceipt(manifest), null, 2)); }
    else if (command !== 'run') console.log(JSON.stringify({ measured: false, protocol: retainedProtocol, usage: 'bun tools/instrumentation-bench/retained.ts run manifest.json /tmp/splice-instrumentation-.../retained <authorized-frozen-source-sha>', prerequisite: 'root gate/freeze plus immutable task fixture classes; never run during inference measurements' }, null, 2));
    else { if (!manifest || !output || !authorizedFreeze) throw new Error('retained_arguments_missing'); const result = await runRetained(await Bun.file(manifest).json(), output, authorizedFreeze); console.log(JSON.stringify(result)); if (result.status !== 'pass') process.exitCode = 2; }
  } catch (error) { console.error(error instanceof Error && /^[a-z0-9_]+$/.test(error.message) ? error.message : 'retained_failed'); process.exitCode = 2; }
}
