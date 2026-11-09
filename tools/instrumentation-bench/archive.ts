import { createHash, randomUUID } from 'node:crypto';
import { constants, createWriteStream, lstatSync } from 'node:fs';
import { chmod, copyFile, lstat, mkdir, open, readdir, realpath, rename, rm, stat, statfs, utimes } from 'node:fs/promises';
import { createGunzip, createGzip, constants as zlibConstants } from 'node:zlib';
import { Transform, Writable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { dirname, join, relative, resolve, sep } from 'node:path';

type Artifact = { path: string; sha256: string };
export type ArchiveOptions = { artifact: Artifact; processGroup: number };
type FileEntry = { path: string; mode: number; mtime_ms: number; bytes: number; sha256: string; stored_as?: string; artifact_reference?: true };
type DirectoryEntry = { path: string; mode: number; mtime_ms: number };
type ArchiveManifest = {
  format: 'splice-benchmark-desk-archive-v1'; measured_status: 'complete' | 'partial'; partial_reason: string | null;
  run_directory: string; role: 'baseline' | 'candidate'; source_commit: string; artifact: Artifact & { bytes: number };
  files: FileEntry[]; directories: DirectoryEntry[]; created_at: string;
};
export type ArchiveReceipt = {
  format: 'splice-benchmark-desk-archive-receipt-v1'; status: 'archived' | 'archive_verified_raw_pending';
  measured_status: 'complete' | 'partial'; partial_reason: string | null; role: 'baseline' | 'candidate';
  run_directory: string; archive_directory: string; archive_manifest_sha256: string; artifact: Artifact & { bytes: number };
  archived_files: number; archived_source_bytes: number; compressed_payload_bytes: number;
  deduplicated_artifacts: number; deduplicated_artifact_bytes: number; archived_at: string;
};

const ARCHIVE_NAME = 'desk-archive';
const RECEIPT_NAME = 'desk-archive-receipt.json';
const digest = (value: string | Uint8Array) => createHash('sha256').update(value).digest('hex');
const json = (value: unknown) => JSON.stringify(value, null, 2) + '\n';
const safeRelative = (path: string) => !path.startsWith('/') && !path.split('/').some(part => !part || part === '.' || part === '..') && !path.includes('\\');

function demand(value: unknown, code: string): asserts value { if (!value) throw new Error(code); }
async function fileHash(path: string) {
  const hash = createHash('sha256');
  const handle = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
  try { await pipeline(handle.createReadStream({ autoClose: false }), new Transform({ transform(chunk, _encoding, callback) { hash.update(chunk); callback(null, chunk); } }), new Writable({ write(_chunk, _encoding, callback) { callback(); } })); }
  finally { await handle.close(); }
  return hash.digest('hex');
}
async function readJson(path: string, maxBytes = 8 * 1024 ** 2) {
  const info = await lstat(path);
  demand(info.isFile() && !info.isSymbolicLink() && info.size <= maxBytes, 'archive_metadata_file_invalid');
  const handle = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
  try { const bytes = await handle.readFile(); demand(bytes.length === info.size, 'archive_metadata_changed'); return JSON.parse(bytes.toString('utf8')); }
  finally { await handle.close(); }
}
async function readBytes(path: string, maxBytes: number) {
  const info = await lstat(path);
  demand(info.isFile() && !info.isSymbolicLink() && info.size <= maxBytes, 'archive_metadata_file_invalid');
  const handle = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
  try { const bytes = await handle.readFile(); demand(bytes.length === info.size, 'archive_metadata_changed'); return bytes; }
  finally { await handle.close(); }
}
function checkDeadGroup(processGroup: number) {
  demand(Number.isSafeInteger(processGroup) && processGroup > 1, 'closed_process_group_required');
  try { process.kill(-processGroup, 0); throw new Error('benchmark_process_group_still_alive'); }
  catch (error) { if ((error as NodeJS.ErrnoException).code !== 'ESRCH') throw error; }
}
async function privateRunDirectory(path: string) {
  const absolute = resolve(path);
  demand(/^\/tmp\/splice-instrumentation-[A-Za-z0-9_./-]+$/.test(absolute), 'task_run_directory_required');
  const actual = await realpath(absolute);
  demand(actual === absolute && actual.startsWith('/tmp/splice-instrumentation-'), 'task_run_directory_symlink_or_escape');
  const info = await lstat(actual); demand(info.isDirectory() && !info.isSymbolicLink() && typeof process.getuid === 'function' && info.uid === process.getuid() && (info.mode & 0o077) === 0, 'private_run_directory_required');
  return actual;
}
async function artifactInfo(artifact: Artifact) {
  demand(/^[a-f0-9]{64}$/.test(artifact.sha256), 'artifact_sha256_required');
  const path = resolve(artifact.path), actual = await realpath(path), info = await lstat(path);
  demand(path.startsWith('/tmp/splice-instrumentation-') && path === actual && info.isFile() && !info.isSymbolicLink(), 'pinned_artifact_file_required');
  demand(await fileHash(actual) === artifact.sha256, 'pinned_artifact_hash_mismatch');
  return { path: actual, sha256: artifact.sha256, bytes: info.size };
}
async function scanDesk(runDirectory: string) {
  const files: Array<{ path: string; absolute: string; mode: number; mtime_ms: number; bytes: number; sha256: string }> = [];
  const directories: DirectoryEntry[] = [];
  const roots = (await readdir(runDirectory)).filter(name => /^desk-\d+$/.test(name)).sort();
  demand(roots.length > 0, 'completed_proxy_run_has_no_desk_state');
  const visit = async (absolute: string) => {
    const before = await lstat(absolute), path = relative(runDirectory, absolute).split(sep).join('/');
    demand(safeRelative(path) && !before.isSymbolicLink(), 'desk_symlink_or_unsafe_path');
    if (before.isDirectory()) {
      directories.push({ path, mode: before.mode & 0o7777, mtime_ms: before.mtimeMs });
      for (const child of (await readdir(absolute)).sort()) await visit(join(absolute, child));
      return;
    }
    demand(before.isFile(), 'desk_nonregular_file');
    const handle = await open(absolute, constants.O_RDONLY | constants.O_NOFOLLOW);
    try {
      const opened = await handle.stat();
      demand(opened.dev === before.dev && opened.ino === before.ino && opened.size === before.size, 'desk_file_changed_before_archive');
      const hash = createHash('sha256');
      const meter = new Transform({ transform(chunk, _encoding, callback) { hash.update(chunk); callback(null, chunk); } });
      await pipeline(handle.createReadStream({ autoClose: false }), meter, new Writable({ write(_chunk, _encoding, callback) { callback(); } }));
      const after = await handle.stat();
      demand(after.dev === opened.dev && after.ino === opened.ino && after.size === opened.size && after.mtimeMs === opened.mtimeMs, 'desk_file_changed_during_archive_scan');
      files.push({ path, absolute, mode: before.mode & 0o7777, mtime_ms: before.mtimeMs, bytes: before.size, sha256: hash.digest('hex') });
    } finally { await handle.close(); }
  };
  for (const root of roots) await visit(join(runDirectory, root));
  files.sort((a, b) => a.path.localeCompare(b.path)); directories.sort((a, b) => a.path.localeCompare(b.path));
  return { files, directories };
}
async function compressFile(source: string, target: string) {
  const inputInfo = await lstat(source);
  const handle = await open(source, constants.O_RDONLY | constants.O_NOFOLLOW);
  const output = createWriteStream(target, { flags: 'wx', mode: 0o600 });
  const hash = createHash('sha256'); let bytes = 0;
  const meter = new Transform({ transform(chunk, _encoding, callback) { bytes += chunk.length; hash.update(chunk); callback(null, chunk); } });
  try {
    const opened = await handle.stat();
    demand(opened.isFile() && opened.dev === inputInfo.dev && opened.ino === inputInfo.ino && opened.size === inputInfo.size, 'desk_file_changed_before_compression');
    await pipeline(handle.createReadStream({ autoClose: false }), meter, createGzip({ level: zlibConstants.Z_BEST_SPEED }), output);
    const after = await handle.stat();
    demand(after.dev === opened.dev && after.ino === opened.ino && after.size === opened.size && after.mtimeMs === opened.mtimeMs && bytes === opened.size, 'desk_file_changed_during_compression');
    await syncFile(target);
    const compressedBytes = (await stat(target)).size;
    return { bytes, sha256: hash.digest('hex'), compressedBytes };
  } finally { await handle.close(); }
}
async function syncFile(path: string) {
  const handle = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
  try { await handle.sync(); } finally { await handle.close(); }
}
async function requireArchiveWorkspace(directory: string, files: Array<{ path: string; bytes: number; sha256: string }>, artifact: Artifact & { bytes: number }) {
  const payloads = files.filter(row => !(row.path.includes('/worker-artifacts/') && row.path.endsWith('.jar')
    && row.sha256 === artifact.sha256 && row.bytes === artifact.bytes));
  // Plan for incompressible input plus generous gzip/metadata overhead, not an assumed compression ratio.
  const required = payloads.reduce((bytes, row) => bytes + Math.ceil(row.bytes * 1.01) + 64 * 1024, 1024 ** 3);
  const disk = await statfs(directory, { bigint: true });
  demand(Number(disk.bavail * disk.bsize) >= required, 'archive_workspace_unavailable');
}
async function syncDirectory(path: string) {
  const handle = await open(path, constants.O_RDONLY | constants.O_DIRECTORY | constants.O_NOFOLLOW);
  try { await handle.sync(); } finally { await handle.close(); }
}
async function verifyGzip(path: string, expected: Pick<FileEntry, 'bytes' | 'sha256'>) {
  const compressed = await lstat(path);
  demand(compressed.isFile() && !compressed.isSymbolicLink(), 'archive_payload_file_invalid');
  const hash = createHash('sha256'); let bytes = 0;
  const meter = new Transform({ transform(chunk, _encoding, callback) { bytes += chunk.length; hash.update(chunk); callback(null, chunk); } });
  const handle = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
  try { await pipeline(handle.createReadStream({ autoClose: false }), createGunzip(), meter,
    new Writable({ write(_chunk, _encoding, callback) { callback(); } })); }
  finally { await handle.close(); }
  demand(bytes === expected.bytes && hash.digest('hex') === expected.sha256, 'archive_roundtrip_hash_mismatch');
}
async function writeNew(path: string, value: unknown) {
  const handle = await open(path, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600);
  try { await handle.writeFile(json(value)); await handle.sync(); } finally { await handle.close(); }
}
function isExpectedPartial(summary: any, manifest: any) {
  return summary?.status === 'partial' && summary?.partial_reason === 'history_study_partial'
    && manifest?.role === 'candidate' && manifest?.retained_history?.query_source === 'history';
}
async function verifyRunRoot(directory: string, artifact: Artifact & { bytes: number }) {
  const manifest = await readJson(join(directory, 'manifest.json'));
  const summary = await readJson(join(directory, 'summary.json'));
  const receipt = await readJson(join(directory, 'receipt.json'), 1024 * 1024);
  demand(['baseline', 'candidate'].includes(manifest.role) && manifest.target?.kind === 'proxy'
    && /^[a-f0-9]{40}$/.test(manifest.source_commit || ''), 'proxy_role_run_required');
  demand(manifest.artifact_sha256 === artifact.sha256 && resolve(manifest.target.artifact_path) === artifact.path, 'run_artifact_binding_mismatch');
  demand(manifest.repetitions >= 5 && manifest.experiment_stage === 'steady', 'steady_five_repetition_run_required');
  const partial = isExpectedPartial(summary, manifest);
  demand(summary.status === 'complete' || partial, 'incomplete_or_unverified_partial_run');
  const names = ['manifest.json', 'samples.jsonl', 'observations.json'];
  for (const name of names) {
    const path = join(directory, name), info = await lstat(path);
    demand(info.isFile() && !info.isSymbolicLink(), 'run_evidence_file_missing');
    const key = name === 'manifest.json' ? 'manifest_sha256' : name === 'samples.jsonl' ? 'samples_sha256' : 'observations_sha256';
    demand(receipt[key] === await fileHash(path), 'run_evidence_hash_mismatch');
  }
  demand(Number.isSafeInteger(receipt.sample_count) && receipt.sample_count >= 0, 'run_sample_receipt_invalid');
  return { manifest, summary, receipt, measuredStatus: partial ? 'partial' as const : 'complete' as const,
    partialReason: partial ? 'history_study_partial' : null };
}
async function readArchive(directory: string, artifact: Artifact) {
  const archiveDirectory = join(directory, ARCHIVE_NAME);
  const archiveInfo = await lstat(archiveDirectory);
  demand(archiveInfo.isDirectory() && !archiveInfo.isSymbolicLink(), 'archive_directory_invalid');
  const manifestPath = join(archiveDirectory, 'manifest.json');
  const manifestInfo = await lstat(manifestPath);
  demand(manifestInfo.isFile() && !manifestInfo.isSymbolicLink() && manifestInfo.size <= 16 * 1024 ** 2, 'archive_manifest_file_invalid');
  const manifestBytes = await readBytes(manifestPath, 16 * 1024 ** 2);
  const manifest = JSON.parse(manifestBytes.toString('utf8')) as ArchiveManifest;
  demand(manifest.format === 'splice-benchmark-desk-archive-v1' && manifest.run_directory === directory, 'archive_manifest_binding_mismatch');
  demand(manifest.artifact.sha256 === artifact.sha256 && resolve(manifest.artifact.path) === resolve(artifact.path), 'archive_artifact_binding_mismatch');
  demand(Array.isArray(manifest.files) && Array.isArray(manifest.directories), 'archive_manifest_inventory_invalid');
  const archiveNames = (await readdir(archiveDirectory)).sort();
  demand(JSON.stringify(archiveNames) === JSON.stringify(['files', 'manifest.json']), 'archive_directory_inventory_mismatch');
  const pinned = await artifactInfo(artifact);
  demand(pinned.bytes === manifest.artifact.bytes, 'archive_artifact_size_mismatch');
  const payloadDirectory = join(archiveDirectory, 'files'), payloadInfo = await lstat(payloadDirectory);
  demand(payloadInfo.isDirectory() && !payloadInfo.isSymbolicLink(), 'archive_payload_directory_invalid');
  const fileNames = new Set<string>(), directoryNames = new Set<string>();
  let compressedIndex = 0, compressedBytes = 0, sourceBytes = 0, deduplicatedBytes = 0, deduplicatedArtifacts = 0;
  for (const row of manifest.files) {
    demand(safeRelative(row.path) && /^desk-\d+\//.test(row.path) && !fileNames.has(row.path)
      && Number.isSafeInteger(row.bytes) && row.bytes >= 0 && Number.isSafeInteger(row.mode) && Number.isFinite(row.mtime_ms)
      && /^[a-f0-9]{64}$/.test(row.sha256), 'archive_entry_path_invalid'); fileNames.add(row.path);
    sourceBytes += row.bytes;
    if (row.artifact_reference) {
      demand(row.sha256 === artifact.sha256 && row.bytes === pinned.bytes && row.path.includes('/worker-artifacts/'), 'archive_artifact_reference_invalid');
      deduplicatedBytes += row.bytes; deduplicatedArtifacts++;
      continue;
    }
    const expectedStore = `files/${String(compressedIndex++).padStart(8, '0')}.gz`;
    demand(row.stored_as === expectedStore, 'archive_payload_index_invalid');
    const payloadPath = join(archiveDirectory, expectedStore);
    await verifyGzip(payloadPath, row); compressedBytes += (await lstat(payloadPath)).size;
  }
  for (const row of manifest.directories) {
    demand(safeRelative(row.path) && /^desk-\d+(?:\/.*)?$/.test(row.path) && !directoryNames.has(row.path)
      && !fileNames.has(row.path) && Number.isSafeInteger(row.mode) && Number.isFinite(row.mtime_ms), 'archive_directory_entry_invalid');
    directoryNames.add(row.path);
  }
  for (const path of fileNames) {
    const parts = path.split('/');
    for (let count = 1; count < parts.length; count++) demand(directoryNames.has(parts.slice(0, count).join('/')), 'archive_parent_directory_missing');
  }
  for (const path of directoryNames) {
    const parent = path.includes('/') ? path.slice(0, path.lastIndexOf('/')) : null;
    if (parent) demand(directoryNames.has(parent), 'archive_parent_directory_missing');
  }
  const listedPayloads = (await readdir(payloadDirectory)).sort();
  demand(listedPayloads.length === compressedIndex && listedPayloads.every((name, index) => name === `${String(index).padStart(8, '0')}.gz`), 'archive_payload_inventory_mismatch');
  const rootReceipt = await readJson(join(directory, RECEIPT_NAME));
  demand(rootReceipt.format === 'splice-benchmark-desk-archive-receipt-v1' && ['archived', 'archive_verified_raw_pending'].includes(rootReceipt.status)
    && rootReceipt.archive_directory === archiveDirectory && rootReceipt.archive_manifest_sha256 === digest(manifestBytes), 'archive_receipt_hash_mismatch');
  demand(rootReceipt.artifact.sha256 === artifact.sha256 && rootReceipt.run_directory === directory, 'archive_receipt_binding_mismatch');
  demand(rootReceipt.role === manifest.role && rootReceipt.measured_status === manifest.measured_status && rootReceipt.partial_reason === manifest.partial_reason
    && rootReceipt.archived_files === manifest.files.length && rootReceipt.archived_source_bytes === sourceBytes
    && rootReceipt.compressed_payload_bytes === compressedBytes && rootReceipt.deduplicated_artifacts === deduplicatedArtifacts
    && rootReceipt.deduplicated_artifact_bytes === deduplicatedBytes, 'archive_receipt_totals_mismatch');
  return { manifest, manifestBytes, rootReceipt: rootReceipt as ArchiveReceipt, archiveDirectory };
}
export async function verifyArchive(directory: string) {
  const runDirectory = await privateRunDirectory(directory);
  const rootReceipt = await readJson(join(runDirectory, RECEIPT_NAME));
  const checked = await readArchive(runDirectory, rootReceipt.artifact);
  demand(checked.rootReceipt.status === rootReceipt.status, 'archive_receipt_status_changed');
  return checked.rootReceipt;
}
export async function archiveRun(directory: string, options: ArchiveOptions): Promise<ArchiveReceipt> {
  const runDirectory = await privateRunDirectory(directory); checkDeadGroup(options.processGroup);
  const artifact = await artifactInfo(options.artifact);
  const verified = await verifyRunRoot(runDirectory, artifact);
  const finalArchive = join(runDirectory, ARCHIVE_NAME), finalReceipt = join(runDirectory, RECEIPT_NAME);
  demand(!(await lstat(finalArchive).then(() => true).catch(() => false)) && !(await lstat(finalReceipt).then(() => true).catch(() => false)), 'archive_already_exists');
  const scanned = await scanDesk(runDirectory), tempArchive = join(runDirectory, `.desk-archive-${randomUUID()}`);
  await requireArchiveWorkspace(runDirectory, scanned.files, artifact);
  await mkdir(tempArchive, { mode: 0o700 }); await mkdir(join(tempArchive, 'files'), { mode: 0o700 });
  const entries: FileEntry[] = []; let payloadIndex = 0, sourceBytes = 0, compressedPayloadBytes = 0, deduplicated = 0, deduplicatedBytes = 0;
  try {
    for (const row of scanned.files) {
      const common = { path: row.path, mode: row.mode, mtime_ms: row.mtime_ms, bytes: row.bytes, sha256: row.sha256 };
      sourceBytes += row.bytes;
      if (row.path.includes('/worker-artifacts/') && row.path.endsWith('.jar') && row.sha256 === artifact.sha256 && row.bytes === artifact.bytes) {
        entries.push({ ...common, artifact_reference: true }); deduplicated++; deduplicatedBytes += row.bytes; continue;
      }
      const storedAs = `files/${String(payloadIndex++).padStart(8, '0')}.gz`;
      const payload = await compressFile(row.absolute, join(tempArchive, storedAs));
      demand(payload.bytes === row.bytes && payload.sha256 === row.sha256, 'archive_source_changed');
      entries.push({ ...common, stored_as: storedAs }); compressedPayloadBytes += payload.compressedBytes;
    }
    const archiveManifest: ArchiveManifest = { format: 'splice-benchmark-desk-archive-v1', measured_status: verified.measuredStatus,
      partial_reason: verified.partialReason, run_directory: runDirectory, role: verified.manifest.role, source_commit: verified.manifest.source_commit,
      artifact, files: entries, directories: scanned.directories, created_at: new Date().toISOString() };
    const manifestBytes = Buffer.from(json(archiveManifest)); await writeNew(join(tempArchive, 'manifest.json'), archiveManifest); await syncFile(join(tempArchive, 'manifest.json'));
    await syncDirectory(tempArchive);
    await syncDirectory(join(tempArchive, 'files'));
    const tempInfo = await readArchiveAt(tempArchive, archiveManifest, artifact);
    demand(tempInfo === digest(manifestBytes), 'archive_manifest_roundtrip_mismatch');
    const afterScan = await scanDesk(runDirectory);
    const sourceInventory = (scan: typeof scanned) => ({
      files: scan.files.map(({ path, mode, mtime_ms, bytes, sha256 }) => ({ path, mode, mtime_ms, bytes, sha256 })),
      directories: scan.directories,
    });
    demand(JSON.stringify(sourceInventory(afterScan)) === JSON.stringify(sourceInventory(scanned)), 'desk_tree_changed_before_cleanup');
    const expectedRoots = scanned.directories.map(row => row.path).filter(path => /^desk-\d+$/.test(path)).sort();
    const currentRoots = (await readdir(runDirectory)).filter(name => /^desk-\d+$/.test(name)).sort();
    demand(JSON.stringify(currentRoots) === JSON.stringify(expectedRoots), 'unexpected_desk_root_before_cleanup');
    checkDeadGroup(options.processGroup);
    await rename(tempArchive, finalArchive);
    await syncDirectory(runDirectory);
    const receipt: ArchiveReceipt = { format: 'splice-benchmark-desk-archive-receipt-v1', status: 'archive_verified_raw_pending',
      measured_status: verified.measuredStatus, partial_reason: verified.partialReason, role: verified.manifest.role, run_directory: runDirectory,
      archive_directory: finalArchive, archive_manifest_sha256: digest(manifestBytes), artifact, archived_files: entries.length,
      archived_source_bytes: sourceBytes, compressed_payload_bytes: compressedPayloadBytes,
      deduplicated_artifacts: deduplicated, deduplicated_artifact_bytes: deduplicatedBytes, archived_at: new Date().toISOString() };
    await writeNew(finalReceipt, receipt);
    await syncDirectory(runDirectory);
    await verifyArchive(runDirectory);
    checkDeadGroup(options.processGroup);
    for (const root of expectedRoots) {
      const info = await lstat(join(runDirectory, root)); demand(info.isDirectory() && !info.isSymbolicLink(), 'desk_root_changed_before_cleanup');
      await rm(join(runDirectory, root), { recursive: true, force: false });
    }
    await syncDirectory(runDirectory);
    const done = { ...receipt, status: 'archived' as const };
    const tempReceipt = `${finalReceipt}.${randomUUID()}.tmp`; await writeNew(tempReceipt, done); await rename(tempReceipt, finalReceipt);
    await syncDirectory(runDirectory);
    return done;
  } catch (error) {
    await rm(tempArchive, { recursive: true, force: true }).catch(() => {});
    throw error;
  }
}
async function readArchiveAt(directory: string, manifest: ArchiveManifest, artifact: Artifact) {
  const bytes = await readBytes(join(directory, 'manifest.json'), 16 * 1024 ** 2);
  demand(bytes.length <= 16 * 1024 ** 2 && digest(bytes) === digest(json(manifest)), 'archive_manifest_invalid');
  const payloadDirectory = await lstat(join(directory, 'files'));
  demand(payloadDirectory.isDirectory() && !payloadDirectory.isSymbolicLink(), 'archive_payload_directory_invalid');
  const pinned = await artifactInfo(artifact); demand(pinned.bytes === manifest.artifact.bytes, 'archive_artifact_size_mismatch');
  for (const row of manifest.files) {
    if (row.artifact_reference) {
      demand(row.sha256 === artifact.sha256 && row.bytes === pinned.bytes && await fileHash(artifact.path) === artifact.sha256, 'archive_artifact_reference_invalid');
    } else {
      demand(row.stored_as && safeRelative(row.stored_as), 'archive_payload_path_invalid');
      await verifyGzip(join(directory, row.stored_as), row);
    }
  }
  return digest(bytes);
}
export async function restoreArchive(directory: string, options: { artifact: Artifact; destination?: string }) {
  const runDirectory = await privateRunDirectory(directory); const checked = await readArchive(runDirectory, options.artifact);
  const roots = checked.manifest.directories.filter(row => /^desk-\d+$/.test(row.path));
  const destination = options.destination ? await freshTaskDirectory(options.destination) : runDirectory;
  if (destination === runDirectory) demand(roots.every(row => !existsSync(join(runDirectory, row.path))), 'desk_state_already_present_use_fresh_restore_destination');
  else demand((await readdir(destination)).length === 0, 'restore_destination_must_be_empty');
  const staging = join(destination, `.desk-restore-${randomUUID()}`); await mkdir(staging, { mode: 0o700 });
  try {
    for (const row of checked.manifest.directories) {
      const path = safeDestination(staging, row.path); await mkdir(path, { recursive: true, mode: 0o700 });
    }
    for (const row of checked.manifest.files) {
      const destination = safeDestination(staging, row.path); await mkdir(dirname(destination), { recursive: true, mode: 0o700 });
      if (row.artifact_reference) await copyFile(checked.rootReceipt.artifact.path, destination, constants.COPYFILE_EXCL);
      else {
        const payload = await open(join(checked.archiveDirectory, row.stored_as!), constants.O_RDONLY | constants.O_NOFOLLOW);
        try { await pipeline(payload.createReadStream({ autoClose: false }), createGunzip(), createWriteStream(destination, { flags: 'wx', mode: row.mode & 0o777 })); }
        finally { await payload.close(); }
      }
      demand(await fileHash(destination) === row.sha256 && (await stat(destination)).size === row.bytes, 'restored_file_hash_mismatch');
      await chmod(destination, row.mode & 0o7777);
      const time = new Date(row.mtime_ms); await utimes(destination, time, time);
    }
    for (const row of checked.manifest.directories.slice().sort((a, b) => b.path.length - a.path.length)) {
      const path = safeDestination(staging, row.path); await chmod(path, row.mode & 0o7777); const time = new Date(row.mtime_ms); await utimes(path, time, time);
    }
    for (const row of roots) await rename(safeDestination(staging, row.path), join(destination, row.path));
    await rm(staging, { recursive: true, force: true });
    return await verifyArchive(runDirectory);
  } catch (error) { await rm(staging, { recursive: true, force: true }).catch(() => {}); throw error; }
}
async function freshTaskDirectory(path: string) {
  const absolute = resolve(path);
  demand(/^\/tmp\/splice-instrumentation-[A-Za-z0-9_./-]+$/.test(absolute), 'task_restore_destination_required');
  const parent = dirname(absolute), realParent = await realpath(parent);
  demand(parent === realParent && realParent.startsWith('/tmp/splice-instrumentation-'), 'restore_parent_symlink_or_escape');
  await mkdir(absolute, { mode: 0o700 });
  const info = await lstat(absolute), actual = await realpath(absolute);
  demand(info.isDirectory() && !info.isSymbolicLink() && actual === absolute && typeof process.getuid === 'function' && info.uid === process.getuid() && (info.mode & 0o077) === 0, 'private_restore_destination_required');
  return absolute;
}
function safeDestination(root: string, path: string) {
  demand(safeRelative(path) && /^desk-\d+(?:\/.*)?$/.test(path), 'archive_restore_path_invalid');
  const result = resolve(root, path); demand(result.startsWith(root + sep), 'archive_restore_path_escape'); return result;
}
function existsSync(path: string) { try { lstatSync(path); return true; } catch { return false; } }
