// V4-220 item 4's console half: upgrade and roll back from Doctor, over POST /api/upgrade and GET
// /api/upgrade/run (UpgradeRunRoutes.kt, #303).
//
// THE WIRE IS READ FROM THE KOTLIN, as the add's is (tests/add-backend.test.ts): a run exists only
// after a POST starts `splice upgrade` for real, so the wire-keys probe never reads one, and a type
// written from a plan would agree with every fixture written from it.
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { UPGRADE_RUN_STATES, readUpgradeRun, startUpgrade } from '../src/entities/doctor';
import type { UpgradeAsk, UpgradePayload, UpgradeRun } from '../src/entities/doctor';
import { DaemonUpgrade, RunView } from '../src/features/daemon-upgrade';
import { askOf, commandOf, rollbackTarget } from '../src/features/daemon-upgrade/model';
import { H, S } from '../src/features/daemon-upgrade/strings';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const UPGRADE = path.join(repoRoot, 'features/lifecycle/src/main/kotlin/splice/lifecycle/upgrade');
const ROUTES = readFileSync(path.join(UPGRADE, 'UpgradeRunRoutes.kt'), 'utf8');
const RUNS = readFileSync(path.join(UPGRADE, 'UpgradeRuns.kt'), 'utf8');

/** The keys one member of UpgradeRunRoutes puts: `put` and `putJsonArray` in its body. */
function keysOf(signature: string): string[] {
  const head = ROUTES.indexOf(`fun ${signature}`);
  if (head < 0) throw new Error(`no fun ${signature} in UpgradeRunRoutes.kt`);
  const body = ROUTES.slice(head, ROUTES.indexOf('\n    }', head));
  return [...new Set([...body.matchAll(/put(?:JsonArray)?\("([a-z_]+)"/g)].flatMap(([, key]) => (key === undefined ? [] : [key])))].sort();
}

// `Required` holds each object to its whole interface, so a key a type gains or loses moves here.
const RUN: Required<UpgradeRun> = {
  id: '0001790000000000-ab12', args: ['upgrade', '--to', 'v0.4.1'], state: 'running',
  started_at_epoch_millis: 1_790_000_000_000, exit_code: null, output: ['splice upgrade: 0.4.0 -> 0.4.1', 'fetching splice-0.4.1.jar'],
};
const ASK: Required<UpgradeAsk> = { to: 'v0.4.1', rollback: true };

describe('the upgrade run\'s wire is UpgradeRunRoutes\' own', () => {
  test('a run view puts exactly UpgradeRun\'s keys', () => {
    expect(keysOf('view(')).toEqual(Object.keys(RUN).sort());
  });

  test('a run\'s state is one of the words UpgradeRunState writes', () => {
    const words = [...RUNS.matchAll(/^\s+[A-Z]+\("([a-z]+)"\),?$/gm)].flatMap(([, word]) => (word === undefined ? [] : [word])).sort();
    expect(words).toEqual([...UPGRADE_RUN_STATES].sort());
  });

  test('the start reads exactly the fields an ask can carry', () => {
    const read = [...ROUTES.matchAll(/body\["([a-z_]+)"\]/g)].flatMap(([, key]) => (key === undefined ? [] : [key])).sort();
    expect(read).toEqual(Object.keys(ASK).sort());
  });
});

interface Sent {
  path: string;
  method: string;
  body: string | undefined;
}

function daemon(status: number, body: unknown, sent: Sent[]): void {
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    sent.push({ path: url, method: init?.method ?? 'GET', body: typeof init?.body === 'string' ? init.body : undefined });
    return { ok: status >= 200 && status < 300, status, json: async () => body };
  });
}

