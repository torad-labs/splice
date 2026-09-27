// V4-346: the first hour in a browser, with fake profiles and a provider page intercepted locally.
import { expect, test } from '@playwright/test';
import type { AddProfile, AddView } from '../src/entities/add';

const SIGN_IN = 'https://signin.fixture.invalid/authorize';
const profiles: AddProfile[] = ['codex', 'grok', 'kimi', 'muse', 'openrouter', 'local', 'deepseek'].map((name) => ({
  name, summary: name === 'codex' ? 'ChatGPT subscription over the Responses API' : `${name} plan`,
  auth_kind: name === 'openrouter' || name === 'local' || name === 'deepseek' ? 'api-key' : 'chatgpt-oauth',
  requires_key: name === 'openrouter' || name === 'deepseek',
  base_url: name === 'local' ? null : 'https://api.example.invalid',
  head_key: name, command: name === 'codex' ? 'claudex' : `claude-${name}`,
  models: [], asks: name === 'local' ? ['name', 'base_url', 'models'] : [],
}));
const add: AddView = {
  id: 'add-1', profile: 'codex', key: 'codex', command: 'claudex', auth_kind: 'chatgpt-oauth',
  base_url: 'https://api.example.invalid', models: [], sign_in_by: 'login', key_env: null,
  credential: { present: false, detail: 'Sign in to ChatGPT.' }, sign_in: null, checks: null, saved: null,
};

function report() {
  return {
    schema_version: 1, generated_at: new Date().toISOString(), splice: { version: '0.4.0' },
    claude_code: { version: '2' }, os: { name: 'Linux', version: '6', arch: 'x64' },
    jvm: { version: '21', vendor: 'test' }, checks: [],
  };
}

test('a fresh home connects ChatGPT in one browser gesture and returns a command', async ({ page }) => {
  const base = process.env.CONSOLE_E2E_BASE;
  const key = process.env.CONSOLE_E2E_KEY;
  if (!base || !key) throw new Error('the isolated console stack did not start');
  await page.addInitScript(([storage, value]) => localStorage.setItem(storage, value), ['myx-mgmt-key', key]);
  await page.context().route('**/*', (route) => {
    const host = new URL(route.request().url()).hostname;
    if (host === 'signin.fixture.invalid') return route.fulfill({
      contentType: 'text/html', body: '<h1>Provider sign-in</h1>',
    });
    if (host === '127.0.0.1' || host === 'localhost') return route.continue();
    return route.abort();
  });
  await page.route((url) => url.pathname === '/api/heads', (route) => route.fulfill({ json: { heads: [] } }));
  await page.route((url) => url.pathname === '/api/auth', (route) => route.fulfill({ json: {} }));
  await page.route((url) => url.pathname === '/api/accounts', (route) => route.fulfill({ json: { accounts: [] } }));
  await page.route((url) => url.pathname === '/api/usage', (route) => route.fulfill({
    json: { window_hours: 5, warn_pct: 80, warn_tokens_5h: 0, heads: [] },
  }));
  await page.route((url) => url.pathname === '/api/sessions', (route) => route.fulfill({ json: { note: '', sessions: [] } }));
  await page.route((url) => url.pathname === '/api/teams', (route) => route.fulfill({ json: { teams: [] } }));
  await page.route((url) => url.pathname === '/api/doctor', (route) => route.fulfill({ json: report() }));
  await page.route((url) => url.pathname === '/api/add/profiles', (route) => route.fulfill({ json: { profiles } }));
  await page.route((url) => url.pathname === '/api/add', (route) => route.fulfill({ json: add }));
  const waiting: AddView = {
    ...add,
    sign_in: {
      id: 'login-1', state: 'waiting', browser_url: SIGN_IN, verification_uri: null,
      user_code: null, failure_reason: null,
    },
  };
  let loginStarted = false;
  await page.route((url) => url.pathname === '/api/add/add-1/login', (route) => {
    loginStarted = true;
    return route.fulfill({ json: waiting });
  });
  await page.route((url) => url.pathname === '/api/add/add-1', (route) => route.fulfill({ json: loginStarted ? waiting : add }));
  await page.route((url) => url.pathname === '/api/add/add-1/save', (route) => route.fulfill({
    json: { ...waiting, credential: { present: true, detail: 'Signed in' },
      saved: { path: '/work/splice.toml', wrapper: { linked: true }, restart: { status: 'draining' } } },
  }));

  await page.goto(`${base}/#/needs-you`);
  await expect(page.getByRole('heading', { name: 'Connect a plan' })).toBeVisible();
  await expect(page.locator('.myx-dt-tone-danger')).toHaveCount(0);
  await expect(page.locator('.myx-rule-state')).toContainText('Not set up');
  await page.getByRole('button', { name: 'Other providers', exact: true }).click();
  await expect(page.getByRole('combobox', { name: 'Profile' })).toBeVisible();
  await page.getByRole('button', { name: 'All plans', exact: true }).click();
  await page.getByRole('button', { name: 'ChatGPT', exact: true }).click();
  await expect(page.getByText('ChatGPT subscription over the Responses API')).toBeVisible();
  await expect(page.getByRole('combobox', { name: 'Profile' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Continue', exact: true }).click();
  const popupReady = page.waitForEvent('popup');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  const popup = await popupReady;
  await expect(popup).toHaveURL(SIGN_IN);
  await expect(popup.getByRole('heading', { name: 'Provider sign-in' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Open sign-in page' })).toBeVisible();
  await page.getByRole('button', { name: 'Save backend', exact: true }).click();
  await page.getByRole('button', { name: 'Save and restart', exact: true }).click();
  await expect(page.getByText('Type this command in your terminal after the restart.')).toBeVisible();
  await expect(page.locator('.myx-add-command code')).toHaveText('claudex');
});
