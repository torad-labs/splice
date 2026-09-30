// NEW: V4-444 — the session page's note box against the real daemon: a note reaches a live session's inbox socket as the frame Claude Code
// reads, and a refusal shows the daemon's own sentence with the draft kept. The listener is this test's own socket, registered the way
// Claude Code registers a session; the daemon's stack is unchanged.
import { expect, test, type Page } from '@playwright/test';
import { mkdtempSync, rmSync, writeFileSync, chmodSync } from 'node:fs';
import { createServer, type Server } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { STACK } from './stack';
import { env, open, type watch } from './support';

const NOTED = { id: 'e2e-noted-0000-4000-8000-000000000003', name: 'e2e-noted' } as const;
const NOTE = 'Run the gate, then tell me the sha.';
const BOX = 'Send a note to this session';

const sessionsDir = (): string => join(dirname(dirname(dirname(env('CONSOLE_E2E_CONFIG')))), '.claude', 'sessions');

/** The page threw nothing, and the only refusals the daemon gave are the ones this journey asked for (each logs one browser resource error). */
async function healthyExcept(page: Page, faults: ReturnType<typeof watch>, refused: string[]): Promise<void> {
  await expect(page.getByRole('main')).not.toBeEmpty();
  expect(faults.pageErrors, 'uncaught page errors').toEqual([]);
  expect(faults.failedReads, 'the refusals the daemon gave').toEqual(refused);
  expect(faults.consoleErrors, 'browser resource errors').toHaveLength(refused.length);
}

let dir = '';
let server: Server | null = null;
let registration = '';
const received: Promise<string>[] = [];

test.beforeAll(async () => {
  // mkdtemp makes the directory 0700, and the socket is narrowed to 0600: the two checks the daemon makes before it writes.
  dir = mkdtempSync(join(tmpdir(), 'note-'));
  const socket = join(dir, 'noted.sock');
  server = createServer((connection) => {
    received.push(new Promise((resolve) => {
      const chunks: Buffer[] = [];
      connection.on('data', (chunk: Buffer) => chunks.push(chunk));
      connection.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    }));
  });
  await new Promise<void>((resolve) => server?.listen(socket, resolve));
  chmodSync(socket, 0o600);
  const now = Date.now();
  registration = join(sessionsDir(), NOTED.name + '.json');
  writeFileSync(registration, JSON.stringify({
    pid: process.pid, sessionId: NOTED.id, cwd: env('CONSOLE_E2E_REPO'), name: NOTED.name, kind: 'interactive', version: '2.1.285',
    status: 'idle', startedAt: now, updatedAt: now, messagingSocketPath: socket,
  }));
});

test.afterAll(async () => {
  rmSync(registration, { force: true });
  await new Promise<void>((resolve) => (server === null ? resolve() : server.close(() => resolve())));
  rmSync(dir, { recursive: true, force: true });
});

test('a note typed on a live session reaches its inbox as a message from another session, and the page says only that it was sent', async ({ page }) => {
  const faults = await open(page, 'sessions/' + NOTED.id);
  const box = page.getByRole('textbox', { name: BOX, exact: true });
  await expect(page.getByText('It reaches the session as a message from another session, not as you typing.')).toBeVisible();
  const send = page.getByRole('button', { name: 'Send the note', exact: true });
  await expect(send).toBeDisabled();
  await box.fill(NOTE);
  await send.click();
  await expect(page.getByRole('status').filter({ hasText: 'Sent.' })).toHaveText('Sent. splice cannot tell whether the session has read it yet.');
  await expect(box).toHaveValue('');
  const frame = JSON.parse(await received[0] ?? '') as { type: string; priority: string; message: { role: string; content: string } };
  expect(frame.type).toBe('user');
  expect(frame.priority).toBe('next');
  expect(frame.message.role).toBe('user');
  expect(frame.message.content).toBe('<cross-session-message from-name="the splice console">\n' + NOTE + '\n</cross-session-message>');
  expect(received, 'one note is one connection').toHaveLength(1);
  // The noted session has no transcript on disk: the daemon's own 404, which the page prints as such.
  await healthyExcept(page, faults, ['404 /api/sessions/' + NOTED.id + '/transcript']);
});

test('a session on a Claude Code version the daemon has not checked refuses the note in its own words, and the draft stays', async ({ page }) => {
  const faults = await open(page, 'sessions/' + STACK.sender.id);
  const box = page.getByRole('textbox', { name: BOX, exact: true });
  await box.fill(NOTE);
  await page.getByRole('button', { name: 'Send the note', exact: true }).click();
  await expect(page.getByRole('status').filter({ hasText: 'notes are only sent to' })).toContainText('the session runs Claude Code of unknown version');
  await expect(box).toHaveValue(NOTE);
  await box.fill(NOTE + ' Again.');
  await expect(page.getByRole('status').filter({ hasText: 'notes are only sent to' })).toHaveCount(0);
  await healthyExcept(page, faults, ['422 /api/sessions/' + STACK.sender.id + '/message']);
});

test('a session that is not running has no box, only the reason', async ({ page }) => {
  // A second registration, dead: a pid no process has.
  const gone = join(sessionsDir(), 'e2e-gone.json');
  writeFileSync(gone, JSON.stringify({
    pid: 2_147_483_000, sessionId: 'e2e-gone-0000-4000-8000-000000000004', cwd: env('CONSOLE_E2E_REPO'), name: 'e2e-gone', kind: 'interactive',
    status: 'idle', startedAt: Date.now() - 60_000, updatedAt: Date.now() - 60_000, messagingSocketPath: join(dir, 'noted.sock'),
  }));
  try {
    const faults = await open(page, 'sessions/e2e-gone-0000-4000-8000-000000000004');
    await expect(page.getByText('This session is not running, so it cannot take a note.')).toBeVisible();
    await expect(page.getByRole('textbox', { name: BOX, exact: true })).toHaveCount(0);
    await healthyExcept(page, faults, ['404 /api/sessions/e2e-gone-0000-4000-8000-000000000004/transcript']);
  } finally {
    rmSync(gone, { force: true });
  }
});