describe('the upgrade through the real client', () => {
  afterEach(() => vi.unstubAllGlobals());

  test('a start is one POST of the ask, and its 202 answer is the run', async () => {
    const sent: Sent[] = [];
    daemon(202, { run: RUN }, sent);
    expect(await startUpgrade({ to: 'v0.4.1' })).toEqual(RUN);
    expect(sent).toEqual([{ path: '/api/upgrade', method: 'POST', body: '{"to":"v0.4.1"}' }]);
  });

  test('a refused start rejects with the daemon\'s sentence', async () => {
    daemon(409, { error: 'An upgrade is already running.' }, []);
    await expect(startUpgrade({})).rejects.toThrow('An upgrade is already running.');
  });

  test('a read is the run, null before any, and away when nothing answers', async () => {
    daemon(200, { run: null }, []);
    expect(await readUpgradeRun()).toEqual({ run: null });
    vi.stubGlobal('fetch', async () => {
      throw new TypeError('Failed to fetch');
    });
    expect(await readUpgradeRun(), 'the restart the run causes').toEqual({ away: true });
  });

  test('a daemon that answers a read with a refusal is up and said no', async () => {
    daemon(503, { error: 'Upgrading from the console is not wired on this daemon.' }, []);
    await expect(readUpgradeRun()).rejects.toThrow('not wired on this daemon');
  });
});

const STATUS: UpgradePayload = {
  installed: '0.4.0', latest: null, latest_basis: 'unavailable', latest_unavailable_reason: 'no upgrade check has succeeded',
  rollback_target: '0.3.9', rollback_basis: 'measured', rollback_unavailable_reason: null, checked_at_epoch_millis: null,
};

describe('the form\'s rules', () => {
  test('a blank release box asks for the latest release, and a filled one for that release', () => {
    expect(askOf('  ')).toEqual({});
    expect(askOf(' v0.4.1 ')).toEqual({ to: 'v0.4.1' });
  });

  test('a rollback is offered only for a release the daemon found on disk', () => {
    expect(rollbackTarget(STATUS)).toBe('0.3.9');
    expect(rollbackTarget({ ...STATUS, rollback_target: null })).toBeNull();
    expect(rollbackTarget({ ...STATUS, rollback_basis: 'unavailable', rollback_unavailable_reason: 'no releases dir' })).toBeNull();
    expect(rollbackTarget(null)).toBeNull();
  });

  test('a run reads as the command it is', () => {
    expect(commandOf(RUN)).toBe('splice upgrade --to v0.4.1');
  });
});

describe('the form and a run, rendered', () => {
  const form = (upgrade: UpgradePayload | null) => renderToStaticMarkup(createElement(DaemonUpgrade, { upgrade }));
  const view = (run: UpgradeRun, away = false) => renderToStaticMarkup(createElement(RunView, { run, away }));

  test('the upgrade key is always there, and the rollback key only beside a release to go back to', () => {
    expect(form(STATUS)).toContain(`>${S.upgrade}<`);
    expect(form(STATUS)).toContain(`>${S.rollback}<`);
    expect(form({ ...STATUS, rollback_target: null })).not.toContain(`>${S.rollback}<`);
    expect(form(null)).toContain(`>${S.upgrade}<`);
  });

  test('a running run prints its command, its state and its output as a log', () => {
    const out = view(RUN);
    expect(out).toContain('>splice upgrade --to v0.4.1<');
    expect(out).toContain(`>${S.state.running}<`);
    expect(out).toContain('role="log"');
    expect(out).toContain('fetching splice-0.4.1.jar');
    expect(out).not.toContain(H.away);
  });

  test('a read nothing answered says the daemon is restarting', () => {
    expect(view(RUN, true)).toContain(H.away);
  });

  test('an ended run says its exit, and each end says what follows it', () => {
    const succeeded = view({ ...RUN, state: 'succeeded', exit_code: 0 });
    expect(succeeded).toContain(`>${S.exit(0)}<`);
    expect(succeeded).toContain(H.reload);
    expect(view({ ...RUN, state: 'lost' })).toContain(H.lost);
    expect(view({ ...RUN, state: 'failed', exit_code: 1, output: [] })).toContain(H.quiet);
  });
});
