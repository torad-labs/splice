#!/usr/bin/env bun
import { spawn } from 'node:child_process';
import { mkdir, open, readFile, readdir, realpath, statfs } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { archiveRun, verifyArchive } from './archive';
import { readRun } from './analyze';
import { hash, validate, type Manifest } from './manifest';

const GIB = 1024 ** 3;
const LOG_LIMIT = 16 * 1024 ** 2;
const json = (value: unknown) => JSON.stringify(value, null, 2) + '\n';
const now = () => new Date().toISOString();
function demand(value: unknown, reason: string): asserts value { if (!value) throw new Error(reason); }
async function writeNew(path: string, value: unknown) {
  const file = await open(path, 'wx', 0o600);
  try { await file.writeFile(json(value)); await file.sync(); } finally { await file.close(); }
}
async function harnessIdentity() {
  const files = (await readdir(import.meta.dir)).filter(name => name.endsWith('.ts')).sort();
  return Object.fromEntries(await Promise.all(files.map(async name => [name, hash(await readFile(join(import.meta.dir, name)))])));
}
async function capacity(path: string, floorGiB: number) {
  const info = await statfs(path, { bigint: true });
  return { checked_at: now(), free_bytes: Number(info.bavail * info.bsize), floor_bytes: floorGiB * GIB };
}
function groupAlive(pid: number) {
  try { process.kill(-pid, 0); return true; }
  catch (error) { if ((error as NodeJS.ErrnoException).code === 'ESRCH') return false; throw error; }
}
function signalGroup(pid: number, signal: NodeJS.Signals) {
  try { process.kill(-pid, signal); }
  catch (error) { if ((error as NodeJS.ErrnoException).code !== 'ESRCH') throw error; }
}
async function closeGroup(pid: number) {
  if (!groupAlive(pid)) return true;
  signalGroup(pid, 'SIGTERM');
  for (let attempt = 0; attempt < 30 && groupAlive(pid); attempt++) await Bun.sleep(100);
  if (groupAlive(pid)) signalGroup(pid, 'SIGKILL');
  for (let attempt = 0; attempt < 20 && groupAlive(pid); attempt++) await Bun.sleep(100);
  return !groupAlive(pid);
}
async function runChild(manifest: Manifest, manifestPath: string, output: string, logs: string) {
  const stdout = await open(join(logs, 'stdout.log'), 'wx', 0o600);
  const stderr = await open(join(logs, 'stderr.log'), 'wx', 0o600);
  const timeoutMs = manifest.repetitions * (manifest.warmup_ms + manifest.duration_ms
    + 2 * manifest.generator_limits.timeout_ms + 45_000) + 120_000;
  let reason: string | null = null;
  let ended = false;
  let child: ReturnType<typeof spawn> | undefined;
  let killer: ReturnType<typeof setTimeout> | undefined;
  let monitor: ReturnType<typeof setInterval> | undefined;
  const stop = (why: string) => {
    if (ended) return;
    reason ??= why;
    if (!child?.pid || killer) return;
    signalGroup(child.pid, 'SIGTERM');
    killer = setTimeout(() => { if (child?.pid) signalGroup(child.pid, 'SIGKILL'); }, 3000);
  };
  const interrupted = () => stop('interrupted');
  process.on('SIGINT', interrupted); process.on('SIGTERM', interrupted);
  let timeout: ReturnType<typeof setTimeout> | undefined;
  try {
    child = spawn(process.execPath, [join(import.meta.dir, 'run.ts'), 'run', '--manifest', manifestPath, '--output', output],
      { detached: true, stdio: ['ignore', stdout.fd, stderr.fd] });
    const exited = new Promise<{ exit_code: number | null; signal: string | null }>((resolveExit, reject) => {
      child!.once('error', reject);
      child!.once('exit', (exit_code, signal) => resolveExit({ exit_code, signal }));
    });
    // Attach rejection handling before the ownership receipt's asynchronous write.
    void exited.catch(() => {});
    await writeNew(join(logs, 'started.json'), { pid: child.pid ?? null, process_group: child.pid ?? null,
      started_at: now(), timeout_ms: timeoutMs });
    if (reason) stop(reason);
    timeout = setTimeout(() => stop('run_timeout'), timeoutMs);
    let checking = false;
    monitor = setInterval(() => {
      if (checking) return;
      checking = true;
      void Promise.all([stdout.stat(), stderr.stat(), capacity(logs, 1)]).then(([out, err, disk]) => {
        if (out.size > LOG_LIMIT || err.size > LOG_LIMIT) stop('run_log_limit');
        if (disk.free_bytes < disk.floor_bytes) stop('run_disk_reserve');
      }).catch(() => stop('run_monitor_failed')).finally(() => { checking = false; });
    }, 1000);
    const result = await exited;
    ended = true;
    if (killer) clearTimeout(killer);
    demand(child.pid && child.pid > 1, 'child_identity_missing');
    const closed = await closeGroup(child.pid);
    const finalLogs = await Promise.all([stdout.stat(), stderr.stat()]);
    if (finalLogs.some(info => info.size > LOG_LIMIT)) reason ??= 'run_log_limit';
    return { ...result, process_group: child.pid, group_closed: closed, reason, finished_at: now() };
  } finally {
    ended = true;
    if (timeout) clearTimeout(timeout);
    if (monitor) clearInterval(monitor);
    if (killer) clearTimeout(killer);
    process.off('SIGINT', interrupted); process.off('SIGTERM', interrupted);
    if (child?.pid) await closeGroup(child.pid);
    await stdout.close(); await stderr.close();
  }
}

