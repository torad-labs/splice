// EVERY WRITE SAYS HOW IT ENDED (Marlin's HOLD, 2026-09-25, condition 1). putBudgets, putAlerts and
// sendTestAlert answered `null` or `false` from their catch, and their panels read any answer as
// success: a refused budget printed "Saved" and a failed test printed "Sent". Each write now answers
// a WriteResult, and each is held here against a daemon that refuses it, one that has not built its
// route, and one that applies it. The wall `webui-write-never-swallows` holds the class.
import { afterEach, describe, expect, test, vi } from 'vitest';
import { writeNote } from '../src/shared/lib';
import type { WriteResult } from '../src/shared/lib';
import { putBudgets } from '../src/entities/budget';
import { budgetsStore } from '../src/entities/budget/model/store';
import { putAlerts, sendTestAlert } from '../src/entities/alert';
import { alertsStore } from '../src/entities/alert/model/store';
import type { AlertSettings } from '../src/entities/alert';
import type { BudgetsPayload } from '../src/entities/budget';

const BUDGETS: BudgetsPayload = { budgets: [{ head: 'claudex', daily_usd: 5, action: 'warn' }] };
const SETTINGS: AlertSettings = { webhook_url: 'https://hooks.example/a', desktop: false };

/** Every request answers `status` with `body`. */
function answer(status: number, body: unknown): void {
  vi.stubGlobal('fetch', () => Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })));
}

afterEach(() => vi.unstubAllGlobals());

const ACTIONS: { name: string; run: () => Promise<WriteResult<unknown>>; seed: () => void; held: () => unknown; was: unknown; applied: unknown }[] = [
  {
    name: 'a budget save',
    run: () => putBudgets([{ head: 'claudex', daily_usd: 900, action: 'block' }]),
    seed: () => budgetsStore.setData(BUDGETS),
    held: () => budgetsStore.get().data,
    was: BUDGETS,
    applied: { budgets: [{ head: 'claudex', daily_usd: 900, action: 'block' }] },
  },
  {
    name: 'an alerts save',
    run: () => putAlerts({ ...SETTINGS, webhook_url: 'https://hooks.example/b' }),
    seed: () => alertsStore.setData(SETTINGS),
    held: () => alertsStore.get().data,
    was: SETTINGS,
    applied: { ...SETTINGS, webhook_url: 'https://hooks.example/b' },
  },
  {
    name: 'a test send',
    run: () => sendTestAlert(),
    seed: () => alertsStore.setData(SETTINGS),
    held: () => alertsStore.get().data,
    was: SETTINGS,
    applied: null,
  },
];

describe.each(ACTIONS)('$name', (action) => {
  test('a refused request is failed with the daemon\'s words, and the store keeps what it last answered', async () => {
    action.seed();
    answer(422, { error: 'the daemon refused this write' });
    const result = await action.run();
    expect(result).toEqual({ status: 'failed', reason: 'the daemon refused this write' });
    expect(action.held()).toEqual(action.was);
    expect(writeNote(result, { done: 'Saved', pending: 'Unavailable' })).toEqual({ text: 'the daemon refused this write', failed: true });
  });

  test('a route not built yet is pending on its item, never a success', async () => {
    action.seed();
    answer(404, { error: 'unknown route' });
    const result = await action.run();
    expect(result).toEqual({ status: 'pending', item: 'V4-133' });
    expect(writeNote(result, { done: 'Saved', pending: 'Unavailable' })).toEqual({ text: 'Unavailable', failed: true });
  });

  test('an applied request carries the daemon\'s answer, and only it reads as done', async () => {
    action.seed();
    answer(200, action.applied);
    const result = await action.run();
    expect(result).toEqual({ status: 'applied', answer: action.applied });
    expect(writeNote(result, { done: 'Saved', pending: 'Unavailable' })).toEqual({ text: 'Saved', failed: false });
  });
});
