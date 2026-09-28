import { describe, expect, test } from 'vitest';
import { IDLE, initialLoginState, next, stepMessage } from '../src/features/account-login/model';
import type { LoginStatusPayload } from '../src/entities/auth';

const status = (over: Partial<LoginStatusPayload> = {}): LoginStatusPayload => ({
  id: 'login-1', head: 'claudex', state: 'live_after_restart',
  user_code: null, verification_uri: null, browser_url: null, failure_reason: null,
  label: 'work', usage_set_aside: '/pool/work-quota.json.orphaned-20260928-110000', ...over,
});

describe('renew an account under its own label', () => {
  test('the Needs you form starts with the orphaned account label, not a new one', () => {
    expect(initialLoginState('work')).toMatchObject({ label: 'work', step: 'idle' });
  });

  test('a completed renew says the old usage record was set aside and new usage is read on the next request', () => {
    const live = next({ ...IDLE, label: 'work' }, { kind: 'status', payload: status() });
    const message = stepMessage(live, 'renew');
    expect(message).toContain('work');
    expect(message).toContain('old usage set aside');
    expect(message).toContain('next request');
  });

  test('a refusal keeps the offered free label and does not claim the account was renewed', () => {
    const failed = next({ ...IDLE, label: 'work' }, { kind: 'status', payload: status({ state: 'failed', label: null,
      usage_set_aside: null, failure_reason: "Linked credential cannot use work; sign in as work-2 instead." }) });
    expect(stepMessage(failed, 'renew')).toContain('work-2');
    expect(stepMessage(failed, 'renew')).not.toContain('old usage set aside');
  });
});
