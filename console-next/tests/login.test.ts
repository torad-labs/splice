// Walls for the sign-in flow machine (src/lib/login.ts), ported from the old console's accounts.test.ts
// ("the login flow machine") and account-login.test.ts ("renew an account under its own label"). Skipped by
// name: the two LoginTicket renderToStaticMarkup assertions (a component, not the machine), the
// pollDuringLogin, advanceSignInTab and openSignInTab tests (browser and timer glue, ported with the
// page), and refusalOf (an api-side reading of a write's answer).
import { describe, expect, test } from 'vitest';
import { IDLE, LOGIN_PENDING_EMPTY, canStart, initialLoginState, next, polling, stepMessage } from '../src/lib/login';
import { H as LOGIN, S as LABELS } from '../src/lib/words-login';
import type { LoginStatusPayload } from '../src/types/login';

// Every payload is LoginRoutes.loginStatusJson's shape, all nine keys: the start answers with it, and every
// poll does. The old console first typed a plan (login_id, flow, a landed state) the daemon never sent, so a
// login never polled and never finished against a real daemon.
const view = (state: LoginStatusPayload['state'], extra: Partial<LoginStatusPayload> = {}): LoginStatusPayload => ({
  id: 'L1', head: 'claudex', state, user_code: null, verification_uri: null, browser_url: null,
  failure_reason: null, label: null, usage_set_aside: null, ...extra,
});

test('Claude folder login destinations name the changed login without native or competing refresh instructions', () => {
  const plain = LOGIN.destination('claude', 'claude-splice');
  const own = LOGIN.destination('claude-splice', 'claude-splice');
  expect(plain).toContain('plain claude’s login');
  expect(own).toContain('claude-splice’s own login');
  for (const words of [plain, own]) {
    expect(words).not.toMatch(/native/i);
    expect(words).not.toContain('to refresh');
  }
});

describe('the login flow machine', () => {
  test('a blank label cannot start: an unnamed credential is not a credential', () => {
    expect(canStart(IDLE)).toBe(false);
    expect(canStart(next(IDLE, { kind: 'label', value: '   ' }))).toBe(false);
    expect(canStart(next(IDLE, { kind: 'label', value: 'work' }))).toBe(true);
  });

  test('the start answers starting with no code, and the login is polled by the id it answered with', () => {
    let state = next(IDLE, { kind: 'label', value: 'work' });
    state = next(state, { kind: 'start' });
    expect(state.step).toBe('starting');
    expect(polling(state)).toBe(false);
    state = next(state, { kind: 'status', payload: view('starting') });
    expect(state.step).toBe('awaiting');
    expect(polling(state)).toBe(true);
    expect(state.status?.id).toBe('L1');
    expect(stepMessage(state)).toBe(LOGIN.waiting);
  });

  test("a device flow's code and link arrive on a poll, and are held until the credential lands", () => {
    const waiting = view('waiting', { user_code: 'AB-12', verification_uri: 'https://auth.example/device' });
    const state = next(next(IDLE, { kind: 'status', payload: view('starting') }), { kind: 'status', payload: waiting });
    expect(state.step).toBe('awaiting');
    expect(state.status).toEqual(waiting);
    expect(stepMessage(state)).toBe(LOGIN.device);
  });

  test("a browser flow's link is the one the operator opens", () => {
    const state = next(IDLE, { kind: 'status', payload: view('waiting', { browser_url: 'https://auth.example/authorize?x=1' }) });
    expect(stepMessage(state)).toBe(LOGIN.browser);
  });

  test('signed in is not added: the head restarts to take the account, and the poll goes on until it has', () => {
    const signed = next(next(IDLE, { kind: 'status', payload: view('waiting') }), { kind: 'status', payload: view('signed_in') });
    expect(signed.step).toBe('landed');
    expect(stepMessage(signed)).toBe(LOGIN.afterRestart);
    expect(stepMessage(signed)).not.toBe(LOGIN.added);
    expect(polling(signed)).toBe(true);
    const live = next(signed, { kind: 'status', payload: view('live_after_restart') });
    expect(live.step).toBe('live');
    expect(stepMessage(live)).toBe(LOGIN.added);
    expect(polling(live)).toBe(false);
  });

  test('a failed login carries the daemon sentence, and the label survives for a retry', () => {
    let state = next(IDLE, { kind: 'label', value: 'work' });
    state = next(state, { kind: 'failed', note: 'device code expired' });
    expect(state.step).toBe('failed');
    expect(stepMessage(state)).toBe('device code expired');
    expect(state.label).toBe('work');
    expect(canStart(state)).toBe(true);
  });

  test("a failed status is a failure in the daemon's words, not a wait, and the poll stops", () => {
    const state = next(next(IDLE, { kind: 'status', payload: view('waiting') }), {
      kind: 'status', payload: view('failed', { failure_reason: 'login did not complete' }),
    });
    expect(state.step).toBe('failed');
    expect(stepMessage(state)).toBe('login did not complete');
    expect(polling(state)).toBe(false);
  });

  test('a pending route leaves the form and names its row', () => {
    const state = next(next(IDLE, { kind: 'label', value: 'work' }), { kind: 'pending', row: 'V4-132' });
    expect(state.step).toBe('pending');
    expect(state.note).toBe('V4-132');
    expect(stepMessage(state)).toBeNull(); // the page renders the empty, not a sentence
    expect(LOGIN_PENDING_EMPTY).toEqual({ text: LABELS.signInUnavailable, source: LOGIN.signInUnavailable });
  });

  test('reset returns the machine to its start', () => {
    expect(next({ ...IDLE, step: 'landed', label: 'work' }, { kind: 'reset' })).toEqual(IDLE);
  });
});

