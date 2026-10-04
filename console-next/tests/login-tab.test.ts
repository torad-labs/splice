// The sign-in tab: opened in the click, pointed at the provider once announced, closed when the login ends without a page.
import { describe, expect, test, vi } from 'vitest';
import { advanceSignInTab, openSignInTab, providerPage } from '../src/lib/login-tab';
import type { SignInTab } from '../src/lib/login-tab';
import type { LoginView } from '../src/types/core';

const tab = (): SignInTab & { closed: boolean } => {
  const made = { opener: {} as unknown, document: { title: '', body: { textContent: '' } as { textContent: string | null } | null }, location: { href: '' }, closed: false, close: () => { made.closed = true; } };
  return made;
};
const view = (over: Partial<LoginView> = {}): LoginView => ({ id: 'l1', state: 'starting', user_code: null, verification_uri: null, browser_url: null, failure_reason: null, ...over });

describe('the provider page', () => {
  test('is an https address and nothing else', () => {
    expect(providerPage('https://signin.example.com/authorize?x=1')).toBe('https://signin.example.com/authorize?x=1');
    for (const bad of ['http://signin.example.com', 'javascript:alert(1)', 'not a url', '', null]) expect(providerPage(bad)).toBeNull();
  });
});

describe('the sign-in tab', () => {
  test('opens blank in a new tab, cut off from the console, saying what it waits for', () => {
    const made = tab();
    const open = vi.fn(() => made);
    expect(openSignInTab(open)).toBe(made);
    expect(open).toHaveBeenCalledWith('', '_blank');
    expect(made.opener).toBeNull();
    expect(made.document.title).toBe('Opening sign-in');
    expect(made.document.body?.textContent).toContain('Opening sign-in');
  });
  test('is null when the browser refused it', () => {
    expect(openSignInTab(() => null)).toBeNull();
  });
  test('waits while the daemon has announced nothing', () => {
    const made = tab();
    expect(advanceSignInTab(made, view(), null)).toBe(made);
    expect(advanceSignInTab(made, null, null)).toBe(made);
    expect(made.closed).toBe(false);
  });
  test('goes to the announced page once, from the browser url or the device flow link, and is let go', () => {
    const browser = tab();
    expect(advanceSignInTab(browser, view({ state: 'waiting', browser_url: 'https://a.example/auth' }), null)).toBeNull();
    expect(browser.location.href).toBe('https://a.example/auth');
    const device = tab();
    expect(advanceSignInTab(device, view({ state: 'waiting', verification_uri: 'https://b.example/device', user_code: 'ABCD' }), null)).toBeNull();
    expect(device.location.href).toBe('https://b.example/device');
    expect(browser.closed || device.closed).toBe(false);
  });
  test('closes when a page was announced that is not safe to open, when the login failed or finished, or when the request failed', () => {
    const cases: [LoginView | null, string | null][] = [
      [view({ state: 'waiting', browser_url: 'http://a.example' }), null],
      [view({ state: 'failed' }), null],
      [view({ state: 'signed_in' }), null],
      [view({ state: 'live_after_restart' }), null],
      [view(), 'the daemon refused'],
    ];
    for (const [status, error] of cases) {
      const made = tab();
      expect(advanceSignInTab(made, status, error)).toBeNull();
      expect(made.closed).toBe(true);
      expect(made.location.href).toBe('');
    }
  });
  test('nothing to do without a tab', () => {
    expect(advanceSignInTab(null, view({ browser_url: 'https://a.example' }), null)).toBeNull();
  });
});
