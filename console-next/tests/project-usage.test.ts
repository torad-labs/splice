import { expect, test } from 'vitest';
import { projectUsage } from '../src/lib/project-teams';
import type { SessionRow } from '../src/types/sessions';
import type { TurnsState, TurnSessionUsage } from '../src/types/perf';

const sessions = [{ session_id: 'synthetic-full-session', head: 'shared-command' }] as SessionRow[];
const group = (over: Partial<TurnSessionUsage> = {}): TurnSessionUsage => ({
  key: 'synthetic-full-session', requests: 2502, input_tokens: 2501000, cached_tokens: 2250900, output_tokens: 250100,
  cost_usd: 1.47559, cache_share: 0.9, unpriced_requests: 1, missing_input_requests: 1, missing_output_requests: 1,
  missing_cache_requests: 0, last_model: 'ordinary-model', last_model_ts_epoch_ms: 150, ...over,
});
const reading = (groups: TurnSessionUsage[] = [group()]): TurnsState => ({
  matched: 2502, matchedBy: { 'shared-command': 2502 }, inflight: [], unread: [],
  truncated: [{ head: 'shared-command', count: 2502, returned: 1 }], landed: [],
  usageBy: { 'shared-command': { totals: group(), models: [], accounts: [], days: [], sessions: groups } },
});

test('project usage consumes session aggregates larger than the displayed slice', () => {
  const value = projectUsage(sessions, reading());
  expect(value).toMatchObject({ requests: 2502, input: 2501000, output: 250100, cost: 1.47559, unpriced: 1 });
});

test('other projects and short session tags cannot masquerade as full session identity', () => {
  const value = projectUsage(sessions, reading([group(), group({ key: 'another-project' }), group({ key: null })]));
  expect(value).toMatchObject({ requests: 2502, unattributed: 2502 });
});

test('a missing price remains missing while the daemon last ordinary model is preserved', () => {
  const value = projectUsage(sessions, reading([group({ cost_usd: null, unpriced_requests: 2502 })]));
  expect(value).toMatchObject({ cost: null, unpriced: 2502, models: { 'synthetic-full-session': { head: 'shared-command', model: 'ordinary-model' } } });
});

test('a project total names why its requests have no price', () => {
  const value = projectUsage(sessions, reading([group({ unpriced_requests: 3, unpriced_uncounted_requests: 1, unpriced_plan_requests: 2, unpriced_undeclared_requests: 0 })]));
  expect(value?.gaps).toEqual({ uncounted: 1, plan: 2, undeclared: 0, unknown: 0 });
});

test('an older daemon is unavailable, never an invented empty project total', () => {
  expect(projectUsage(sessions, { ...reading(), usageBy: {} })).toBeNull();
});

test('a session that changed commands keeps its newest ordinary model across their aggregates', () => {
  const data = reading();
  data.matchedBy['other'] = 1;
  const usageBy = data.usageBy;
  if (usageBy === undefined) throw new Error('synthetic aggregates must exist');
  usageBy['other'] = { totals: group(), models: [], accounts: [], days: [], sessions: [group({ last_model: 'newer-model', last_model_ts_epoch_ms: 200 })] };
  expect(projectUsage(sessions, data)).toMatchObject({ requests: 5004, models: { 'synthetic-full-session': { head: 'other', model: 'newer-model' } } });
});