describe('renew an account under its own label', () => {
  const renewed = (over: Partial<LoginStatusPayload> = {}) => view('live_after_restart', {
    label: 'work', usage_set_aside: '/pool/work-quota.json.orphaned-20260928-110000', ...over,
  });

  test('the Needs you form starts with the orphaned account label, not a new one', () => {
    expect(initialLoginState('work')).toMatchObject({ label: 'work', step: 'idle' });
  });

  test('a completed renew says the old usage record was set aside and new usage is read on the next request', () => {
    const live = next({ ...IDLE, label: 'work' }, { kind: 'status', payload: renewed() });
    const message = stepMessage(live, 'renew');
    expect(message).toContain('work');
    expect(message).toContain('old usage set aside');
    expect(message).toContain('next request');
  });

  test('renewal words use the display name while the flow retains its stable label', () => {
    const live = next({ ...IDLE, label: 'work' }, { kind: 'status', payload: renewed() });
    expect(stepMessage(live, 'renew', 'Work login')).toBe(LOGIN.renewed('Work login'));
    const existing = next({ ...IDLE, label: 'work' }, { kind: 'status', payload: renewed({ usage_set_aside: null }) });
    expect(stepMessage(existing, 'renew', 'Work login')).toBe(LOGIN.renewedExisting('Work login'));
    expect(live.label).toBe('work');
    expect(live.status?.label).toBe('work');
  });

  test('a renew with no usage record to set aside says signed in again, not renewed', () => {
    const live = next({ ...IDLE, label: 'work' }, { kind: 'status', payload: renewed({ usage_set_aside: null }) });
    expect(stepMessage(live, 'renew')).toBe(LOGIN.renewedExisting('work'));
  });

  test('a refusal keeps the offered free label and does not claim the account was renewed', () => {
    const failed = next({ ...IDLE, label: 'work' }, { kind: 'status', payload: renewed({
      state: 'failed', label: null, usage_set_aside: null, failure_reason: 'Linked credential cannot use work; sign in as work-2 instead.',
    }) });
    expect(stepMessage(failed, 'renew')).toContain('work-2');
    expect(stepMessage(failed, 'renew')).not.toContain('old usage set aside');
  });
});
