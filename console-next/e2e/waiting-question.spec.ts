// NEW: V4-444 — a session waiting on the operator shows what it asked on its Sessions card, against the real daemon: the registry's
// waitingFor and entrypoint, read from files written the way Claude Code writes them, and an AskUserQuestion call read from the
// session's transcript on disk. A session held on a permission prompt says so and offers no options. The daemon's stack is unchanged;
// the two registrations and the transcript are this journey's own and are removed after it.
import { expect, test, type Page } from '@playwright/test';
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import type { SessionsPayload } from '../src/types/sessions';
import { env, open, read } from './support';

const ASKED = { id: 'e2e-asked-0000-4000-8000-000000000005', name: 'e2e-asked' } as const;
const HELD = { id: 'e2e-held-0000-4000-8000-000000000006', name: 'e2e-held' } as const;

/** The questions as Claude Code's AskUserQuestion tool takes them, descriptions and headers included. */
const QUESTIONS = [
  {
    question: 'Which plan should take the session?', header: 'Plan', multiSelect: false,
    options: [{ label: 'Max', description: 'The larger plan' }, { label: 'Pro', description: 'The smaller plan' }],
  },
  {
    question: 'Which checks should run first?', header: 'Checks', multiSelect: true,
    options: [{ label: 'Unit', description: 'The unit suite' }, { label: 'E2E', description: 'The browser journeys' }],
  },
];

const sessionsDir = (): string => join(dirname(dirname(dirname(env('CONSOLE_E2E_CONFIG')))), '.claude', 'sessions');
const transcript = (): string => join(env('CONSOLE_E2E_TRANSCRIPT_ROOT'), 'projects', 'console-e2e', ASKED.id + '.jsonl');
const registration = (name: string): string => join(sessionsDir(), name + '.json');

function register(session: { id: string; name: string }, waitingFor: string, entrypoint: string): void {
  const now = Date.now();
  writeFileSync(registration(session.name), JSON.stringify({
    pid: process.pid, sessionId: session.id, cwd: env('CONSOLE_E2E_REPO'), name: session.name, kind: 'interactive', version: '2.1.285',
    status: 'waiting', waitingFor, entrypoint, statusUpdatedAt: now, startedAt: now, updatedAt: now, messagingSocketPath: null,
  }));
}

const card = (page: Page, name: string) => page.locator('li.card', { has: page.getByRole('link', { name, exact: true }) });

test.beforeAll(() => {
  mkdirSync(dirname(transcript()), { recursive: true, mode: 0o700 });
  const records = [
    { type: 'user', message: { role: 'user', content: 'Set the session up.' } },
    {
      type: 'assistant',
      message: { id: 'msg_e2e_asked', role: 'assistant', content: [{ type: 'tool_use', id: 'toolu_e2e_asked', name: 'AskUserQuestion', input: { questions: QUESTIONS } }] },
    },
  ];
  writeFileSync(transcript(), records.map((record) => JSON.stringify(record)).join('\n') + '\n', { mode: 0o600 });
  register(ASKED, 'input needed', 'cli');
  register(HELD, 'permission prompt', 'eli-telegram');
});

test.afterAll(() => {
  for (const name of [ASKED.name, HELD.name]) rmSync(registration(name), { force: true });
  rmSync(transcript(), { force: true });
});

test('a waiting session\'s card shows its questions with their options and where to answer; a permission prompt offers none', async ({ page }) => {
  const faults = await open(page, 'sessions');
  const rows = (await read<SessionsPayload>(page, '/api/sessions')).sessions;
  const asked = rows.find((row) => row.session_id === ASKED.id);
  expect(asked?.waiting_for).toBe('input needed');
  expect(asked?.entrypoint).toBe('cli');
  expect(asked?.last?.asks).toEqual([
    { question: 'Which plan should take the session?', options: ['Max', 'Pro'], multi: false },
    { question: 'Which checks should run first?', options: ['Unit', 'E2E'], multi: true },
  ]);

  const askedCard = card(page, ASKED.name);
  await expect(askedCard.getByText('Which plan should take the session?', { exact: true })).toBeVisible();
  await expect(askedCard.getByText('Which checks should run first?', { exact: true })).toBeVisible();
  await expect(askedCard.locator('.ask-options li')).toHaveText(['Max', 'Pro', 'Unit', 'E2E']);
  await expect(askedCard.getByText('Choose any', { exact: true })).toHaveCount(1);
  await expect(askedCard.getByText('The larger plan')).toHaveCount(0);
  await expect(askedCard.getByText('Answer in its terminal', { exact: true })).toBeVisible();

  const heldCard = card(page, HELD.name);
  await expect(heldCard.getByText(/^Waiting for your permission/)).toBeVisible();
  await expect(heldCard.locator('.ask-options li')).toHaveCount(0);
  await expect(heldCard.getByText('Answer in eli-telegram', { exact: true })).toBeVisible();

  expect(faults.pageErrors, 'uncaught page errors').toEqual([]);
  expect(faults.failedReads, 'refused reads').toEqual([]);
});
