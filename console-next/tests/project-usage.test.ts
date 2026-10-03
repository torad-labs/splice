import { expect, test } from 'vitest';
import { projectUsage } from '../src/lib/project-teams';
import type { SessionRow } from '../src/types/sessions';
import type { TurnRow } from '../src/types/perf';

const sessions = [{ session_id: 'synthetic-full-session' }] as SessionRow[];
const row = (over: Partial<TurnRow> = {}): TurnRow => ({ head: 'shared-command', session_id: 'synthetic-full-session', ts: 1, model: 'actual-model', compact: false, outcome: 'ok', cost_usd: 0.25, in_tokens: 100, out_tokens: 20, ...over });

test('project usage is session attributed, not every request through a shared command', () => {
  const missing = row();
  delete missing.session_id;
  const value = projectUsage(sessions, [row(), row({ session_id: 'another-project' }), missing, row({ local_step: 1 })]);
  expect(value).toMatchObject({ requests: 1, input: 100, output: 20, cost: 0.25, unpriced: 0 });
});

test('a missing price remains missing and the last model comes from a real non-compaction request', () => {
  const value = projectUsage(sessions, [row({ cost_usd: null }), row({ ts: 2, model: 'next-model', cost_usd: null }), row({ ts: 3, compact: true, model: 'compactor', cost_usd: null })]);
  expect(value).toMatchObject({ requests: 3, cost: null, unpriced: 3 });
  expect(value.models['synthetic-full-session']).toEqual({ head: 'shared-command', model: 'next-model' });
});

test('short session tags cannot masquerade as full session identity', () => {
  const legacy = row();
  delete legacy.session_id;
  legacy.session = 'syntheti';
  expect(projectUsage(sessions, [legacy]).requests).toBe(0);
});
