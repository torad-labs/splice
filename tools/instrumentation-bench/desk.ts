import { mkdir, readFile, readdir, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { hash, loopback, type Manifest } from './manifest';
import { verifyArtifact, assertProcessArtifact, assertProcessIdentity, waitForOwnedHealth, type DeskIdentity } from './identity';
async function port() { const s = Bun.serve({ hostname: '127.0.0.1', port: 0, fetch: () => new Response() }); const p = s.port; await s.stop(true); if (p === undefined) throw new Error('isolated_port_unavailable'); return p; }
export async function startDesk(m: Manifest, upstream: string, directory: string, exporterEndpoint: string | null = null) {
  if (m.target.kind === 'direct') return { target: `${upstream}/${m.protocol === 'anthropic' ? 'v1/messages' : 'v1/responses'}`, pid: null, bootId: 'unknown', jvm: 'not-applicable', processBuild: 'scripted-upstream-v1', configHash: hash(JSON.stringify({ protocol: m.protocol, collection: m.collection })), mgmtKey: '', pollBase: upstream, controlBase: upstream, state: directory, identity: null as DeskIdentity | null, assertIdentity: async () => {}, stop: async () => {} };
  const artifactIdentity = await verifyArtifact(m); const artifact = artifactIdentity.artifact_path;
  const deskHome = join(directory, 'home'); const state = join(directory, 'state'); await mkdir(deskHome, { recursive: true }); await mkdir(state, { recursive: true });
  const headPort = await port(); let controlPort = await port(); while (controlPort === headPort) controlPort = await port();
  loopback(`http://127.0.0.1:${headPort}`); loopback(`http://127.0.0.1:${controlPort}`);
  const candidateConfig = m.role === 'candidate' ? `[defaults]\nperf_collection_enabled = ${m.collection.mode === 'local'}\n[telemetry.operator]\nprometheus_enabled = false\notlp_endpoint = ${JSON.stringify(exporterEndpoint ?? '')}\notlp_protocol = "http/protobuf"\notlp_headers = '{}'\notlp_signals = "${m.collection.exports ? 'metrics,traces,logs' : ''}"\n` : '';
  const baseConfig = `[daemon]\ncontrol_port = ${controlPort}\nstate_dir = "${state}"\nauto_update = false\n[providers.bench]\ndialect = "anthropic-passthrough"\nbase_url = "${upstream}"\nauth = { kind = "client" }\n[[providers.bench.models]]\nid = "fixture-model"\ncontext_window = 2000000\n[heads.bench]\nprovider = "bench"\nport = ${headPort}\npinned_model = "fixture-model"\ndiscovery_prefix = "claude-bench--"\n`;
  const config = candidateConfig + baseConfig;
  const normalized = (value: string) => value.replaceAll(directory, '<desk>').replaceAll(String(headPort), '<head-port>').replaceAll(String(controlPort), '<control-port>').replaceAll(upstream, '<upstream>').replaceAll(exporterEndpoint ?? '<no-exporter>', '<exporter>');
  const configPath = join(directory, 'splice.toml'); await Bun.write(configPath, config);
  const log = Bun.file(join(directory, 'daemon.log'));
  const proc = Bun.spawn(['java', `-Duser.home=${deskHome}`, '-Xmx512m', '-jar', artifact, 'daemon'], {
    cwd: directory, env: { PATH: process.env.PATH ?? '/usr/bin:/bin', XDG_CONFIG_HOME: join(deskHome, '.config'), XDG_DATA_HOME: join(deskHome, '.local/share'), XDG_STATE_HOME: join(deskHome, '.local/state'), XDG_CACHE_HOME: join(deskHome, '.cache'), SPLICE_CONFIG: configPath, SPLICE_STATE_DIR: state }, stdout: log, stderr: log,
  });
  const stop = async () => { if (proc.exitCode !== null || proc.signalCode !== null) return; proc.kill('SIGTERM'); const killed = setTimeout(() => proc.kill('SIGKILL'), 10000); await proc.exited; clearTimeout(killed); };
  try {
    const processIdentity = await waitForOwnedHealth(proc.pid, headPort, controlPort, () => proc.exitCode === null && proc.signalCode === null);
    await assertProcessArtifact(proc.pid, artifact);
    await verifyArtifact(m);
    const identity: DeskIdentity = { ...artifactIdentity, ...processIdentity, process_build: `source:${m.source_commit}`, config_revision: hash(normalized(config)), base_config_sha256: hash(normalized(baseConfig)), raw_config_sha256: hash(config) };
    const assertIdentity = async () => { await assertProcessIdentity(identity, () => proc.exitCode === null && proc.signalCode === null); await assertProcessArtifact(proc.pid, artifact); };
    await assertIdentity();
    const keyFile = (await readdir(state)).find(name => name === 'mgmt-key');
    const mgmtKey = keyFile ? (await readFile(join(state, keyFile), 'utf8')).trim() : '';
    const java = Bun.spawnSync(['java', '-version'], { stderr: 'pipe', stdout: 'pipe' });
    return { target: `http://127.0.0.1:${headPort}/v1/messages`, pid: proc.pid, bootId: 'unknown', jvm: new TextDecoder().decode(java.stderr).trim(), processBuild: `source:${m.source_commit}`, configHash: identity.config_revision, identity, assertIdentity, mgmtKey, pollBase: `http://127.0.0.1:${headPort}`, controlBase: `http://127.0.0.1:${controlPort}`, state, stop };
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
