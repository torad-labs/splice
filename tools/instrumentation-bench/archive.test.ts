import { expect, test } from 'bun:test';
import { createHash } from 'node:crypto';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { copyFile, mkdir, mkdtemp, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { archiveRun, restoreArchive, verifyArchive } from './archive';

const hash = (bytes: string | Uint8Array) => createHash('sha256').update(bytes).digest('hex');
const deadProcessGroup = 2_000_000_000;

async function fixture(partial = false) {
  const root = await mkdtemp(join(tmpdir(), 'splice-instrumentation-archive-test-'));
  const artifactPath = join(root, 'frozen', 'splice.jar'); await mkdir(join(root, 'frozen'));
  const artifactBytes = Buffer.from('frozen-artifact-bytes\n'.repeat(16_000)); await writeFile(artifactPath, artifactBytes, { mode: 0o600 });
  const artifact = { path: artifactPath, sha256: hash(artifactBytes) };
  const output = join(root, 'run'); await mkdir(output, { mode: 0o700 });
  const manifest = { schema_version: 1, experiment_stage: 'steady', run_id: `archive-test-${partial ? 'candidate' : 'baseline'}`, role: partial ? 'candidate' : 'baseline',
    source_commit: 'a'.repeat(40), artifact_sha256: artifact.sha256, process_build: 'test-build', target: { kind: 'proxy', artifact_path: artifactPath },
    repetitions: 5, retained_history: partial ? { query_source: 'history' } : undefined };
  const manifestBytes = JSON.stringify(manifest, null, 2) + '\n';
  const samples = '{"sequence":0}\n', observations = '{"repetitions":[]}\n';
  const summary = JSON.stringify({ status: partial ? 'partial' : 'complete', partial_reason: partial ? 'history_study_partial' : null }) + '\n';
  await Promise.all([
    writeFile(join(output, 'manifest.json'), manifestBytes, { mode: 0o600 }),
    writeFile(join(output, 'samples.jsonl'), samples, { mode: 0o600 }),
    writeFile(join(output, 'observations.json'), observations, { mode: 0o600 }),
    writeFile(join(output, 'summary.json'), summary, { mode: 0o600 }),
  ]);
  await writeFile(join(output, 'receipt.json'), JSON.stringify({ schema_version: 1, manifest_sha256: hash(manifestBytes),
    samples_sha256: hash(samples), observations_sha256: hash(observations), sample_count: 1 }, null, 2) + '\n', { mode: 0o600 });
  const workerJar = join(output, 'desk-0', 'state', 'worker-artifacts', '123-456', `${artifact.sha256}.jar`);
  await mkdir(join(output, 'desk-0', 'state', 'worker-artifacts', '123-456'), { recursive: true });
  await copyFile(artifactPath, workerJar);
  await mkdir(join(output, 'desk-0', 'state', 'trace'), { recursive: true });
  await writeFile(join(output, 'desk-0', 'state', 'trace', 'events.jsonl'), 'event\n'.repeat(5000), { mode: 0o600 });
  await mkdir(join(output, 'desk-1', 'tmp'), { recursive: true }); // Mimics a cold repetition's second daemon directory.
  await writeFile(join(output, 'desk-1', 'tmp', 'empty.log'), '', { mode: 0o600 });
  return { root, output, artifact, artifactBytes, samples, workerJar };
}

test('archives only closed proxy desk state, deduplicates exact artifact bytes and restores them', async () => {
  const f = await fixture();
  try {
    const receipt = await archiveRun(f.output, { artifact: f.artifact, processGroup: deadProcessGroup });
    expect(receipt.status).toBe('archived');
    expect(receipt.measured_status).toBe('complete');
    expect(receipt.deduplicated_artifacts).toBe(1);
    expect(receipt.deduplicated_artifact_bytes).toBe(f.artifactBytes.length);
    expect(await readFile(join(f.output, 'samples.jsonl'), 'utf8')).toBe(f.samples);
    expect(await verifyArchive(f.output)).toEqual(receipt);
    await expect(readFile(join(f.output, 'desk-0', 'state', 'trace', 'events.jsonl'))).rejects.toThrow();

    expect(await restoreArchive(f.output, { artifact: f.artifact })).toEqual(receipt);
    expect(await readFile(f.workerJar)).toEqual(f.artifactBytes);
    expect(await readFile(join(f.output, 'desk-0', 'state', 'trace', 'events.jsonl'), 'utf8')).toBe('event\n'.repeat(5000));
    await rm(join(f.output, 'desk-0'), { recursive: true }); // Simulate interruption during multi-root raw cleanup.
    const restored = join(f.root, 'restored-after-partial-cleanup');
    expect(await restoreArchive(f.output, { artifact: f.artifact, destination: restored })).toEqual(receipt);
    expect(await readFile(join(restored, 'desk-0', 'state', 'trace', 'events.jsonl'), 'utf8')).toBe('event\n'.repeat(5000));
    expect(await readFile(join(restored, 'desk-1', 'tmp', 'empty.log'))).toEqual(Buffer.alloc(0));
    expect(await readFile(join(f.output, 'desk-1', 'tmp', 'empty.log'))).toEqual(Buffer.alloc(0));
  } finally { await rm(f.root, { recursive: true, force: true }); }
});

test('preserves the expected partial-history classification', async () => {
  const f = await fixture(true);
  try {
    const receipt = await archiveRun(f.output, { artifact: f.artifact, processGroup: deadProcessGroup });
    expect(receipt.status).toBe('archived');
    expect(receipt.measured_status).toBe('partial');
    expect(receipt.partial_reason).toBe('history_study_partial');
    expect(await verifyArchive(f.output)).toEqual(receipt);
  } finally { await rm(f.root, { recursive: true, force: true }); }
});

test('refuses symlink traversal without removing raw state', async () => {
  const f = await fixture();
  try {
    const link = join(f.output, 'desk-0', 'state', 'trace', 'external');
    await symlink(f.artifact.path, link);
    await expect(archiveRun(f.output, { artifact: f.artifact, processGroup: deadProcessGroup })).rejects.toThrow('desk_symlink_or_unsafe_path');
    expect(await readFile(link)).toEqual(f.artifactBytes);
    expect(await readFile(join(f.output, 'samples.jsonl'), 'utf8')).toBe(f.samples);
  } finally { await rm(f.root, { recursive: true, force: true }); }
});

test('compresses nonmatching worker jars and rejects tampered payloads before restore', async () => {
  const f = await fixture();
  try {
    const nonmatching = Buffer.from('different-worker-artifact\n'.repeat(3000));
    await writeFile(f.workerJar, nonmatching, { mode: 0o600 });
    const receipt = await archiveRun(f.output, { artifact: f.artifact, processGroup: deadProcessGroup });
    expect(receipt.deduplicated_artifacts).toBe(0);
    expect(receipt.deduplicated_artifact_bytes).toBe(0);
    const manifestPath = join(receipt.archive_directory, 'manifest.json');
    const archiveManifest = JSON.parse(await readFile(manifestPath, 'utf8'));
    const jarEntry = archiveManifest.files.find((row: { path: string }) => row.path.endsWith('.jar'));
    expect(jarEntry.artifact_reference).toBeUndefined();
    const payloadPath = join(receipt.archive_directory, jarEntry.stored_as);
    const validPayload = await readFile(payloadPath);
    const damagedPayload = Buffer.from(validPayload), damagedIndex = Math.floor(damagedPayload.length / 2);
    damagedPayload[damagedIndex] = (damagedPayload[damagedIndex] ?? 0) ^ 0xff;
    await writeFile(payloadPath, damagedPayload, { mode: 0o600 });
    await expect(verifyArchive(f.output)).rejects.toThrow();
    await expect(restoreArchive(f.output, { artifact: f.artifact, destination: join(f.root, 'tampered-restore') })).rejects.toThrow();
    await writeFile(payloadPath, validPayload, { mode: 0o600 });
    expect(await verifyArchive(f.output)).toEqual(receipt);
    const restored = join(f.root, 'restored-nonmatching-artifact');
    expect(await restoreArchive(f.output, { artifact: f.artifact, destination: restored })).toEqual(receipt);
    expect(await readFile(join(restored, 'desk-0', 'state', 'worker-artifacts', '123-456', `${f.artifact.sha256}.jar`))).toEqual(nonmatching);
  } finally { await rm(f.root, { recursive: true, force: true }); }
});

test('refuses a live process group without touching desk state', async () => {
  const f = await fixture();
  const child = spawn('/bin/sleep', ['10'], { detached: true, stdio: 'ignore' });
  try {
    await once(child, 'spawn');
    await expect(archiveRun(f.output, { artifact: f.artifact, processGroup: child.pid! })).rejects.toThrow('benchmark_process_group_still_alive');
    expect(await readFile(join(f.output, 'desk-0', 'state', 'trace', 'events.jsonl'), 'utf8')).toBe('event\n'.repeat(5000));
  } finally {
    if (child.pid) { const exited = once(child, 'exit'); try { process.kill(-child.pid, 'SIGTERM'); } catch {} await exited; }
    await rm(f.root, { recursive: true, force: true });
  }
});
