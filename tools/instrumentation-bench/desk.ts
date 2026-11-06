import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { mkdir, readFile, readdir, realpath, stat } from 'node:fs/promises';
import { isAbsolute, join } from 'node:path';
import { hash, loopback, type Manifest } from './manifest';
import { verifyArtifact, assertProcessArtifact, assertProcessIdentity, waitForOwnedHealth, type DeskIdentity } from './identity';
import { installHistory } from './history';
import { boundedBody } from './control';
export interface JavaRuntime { executable: string; sha256: string; version: string }
type DeskProcess = Pick<ReturnType<typeof Bun.spawn>, 'exitCode' | 'signalCode' | 'exited' | 'kill' | 'unref'>;
async function executableHash(path: string) {
  const digest = createHash('sha256');
  for await (const bytes of createReadStream(path)) digest.update(bytes);
  return digest.digest('hex');
}
async function selectJava(selection: Manifest['java']) {
  if (selection !== undefined && (!selection || typeof selection.executable !== 'string' || !isAbsolute(selection.executable) || !/^[a-f0-9]{64}$/.test(selection.sha256))) throw new Error('invalid_java_selection');
  const selected = selection?.executable ?? Bun.which('java');
  if (!selected) throw new Error('java_executable_unavailable');
  const executable = await realpath(selected), info = await stat(executable);
  if (!info.isFile() || (info.mode & 0o111) === 0) throw new Error('java_executable_invalid');
  const sha256 = await executableHash(executable);
  if (selection && selection.sha256 !== sha256) throw new Error('java_sha256_mismatch');
  return { executable, sha256 };
}
async function assertJava(pid: number, java: Pick<JavaRuntime, 'executable' | 'sha256'>) {
  const running = `/proc/${pid}/exe`;
  if (await realpath(running) !== java.executable || await executableHash(running) !== java.sha256) throw new Error('process_java_mismatch');
}
async function exitedWithin(proc: DeskProcess, ms: number) {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try { return await Promise.race([proc.exited.then(() => true), new Promise<boolean>(done => { timer = setTimeout(() => done(false), ms); })]); }
  finally { clearTimeout(timer); }
}
async function stopProcess(proc: DeskProcess) {
  if (proc.exitCode !== null || proc.signalCode !== null) return;
  proc.kill('SIGTERM');
  if (await exitedWithin(proc, 10000)) return;
  if (proc.exitCode === null && proc.signalCode === null) proc.kill('SIGKILL');
  if (!await exitedWithin(proc, 1000)) { proc.unref(); throw new Error('isolated_desk_cleanup_incomplete'); }
}
async function javaVersion(java: Pick<JavaRuntime, 'executable' | 'sha256'>, flags: string[], cwd: string, env: Record<string, string>) {
  const proc = Bun.spawn([java.executable, ...flags, '-version'], { cwd, env, stdin: 'ignore', stdout: 'pipe', stderr: 'pipe' });
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    const [stdout, stderr, code] = await Promise.race([
      Promise.all([boundedBody(new Response(proc.stdout), 64 * 1024), boundedBody(new Response(proc.stderr), 64 * 1024), proc.exited]),
      new Promise<never>((_, reject) => { timer = setTimeout(() => reject(new Error('java_version_timeout')), 5000); }),
    ]);
    if (code !== 0 || await executableHash(java.executable) !== java.sha256) throw new Error('java_version_failed');
    const version = new TextDecoder().decode(Buffer.concat([stdout, stderr])).trim();
    if (!version) throw new Error('java_version_missing');
    return version;
  } finally { clearTimeout(timer); await stopProcess(proc); }
}
async function port() { const s = Bun.serve({ hostname: '127.0.0.1', port: 0, fetch: () => new Response() }); const p = s.port; await s.stop(true); if (p === undefined) throw new Error('isolated_port_unavailable'); return p; }
export async function startDesk(m: Manifest, upstream: string, directory: string, exporterEndpoint: string | null = null, javaSelection: Manifest['java'] = m.java) {
  if (m.target.kind === 'direct') return { target: `${upstream}/${m.protocol === 'anthropic' ? 'v1/messages' : 'v1/responses'}`, pid: null, bootId: 'unknown', jvm: 'not-applicable', java: null as JavaRuntime | null, processBuild: 'scripted-upstream-v1', configHash: hash(JSON.stringify({ protocol: m.protocol, collection: m.collection })), mgmtKey: '', pollBase: upstream, controlBase: upstream, state: directory, history: null, identity: null as DeskIdentity | null, assertIdentity: async () => {}, stop: async () => {} };
  const selectedJava = await selectJava(javaSelection);
  const artifactIdentity = await verifyArtifact(m); const artifact = artifactIdentity.artifact_path;
  await mkdir(directory, { recursive: true, mode: 0o700 }); directory = await realpath(directory);
  const deskHome = join(directory, 'home'), deskTmp = join(directory, 'tmp'), emptyPath = join(directory, 'empty-path');
  const state = join(directory, 'state');
  for (const path of [deskHome, state, deskTmp, emptyPath]) {
    await mkdir(path, { recursive: true, mode: 0o700 });
    if (await realpath(path) !== path || ((await stat(path)).mode & 0o077) !== 0) throw new Error('desk_private_directory_required');
  }
  if ((await readdir(emptyPath)).length !== 0) throw new Error('desk_empty_path_required');
  const history = m.retained_history ? await installHistory(m.retained_history, state) : null;
  const headPort = await port(); let controlPort = await port(); while (controlPort === headPort) controlPort = await port();
  const responses = m.upstream?.protocol === 'responses';
  loopback(`http://127.0.0.1:${headPort}`); loopback(`http://127.0.0.1:${controlPort}`);
  const candidateConfig = m.role === 'candidate' ? `[defaults]\nperf_collection_enabled = "${m.collection.mode === 'local'}"\n[telemetry.operator]\nprometheus_enabled = false\notlp_endpoint = ${JSON.stringify(exporterEndpoint ?? '')}\notlp_protocol = "http/protobuf"\notlp_headers = '{}'\notlp_signals = "${m.collection.exports ? 'metrics,traces,logs' : ''}"\n` : '';
  const baseConfig = `[daemon]\ncontrol_port = ${controlPort}\nstate_dir = "${state}"\nauto_update = false\n[providers.bench]\ndialect = "${responses ? 'openai-responses' : 'anthropic-passthrough'}"\nbase_url = "${upstream}"\nauth = ${responses ? '{ kind = "api-key", env = "SPLICE_BENCH_FIXTURE_KEY" }' : '{ kind = "client" }'}\n${responses ? `[providers.bench.quirks]\nwebsocket = ${m.upstream?.transport === 'websocket'}\nwebsocket_prefill = false\n` : ''}[[providers.bench.models]]\nid = "fixture-model"\ncontext_window = 2000000\n[heads.bench]\nprovider = "bench"\nport = ${headPort}\npinned_model = "fixture-model"\ndiscovery_prefix = "claude-bench--"\n`;
  const config = candidateConfig + baseConfig;
  const normalized = (value: string) => value.replaceAll(directory, '<desk>').replaceAll(String(headPort), '<head-port>').replaceAll(String(controlPort), '<control-port>').replaceAll(upstream, '<upstream>').replaceAll(exporterEndpoint ?? '<no-exporter>', '<exporter>');
  const configPath = join(directory, 'splice.toml'); await Bun.write(configPath, config);
  const env = { PATH: emptyPath, SPLICE_BENCH_FIXTURE_KEY: 'local-scripted-fixture', XDG_CONFIG_HOME: join(deskHome, '.config'), XDG_DATA_HOME: join(deskHome, '.local/share'), XDG_STATE_HOME: join(deskHome, '.local/state'), XDG_CACHE_HOME: join(deskHome, '.cache'), SPLICE_CONFIG: configPath, SPLICE_STATE_DIR: state };
  const flags = [`-Duser.home=${deskHome}`, `-Djava.io.tmpdir=${deskTmp}`, '-Xmx512m'];
  const java: JavaRuntime = { ...selectedJava, version: await javaVersion(selectedJava, flags, directory, env) };
  const log = Bun.file(join(directory, 'daemon.log'));
  const proc = Bun.spawn([java.executable, ...flags, '-jar', artifact, 'daemon'], {
    cwd: directory, env, stdin: 'ignore', stdout: log, stderr: log,
  });
  const stop = () => stopProcess(proc);
  try {
    await assertJava(proc.pid, java);
    const processIdentity = await waitForOwnedHealth(proc.pid, headPort, controlPort, () => proc.exitCode === null && proc.signalCode === null);
    await assertProcessArtifact(proc.pid, artifact);
    await verifyArtifact(m);
    const identity: DeskIdentity & { java: JavaRuntime } = { ...artifactIdentity, ...processIdentity, java, config_revision: hash(normalized(config)), base_config_sha256: hash(normalized(baseConfig)), raw_config_sha256: hash(config) };
    const assertIdentity = async () => { await assertProcessIdentity(identity, () => proc.exitCode === null && proc.signalCode === null); await assertProcessArtifact(proc.pid, artifact); await assertJava(proc.pid, java); };
    await assertIdentity();
    const keyFile = (await readdir(state)).find(name => name === 'mgmt-key');
    const mgmtKey = keyFile ? (await readFile(join(state, keyFile), 'utf8')).trim() : '';
    return { target: `http://127.0.0.1:${headPort}/v1/messages`, pid: proc.pid, bootId: 'unknown', jvm: java.version, java, processBuild: identity.process_build, configHash: identity.config_revision, identity, assertIdentity, mgmtKey, pollBase: `http://127.0.0.1:${headPort}`, controlBase: `http://127.0.0.1:${controlPort}`, state, history, stop };
  } catch (error) { await stop(); throw error; }
}
export async function resource(pid: number | null, state: string) {
  let cpuMs: number | null = null, rssBytes: number | null = null;
  if (pid !== null) try {
    const data = await readFile(`/proc/${pid}/stat`, 'utf8'); const fields = data.slice(data.lastIndexOf(')') + 2).split(' ');
    const ticks = Number(Bun.spawnSync(['getconf', 'CLK_TCK']).stdout.toString().trim());
    cpuMs = (Number(fields[11]) + Number(fields[12])) * 1000 / ticks;
    const status = await readFile(`/proc/${pid}/status`, 'utf8'); const rss = status.match(/^VmRSS:\s+(\d+)\s+kB/m); rssBytes = rss ? Number(rss[1]) * 1024 : null;
  } catch {}
  let files = 0;
  const size = async (path: string): Promise<number> => {
    if (++files > 20000) throw new Error('resource_scan_limit');
    const info = await stat(path); if (!info.isDirectory()) return info.size;
    let bytes = 0; for (const name of await readdir(path)) bytes += await size(join(path, name)); return bytes;
  };
  let diskBytes: number | null = null; try { diskBytes = await size(state); } catch {}
  return { cpu_ms: cpuMs, rss_bytes: rssBytes, disk_bytes: diskBytes };
}
