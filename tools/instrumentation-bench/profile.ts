import { createHash } from 'node:crypto';
import { mkdir, open } from 'node:fs/promises';
import { join } from 'node:path';
import { boundedBody, control, runtimeIdentity, type OwnedDesk, type RuntimeIdentity } from './control';
import { type Manifest } from './manifest';
import { sleep } from './fixture';

export interface ProfileReceipt {
  repetition: number; profile_id: string; status: string; error_class: string | null; identity: RuntimeIdentity;
  process_id: number; capture_from_ms: number | null; capture_until_ms: number | null; steady_from_ms: number | null; steady_until_ms: number | null;
  size_bytes: number | null; artifact_sha256: string | null; artifact_path: string | null;
  runtime_binding: { first: RuntimeIdentity | null; last: RuntimeIdentity | null; samples: number; source_commit: string; jar_sha256: string; process_start_ticks: string; config_sha256: string; config_scope: 'profile_and_runtime_revisions_reported_separately' };
  jfr: { status: string; chunks: number | null; duration_seconds: number | null; execution_samples: number | null; reason: string | null };
}
export function sameRuntimeOwner(profile: RuntimeIdentity, runtime: RuntimeIdentity) {
  return ![profile.bootId, profile.build, runtime.bootId, runtime.build].includes('unknown') && profile.bootId === runtime.bootId && profile.build === runtime.build && profile.version === runtime.version && profile.schemaVersion === runtime.schemaVersion;
}
async function jfrSummary(path: string) {
  const child = Bun.spawn(['jfr', 'summary', path], { stdout: 'pipe', stderr: 'pipe', env: { PATH: process.env.PATH ?? '/usr/bin:/bin' } });
  const timeout = setTimeout(() => child.kill(), 10000);
  try {
    const [out, stderr, exit] = await Promise.all([boundedBody(new Response(child.stdout), 64 * 1024), boundedBody(new Response(child.stderr), 64 * 1024), child.exited]);
    const stdout = new TextDecoder().decode(out);
    if (stdout.length + stderr.length > 128 * 1024 || exit !== 0) throw new Error('jfr_summary_failed');
    const read = (pattern: RegExp) => { const value = pattern.exec(stdout)?.[1]; return value === undefined ? null : Number(value); };
    const chunks = read(/Chunks:\s+(\d+)/), duration_seconds = read(/Duration:\s+(\d+)\s+s/), execution_samples = read(/jdk\.ExecutionSample\s+(\d+)/);
    if (chunks === null || chunks < 1 || duration_seconds === null || execution_samples === null) throw new Error('jfr_summary_incomplete');
    return { status: 'verified', chunks, duration_seconds, execution_samples, reason: null };
  } finally { clearTimeout(timeout); if (child.exitCode === null && child.signalCode === null) child.kill(); }
}
async function download(desk: OwnedDesk, id: string, directory: string, expectedBytes: number) {
  await desk.assertIdentity(); await mkdir(directory, { recursive: true, mode: 0o700 });
  const response = await fetch(`${desk.controlBase}/api/perf/profiles/${id}/artifact`, { headers: { authorization: `Bearer ${desk.mgmtKey}` }, redirect: 'error', signal: AbortSignal.timeout(30000) });
  const reader = response.body?.getReader();
  if (!response.ok || !reader) { await reader?.cancel(); throw new Error('profile_artifact_unavailable'); }
  const path = join(directory, `${id}.jfr`);
  let file: Awaited<ReturnType<typeof open>>;
  try { file = await open(path, 'wx', 0o600); } catch (error) { await reader.cancel(); throw error; }
  const digest = createHash('sha256'); let size = 0; let magic = Buffer.alloc(0);
  try {
    if (!response.ok || !reader) throw new Error('profile_artifact_unavailable');
    while (true) { const item = await reader.read(); if (item.done) break; size += item.value.byteLength;
      if (size > 64 * 1024 ** 2 || size > expectedBytes) throw new Error('profile_artifact_oversized');
      if (magic.length < 4) magic = Buffer.concat([magic, item.value.subarray(0, 4 - magic.length)]);
      digest.update(item.value); await file.writeFile(item.value);
    }
    await file.sync();
    if (size !== expectedBytes || size < 68 || !magic.equals(Buffer.from([0x46, 0x4c, 0x52, 0]))) throw new Error('profile_artifact_invalid');
    await desk.assertIdentity(); return { path, size, hash: digest.digest('hex') };
  } catch (error) { await reader?.cancel(); throw error; } finally { await file.close(); }
}
export async function startProfile(m: Manifest, desk: OwnedDesk, repetition: number, output: string) {
  if (!m.collection.profiles) return null;
  if (desk.pid === null || desk.identity === null) throw new Error('profile_owned_process_required');
  const capability = (await control(desk, '/api/perf/profiles/capabilities')).value;
  if (capability.status === 'busy' || capability.status === 'unsupported') throw new Error('profile_capability_unavailable');
  const durationSeconds = Math.min(300, Math.ceil((m.warmup_ms + m.duration_ms + m.generator_limits.timeout_ms) / 1000));
  const started = (await control(desk, '/api/perf/profiles', { method: 'POST', body: { profiler: 'jfr', mode: 'cpu', durationSeconds } })).value;
  const id = started.profile_id;
  if (typeof id !== 'string' || !/^[0-9a-f-]{36}$/.test(id) || started.status !== 'running' || started.process_id !== desk.pid) throw new Error('profile_start_unverified');
  const identity = runtimeIdentity(started.identity);
  const receipt: ProfileReceipt = { repetition, profile_id: id, status: 'running', error_class: null, identity, process_id: desk.pid,
    capture_from_ms: null, capture_until_ms: null, steady_from_ms: null, steady_until_ms: null, size_bytes: null, artifact_sha256: null, artifact_path: null,
    runtime_binding: { first: null, last: null, samples: 0, source_commit: desk.identity.source_commit, jar_sha256: desk.identity.artifact_sha256, process_start_ticks: desk.identity.start_ticks, config_sha256: desk.identity.raw_config_sha256, config_scope: 'profile_and_runtime_revisions_reported_separately' },
    jfr: { status: 'unavailable', chunks: null, duration_seconds: null, execution_samples: null, reason: 'not_downloaded' } };
  return { receipt, bindRuntime: (runtime: RuntimeIdentity | null) => {
    if (!runtime || !sameRuntimeOwner(identity, runtime) || receipt.runtime_binding.first && !sameRuntimeOwner(receipt.runtime_binding.first, runtime)) throw new Error('profile_runtime_identity_mismatch');
    receipt.runtime_binding.first ??= runtime; receipt.runtime_binding.last = runtime; receipt.runtime_binding.samples++;
  }, stop: async (fromMs: number, untilMs: number) => {
    receipt.steady_from_ms = fromMs; receipt.steady_until_ms = untilMs;
    try {
      if (receipt.runtime_binding.samples < 2 || !receipt.runtime_binding.first || !receipt.runtime_binding.last) throw new Error('profile_runtime_binding_missing');
      let result = (await control(desk, `/api/perf/profiles/${id}/stop`, { method: 'POST' })).value;
      const deadline = performance.now() + 20000;
      while (result.status === 'running' && performance.now() < deadline) { await sleep(100); result = (await control(desk, `/api/perf/profiles/${id}`)).value; }
      if (result.status !== 'completed' || result.artifact_available !== true || result.process_id !== desk.pid || JSON.stringify(runtimeIdentity(result.identity)) !== JSON.stringify(identity)) throw new Error('profile_terminal_identity_unverified');
      if (result.window_scope !== 'recording' || !Number.isSafeInteger(result.from_ms) || !Number.isSafeInteger(result.until_ms) || result.from_ms > fromMs || result.until_ms < untilMs) throw new Error('profile_interval_uncovered');
      if (!Number.isSafeInteger(result.size_bytes) || result.size_bytes <= 0 || result.size_bytes > 64 * 1024 ** 2) throw new Error('profile_artifact_size_invalid');
      receipt.capture_from_ms = result.from_ms; receipt.capture_until_ms = result.until_ms;
      const artifact = await download(desk, id, join(output, 'profiles'), result.size_bytes);
      receipt.size_bytes = artifact.size; receipt.artifact_sha256 = artifact.hash; receipt.artifact_path = artifact.path;
      receipt.jfr = await jfrSummary(artifact.path); receipt.status = 'verified';
    } catch (error) { receipt.status = 'partial'; receipt.error_class = error instanceof Error && /^[a-z_]+$/.test(error.message) ? error.message : 'profile_verification_failed'; }
  } };
}
