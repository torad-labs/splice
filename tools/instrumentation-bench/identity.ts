import { readFile, readdir, readlink, realpath } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { hash, type Manifest } from './manifest';
import { sleep } from './fixture';

export interface ArtifactIdentity {
  artifact_path: string; source_commit: string; artifact_sha256: string; artifact_receipt_sha256: string;
}
export interface ListenerIdentity { port: number; inode: string }
export interface ProcessIdentity {
  pid: number; start_ticks: string; listeners: ListenerIdentity[];
  verification: 'linux-proc-listen-inodes'; verified_at: string;
}
export interface DeskIdentity extends ArtifactIdentity, ProcessIdentity {
  process_build: string; config_revision: string; base_config_sha256: string; raw_config_sha256: string;
}
export async function verifyArtifact(m: Manifest): Promise<ArtifactIdentity> {
  const artifact = await realpath(m.target.artifact_path!);
  if (!artifact.startsWith('/tmp/splice-instrumentation-')) throw new Error('artifact_path_escaped');
  if (hash(new Uint8Array(await Bun.file(artifact).arrayBuffer())) !== m.artifact_sha256) throw new Error('artifact_sha256_mismatch');
  const raw = await Bun.file(join(dirname(artifact), 'identity.json')).text();
  const receipt = JSON.parse(raw);
  if (receipt.source_commit !== m.source_commit || receipt.artifact_sha256 !== m.artifact_sha256 || receipt.clean_source !== true) throw new Error('artifact_identity_receipt_mismatch');
  return { artifact_path: artifact, source_commit: receipt.source_commit, artifact_sha256: receipt.artifact_sha256, artifact_receipt_sha256: hash(raw) };
}
export async function startTicks(pid: number): Promise<string> {
  if (process.platform !== 'linux') throw new Error('process_socket_identity_unsupported');
  try {
    const raw = await readFile(`/proc/${pid}/stat`, 'utf8'); const fields = raw.slice(raw.lastIndexOf(')') + 2).split(' ');
    const ticks = fields[19];
    if (!ticks || !/^\d+$/.test(ticks) || ['Z', 'X'].includes(fields[0] ?? '')) throw new Error();
    return ticks;
  } catch { throw new Error('process_identity_unavailable'); }
}
async function listeners(pid: number, ports: number[], expectedStart: string): Promise<ListenerIdentity[] | null> {
  if (await startTicks(pid) !== expectedStart) throw new Error('process_identity_changed');
  let tables: string[], descriptors: string[];
  try {
    tables = await Promise.all(['tcp', 'tcp6'].map(name => readFile(`/proc/${pid}/net/${name}`, 'utf8')));
    descriptors = await readdir(`/proc/${pid}/fd`);
  } catch { throw new Error('process_socket_identity_unavailable'); }
  const links = await Promise.all(descriptors.map(async fd => { try { return await readlink(`/proc/${pid}/fd/${fd}`); } catch { return ''; } }));
  const owned = new Set(links.flatMap(link => { const match = /^socket:\[(\d+)\]$/.exec(link); return match?.[1] ? [match[1]] : []; }));
  const found = new Map<number, string>();
  for (const table of tables) for (const row of table.trim().split('\n').slice(1)) {
    const fields = row.trim().split(/\s+/); const [address, hexPort] = (fields[1] ?? '').split(':'); const inode = fields[9];
    // Only bindings reachable through our IPv4 loopback URL count, never another local interface.
    if (!['0100007F', '00000000', '00000000000000000000000000000000', '0000000000000000FFFF00000100007F'].includes(address ?? '')) continue;
    const port = parseInt(hexPort ?? '', 16);
    if (fields[3] === '0A' && inode && ports.includes(port) && owned.has(inode)) found.set(port, inode);
  }
  if (await startTicks(pid) !== expectedStart) throw new Error('process_identity_changed');
  return ports.every(port => found.has(port)) ? ports.map(port => ({ port, inode: found.get(port)! })) : null;
}
export async function assertProcessIdentity(identity: ProcessIdentity, alive: () => boolean) {
  if (!alive()) throw new Error('isolated_desk_exited');
  const current = await listeners(identity.pid, identity.listeners.map(item => item.port), identity.start_ticks);
  if (!current || JSON.stringify(current) !== JSON.stringify(identity.listeners) || !alive()) throw new Error('process_socket_identity_changed');
}
export async function assertProcessArtifact(pid: number, artifact: string) {
  let args: string[];
  try { args = (await readFile(`/proc/${pid}/cmdline`, 'utf8')).split('\0'); } catch { throw new Error('process_artifact_unavailable'); }
  const jar = args[args.indexOf('-jar') + 1];
  if (!args.includes('-jar') || !jar || await realpath(jar) !== artifact) throw new Error('process_artifact_mismatch');
}
export async function waitForOwnedHealth(pid: number, headPort: number, controlPort: number, alive: () => boolean, timeoutMs = 20000): Promise<ProcessIdentity> {
  const start = await startTicks(pid); const deadline = performance.now() + timeoutMs;
  while (performance.now() < deadline) {
    if (!alive()) throw new Error('isolated_desk_exited');
    const owned = await listeners(pid, [headPort, controlPort], start);
    if (owned) {
      const identity: ProcessIdentity = { pid, start_ticks: start, listeners: owned, verification: 'linux-proc-listen-inodes', verified_at: new Date().toISOString() };
      let healthy = false;
      // No HTTP request, including /health, is sent until both sockets are proven to belong to this child.
      try { const response = await fetch(`http://127.0.0.1:${headPort}/health`, { signal: AbortSignal.timeout(250), redirect: 'error' }); healthy = response.ok; await response.body?.cancel(); } catch {}
      await assertProcessIdentity(identity, alive);
      if (healthy) return identity;
    }
    await sleep(25);
  }
  throw new Error('isolated_desk_identity_timeout');
}
export async function verifyRunBinding(m: Manifest): Promise<DeskIdentity[]> {
  if (m.target.kind !== 'proxy') throw new Error('comparison_requires_proxy_artifacts');
  const artifact = await verifyArtifact(m);
  const provenance = m.provenance as { process_identities?: DeskIdentity[] } | undefined;
  const identities = provenance?.process_identities;
  const expected = m.cache_state === 'cold' ? m.repetitions : 1;
  if (!Array.isArray(identities) || identities.length !== expected) throw new Error('process_binding_missing');
  for (const id of identities) {
    if (id.artifact_path !== artifact.artifact_path || id.source_commit !== artifact.source_commit || id.artifact_sha256 !== artifact.artifact_sha256 || id.artifact_receipt_sha256 !== artifact.artifact_receipt_sha256) throw new Error('process_artifact_binding_mismatch');
    if (id.process_build !== `source:${m.source_commit}` || m.process_build !== id.process_build || id.config_revision !== m.config_revision || !/^[a-f0-9]{64}$/.test(id.config_revision)) throw new Error('process_config_binding_mismatch');
    if (!/^[a-f0-9]{64}$/.test(id.base_config_sha256) || !/^[a-f0-9]{64}$/.test(id.raw_config_sha256)) throw new Error('config_binding_missing');
    if (id.verification !== 'linux-proc-listen-inodes' || !Number.isSafeInteger(id.pid) || id.pid < 1 || !/^\d+$/.test(id.start_ticks) || !Number.isFinite(Date.parse(id.verified_at)) || !Array.isArray(id.listeners) || id.listeners.length !== 2) throw new Error('process_binding_invalid');
    if (new Set(id.listeners.map(item => item.port)).size !== 2 || id.listeners.some(item => !Number.isSafeInteger(item.port) || item.port <= 1024 || item.port > 65535 || [3096, 3099].includes(item.port) || !/^\d+$/.test(item.inode))) throw new Error('socket_binding_invalid');
  }
  return identities;
}