/** Runs complete roles serially; verified desk archives replace expanded state between roles. */
export async function serial(manifestPaths: string[], directory: string) {
  demand(process.platform === 'linux', 'linux_process_group_required');
  demand(manifestPaths.length > 0 && manifestPaths.length <= 100, 'one_to_100_manifests_required');
  const manifests = await Promise.all(manifestPaths.map(async path => {
    const file = Bun.file(path); demand(file.size <= 1024 ** 2, 'manifest_too_large');
    const manifest = validate(await file.json());
    demand(manifest.experiment_stage === 'steady' && manifest.repetitions >= 5, 'steady_five_repetition_run_required');
    return manifest;
  }));
  const root = resolve(directory), parent = dirname(root);
  demand(/^\/tmp\/splice-instrumentation-[A-Za-z0-9_./-]+$/.test(root), 'private_task_output_required');
  demand(await realpath(parent) === parent, 'output_parent_symlink');
  await mkdir(root, { mode: 0o700 });
  const identity = await harnessIdentity();
  await writeNew(join(root, 'plan.json'), { created_at: now(), measured: false, harness: identity, manifests,
    storage: 'One expanded role at a time; verified archives and evidence accumulate. No fixed batch-space requirement.' });
  for (const [index, manifest] of manifests.entries()) {
    demand(json(await harnessIdentity()) === json(identity), 'harness_changed');
    const job = join(root, String(index).padStart(3, '0'));
    await mkdir(job, { mode: 0o700 });
    const available = await capacity(root, manifest.target.kind === 'proxy' ? 64 : 1);
    await writeNew(join(job, 'capacity.json'), available);
    demand(available.free_bytes >= available.floor_bytes, 'launch_disk_floor_not_met');
    const input = join(job, 'input.json'), output = join(job, 'run');
    await writeNew(input, manifest);
    const execution = await runChild(manifest, input, output, job);
    await writeNew(join(job, 'execution.json'), execution);
    demand(execution.exit_code === 0 && !execution.signal && !execution.reason && execution.group_closed, 'run_incomplete');
    demand(json(await harnessIdentity()) === json(identity), 'harness_changed');
    const run = await readRun(output);
    demand(run.summary.status === 'complete', 'run_incomplete');
    if (manifest.target.kind === 'proxy') {
      const archive = await archiveRun(output, { artifact: { path: manifest.target.artifact_path!, sha256: manifest.artifact_sha256 },
        processGroup: execution.process_group });
      demand(archive.status === 'archived' && json(await verifyArchive(output)) === json(archive), 'archive_not_verified');
      await writeNew(join(job, 'storage.json'), { outside_measurement: true, archive });
    }
    await writeNew(join(job, 'completed.json'), { status: 'workload_complete_analysis_pending', finished_at: now() });
  }
  await writeNew(join(root, 'completed.json'), { status: 'workloads_complete_analysis_pending', finished_at: now(), jobs: manifests.length });
}

if (import.meta.main) {
  try {
    const args = process.argv.slice(2), manifests: string[] = []; let output: string | undefined;
    while (args.length) {
      const name = args.shift(), value = args.shift(); demand(value && !value.startsWith('--'), 'option_value_required');
      if (name === '--manifest') manifests.push(value);
      else { demand(name === '--output' && !output, 'invalid_option'); output = value; }
    }
    demand(output, 'output_required'); await serial(manifests, output);
  } catch (error) {
    const message = error instanceof Error ? error.message : '';
    console.error(json({ status: 'incomplete', error_class: /^[a-z0-9_]+$/.test(message) ? message : 'invalid_input_or_io' }));
    process.exitCode = 2;
  }
}
