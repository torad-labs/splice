import type { DeskIdentity } from './identity';
import { loopback } from './manifest';

export interface OwnedDesk {
  pid: number | null; identity: DeskIdentity | null; controlBase: string; mgmtKey: string; state: string;
  assertIdentity(): Promise<void>;
}
export interface RuntimeIdentity { bootId: string; build: string; version: string; configRevision: string; schemaVersion: number }
export function runtimeIdentity(value: unknown, declaredSchemaVersion: unknown): RuntimeIdentity {
  if (!value || typeof value !== 'object') throw new Error('runtime_identity_missing');
  const v = value as Record<string, unknown>;
  for (const key of ['bootId', 'build', 'version', 'configRevision']) if (typeof v[key] !== 'string' || !v[key] || (v[key] as string).length > 200) throw new Error('runtime_identity_invalid');
  if (declaredSchemaVersion !== 1 || Object.hasOwn(v, 'schemaVersion') && v.schemaVersion !== 1) throw new Error('runtime_identity_schema');
  return { bootId: v.bootId as string, build: v.build as string, version: v.version as string, configRevision: v.configRevision as string, schemaVersion: 1 };
}
export async function boundedBody(response: Response, limit: number): Promise<Uint8Array> {
  const reader = response.body?.getReader(); const chunks: Uint8Array[] = []; let size = 0;
  if (reader) try {
    while (true) { const next = await reader.read(); if (next.done) break; size += next.value.byteLength; if (size > limit) throw new Error('control_body_limit'); chunks.push(next.value); }
  } catch (error) { await reader.cancel(); throw error; }
  return Buffer.concat(chunks);
}
export async function control(desk: OwnedDesk, path: string, options: { method?: 'GET' | 'POST'; body?: object; limit?: number; timeout_ms?: number } = {}) {
  loopback(desk.controlBase);
  if (!/^\/api\/perf\/[a-z0-9_/?=&.%:-]+$/i.test(path) || path.includes('..') || path.length > 2048) throw new Error('control_path_invalid');
  await desk.assertIdentity();
  const start = performance.now(); const response = await fetch(desk.controlBase + path, {
    method: options.method ?? 'GET', headers: { authorization: `Bearer ${desk.mgmtKey}`, 'content-type': 'application/json' },
    body: options.body === undefined ? undefined : JSON.stringify(options.body), redirect: 'error', signal: AbortSignal.timeout(options.timeout_ms ?? 5000),
  });
  const bytes = await boundedBody(response, options.limit ?? 1024 * 1024);
  await desk.assertIdentity();
  if (!response.ok) throw new Error(`control_http_${response.status}`);
  return { value: JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)) as Record<string, any>, bytes: bytes.byteLength, elapsed_ms: performance.now() - start };
}
export const finite = (value: unknown): number | null => typeof value === 'number' && Number.isFinite(value) ? value : null;
export const counter = (value: unknown): number | null => Number.isSafeInteger(value) && (value as number) >= 0 ? value as number : null;
