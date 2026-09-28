// V4-346: the first hour in a browser, with fake profiles and a provider page intercepted locally.
import { expect, test } from '@playwright/test';
import type { AddProfile, AddView } from '../src/entities/add';
import type { HeadStatus } from '../src/shared/api';

const SIGN_IN = 'https://signin.fixture.invalid/authorize';
const profiles: AddProfile[] = ['codex', 'grok', 'kimi', 'muse', 'openrouter', 'local', 'deepseek'].map((name) => ({
  name, summary: name === 'codex' ? 'ChatGPT subscription over the Responses API' : `${name} plan`,
  auth_kind: name === 'openrouter' || name === 'local' || name === 'deepseek' ? 'api-key' : 'chatgpt-oauth',
  requires_key: name === 'openrouter' || name === 'deepseek',
  base_url: name === 'local' ? null : 'https://api.example.invalid',
  head_key: name, command: name === 'codex' ? 'claudex' : `claude-${name}`,
  models: [], asks: name === 'local' ? ['name', 'base_url', 'models'] : [],
}));
const connectedHead: HeadStatus = {
  key: 'codex', label: 'claudex', name: 'codex', port: 3099, authKind: 'chatgpt-oauth',
  wantVersion: '0.4.0', running: true, healthy: true, version: '0.4.0', versionMatch: true,
  mode: null, gate: null, maxInflight: 4, health: { localOriginErrors: 0, providerErrors: 0 }, pids: [1],
};
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
  const pageErrors: string[] = [];
  page.on('pageerror', (error) => pageErrors.push(error.name));
  await page.addInitScript(([storage, value]) => localStorage.setItem(storage, value), ['myx-mgmt-key', key]);
  await page.addInitScript(() => {
    const actualOpen = window.open.bind(window);
    const probe = window as Window & { __loginWindowReturned?: boolean };
    probe.__loginWindowReturned = false;
    window.open = (...args: Parameters<typeof window.open>) => {
      const opened = actualOpen(...args);
      probe.__loginWindowReturned = opened !== null;
      return opened;
    };
  });
  await page.context().route('**/*', (route) => {
    const host = new URL(route.request().url()).hostname;
    if (host === 'signin.fixture.invalid') return route.fulfill({
      contentType: 'text/html', body: '<h1>Provider sign-in</h1>',
    });
    if (host === '127.0.0.1' || host === 'localhost') return route.continue();
    return route.abort();
  });
  let headReady = false;
  await page.route((url) => url.pathname === '/api/heads', (route) => route.fulfill({ json: { heads: headReady ? [connectedHead] : [] } }));
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
  const announced: NonNullable<AddView['sign_in']> = {
    id: 'login-1', state: 'waiting', browser_url: SIGN_IN, verification_uri: null,
    user_code: null, failure_reason: null,
  };
  const waiting: AddView = { ...add, sign_in: announced };
  const starting: AddView = { ...add, sign_in: { ...announced, state: 'starting', browser_url: null } };
  const signedIn: AddView = { ...waiting, credential: { present: true, detail: 'Signed in' },
    sign_in: { ...announced, state: 'signed_in' } };
  let loginStarted = false;
  let loginPolls = 0;
  let saves = 0;
  await page.route((url) => url.pathname === '/api/add/add-1/login', (route) => {
    loginStarted = true;
    return route.fulfill({ json: starting });
  });
  await page.route((url) => url.pathname === '/api/add/add-1', (route) => {
    if (!loginStarted) return route.fulfill({ json: add });
    loginPolls++;
    return route.fulfill({ json: loginPolls === 1 ? starting : loginPolls === 2 ? waiting : signedIn });
  });
  await page.route((url) => url.pathname === '/api/add/add-1/save', (route) => {
    saves++;
    headReady = true;
    return route.fulfill({ json: { ...signedIn,
      saved: { path: '/work/splice.toml', wrapper: { linked: true }, restart: { status: 'draining' } } },
    });
  });
  let tryRuns = 0;
  await page.route((url) => url.pathname === '/api/playground', (route) => {
    const body = route.request().postDataJSON() as { head: string; prompt: string };
    if (body.head !== 'codex' || body.prompt !== 'Say hello and name your model.') {
      return route.fulfill({ status: 400, json: { error: 'Wrong provider test target' } });
    }
    tryRuns++;
    return route.fulfill({ json: {
      request: { url: 'https://api.example.invalid/responses', method: 'POST', headers: {}, body: { model: 'gpt-6-sol' } },
      response: tryRuns === 1
        ? { status: 200, body: { model: 'gpt-6-sol', output: [{ content: [{ type: 'output_text', text: 'Hello from Sol' }] }] } }
        : { status: 429, body: { error: { message: 'Limit reached' } } },
    } });
  });

  await page.goto(`${base}/#/needs-you`);
  await expect(page.getByRole('heading', { name: 'Connect a plan' })).toBeVisible();
  await expect(page.locator('.myx-dt-tone-danger')).toHaveCount(0);
  await expect(page.locator('.myx-rule-state')).toContainText('Not set up');
  await page.getByRole('button', { name: 'Other providers', exact: true }).click();
  await expect(page.getByRole('combobox', { name: 'Profile' })).toBeVisible();
  await page.getByRole('button', { name: 'All plans', exact: true }).click();
  await expect(page.getByRole('button', { name: 'ChatGPT', exact: true })).toBeVisible();
  await page.getByText('Sign in with your ChatGPT plan.', { exact: true }).click();
  await expect(page.getByText('ChatGPT subscription over the Responses API')).toBeVisible();
  await expect(page.getByRole('combobox', { name: 'Profile' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Continue', exact: true }).click();
  const popupReady = page.waitForEvent('popup');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  const popup = await popupReady;
  // A real foreground sign-in tab hides the console. Keep that state even when a headless
  // browser happens to leave both tabs visible, so the late URL requires an unpaused poll.
  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await expect(page.getByRole('link', { name: 'Open sign-in page' })).toBeVisible({ timeout: 10_000 });
  expect(await page.evaluate(() => (window as Window & { __loginWindowReturned?: boolean }).__loginWindowReturned)).toBe(true);
  expect(popup.isClosed()).toBe(false);
  expect(pageErrors).toEqual([]);
  await expect(popup).toHaveURL(SIGN_IN);
  await expect(popup.getByRole('heading', { name: 'Provider sign-in' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Save backend', exact: true })).toHaveCount(0);
  await expect.poll(() => saves, { timeout: 15_000 }).toBe(1);
  await expect(page.locator('.myx-add-command')).toContainText('ChatGPT connected. Run claudex.');
  await expect(page.locator('.myx-add-command code')).toHaveText('claudex');
  await page.getByRole('button', { name: 'Try it', exact: true }).click();
  await expect(page.locator('.myx-add-try')).toContainText('Hello from Sol');
  await expect(page.locator('.myx-add-try')).toContainText('gpt-6-sol');
  await expect(page.locator('.myx-add-try')).toContainText('a tiny bit of your plan');
  expect(saves).toBe(1);
  expect(tryRuns).toBe(1);
  await page.getByRole('button', { name: 'Try it', exact: true }).click();
  await expect(page.locator('.myx-add-try').getByRole('alert')).toContainText('Limit reached');
  await expect(page.locator('.myx-add-try')).not.toContainText('a tiny bit of your plan');
  expect(tryRuns).toBe(2);
});
