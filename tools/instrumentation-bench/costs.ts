import { control, counter, runtimeIdentity, type OwnedDesk, type RuntimeIdentity } from './control';
const counters = ['queries', 'cacheMisses', 'rowsScanned', 'bytesScanned', 'queryMs', 'busy', 'warmProjections', 'coldProjections'] as const;
const gauges = ['activeQueries', 'historyBytes', 'retainedBytes'] as const;
export type SourceCost = Record<typeof counters[number] | typeof gauges[number], number>;
export interface SourceCosts { identity: RuntimeIdentity; process_id: number; as_of_ms: number; sources: { aggregate: SourceCost; evidence: SourceCost }; retained_bytes_scope: 'process_shared_work_pool' }
export async function readSourceCosts(desk: OwnedDesk): Promise<SourceCosts | null> {
  try {
    const { value } = await control(desk, '/api/perf/instrumentation/status');
    if (!['ok', 'partial'].includes(value.status) || value.process_id !== desk.pid || counter(value.as_of_ms) === null || Math.abs(Date.now() - value.as_of_ms) > 15000 || value.source_retained_bytes_scope !== 'process_shared_work_pool') return null;
    const sources = {} as SourceCosts['sources'];
    for (const owner of ['aggregate', 'evidence'] as const) {
      const cost = {} as SourceCost;
      for (const key of [...counters, ...gauges]) { const n = counter(value.sources?.[owner]?.[key]); if (n === null) return null; cost[key] = n; }
      sources[owner] = cost;
    }
    return { identity: runtimeIdentity(value.identity, value.schema_version), process_id: value.process_id, as_of_ms: value.as_of_ms, sources, retained_bytes_scope: 'process_shared_work_pool' };
  } catch { return null; }
}
export function sourceCostDelta(first: SourceCosts | null, last: SourceCosts | null) {
  if (!first || !last || first.process_id !== last.process_id || JSON.stringify(first.identity) !== JSON.stringify(last.identity) || last.as_of_ms < first.as_of_ms) return { status: 'unavailable', reason: 'source_cost_missing_or_identity_changed', sources: null };
  const sources: Record<string, object> = {};
  for (const owner of ['aggregate', 'evidence'] as const) {
    const delta: Record<string, number> = {};
    for (const key of counters) { delta[key] = last.sources[owner][key] - first.sources[owner][key]; if (delta[key]! < 0) return { status: 'unavailable', reason: 'source_cost_counter_reset', sources: null }; }
    sources[owner] = { delta, before: Object.fromEntries(gauges.map(key => [key, first.sources[owner][key]])), after: Object.fromEntries(gauges.map(key => [key, last.sources[owner][key]])) };
  }
  return { status: 'observed', reason: null, sources, from_ms: first.as_of_ms, until_ms: last.as_of_ms, identity: first.identity, process_id: first.process_id, retained_bytes_scope: 'process_shared_work_pool', attribution: 'all source work during query bracket; not exclusive attribution under concurrent polling' };
}
