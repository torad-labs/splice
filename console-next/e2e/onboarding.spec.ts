// NEW: V4-444 — first-hour sign-in uses a synthetic provider tab and never reaches a provider.
import { expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import type { AddChecksFailed, AddProfile, AddView } from '../src/types/add';
import type { HeadsPayload } from '../src/types/core';
import { env, open, read } from './support';
import { STACK } from './stack';

// Finish intercepted reads before Playwright closes this test's page and installs the next fixture.
test.afterEach(async ({ page }) => {
  await page.unrouteAll({ behavior: 'wait' });
  await page.context().unrouteAll({ behavior: 'wait' });
});

test('a first plan follows a late sign-in URL while hidden, saves once and tries success then quota refusal', async ({ page }) => {
  const signIn = 'https://signin.fixture.invalid/authorize';
  const command = 'synthetic-connected-plan';
  const profiles: AddProfile[] = [{
    name: 'codex', summary: 'Synthetic ChatGPT subscription', auth_kind: 'chatgpt-oauth',
    requires_key: false, base_url: 'https://api.fixture.invalid', head_key: STACK.oauthHead,
    command, models: [], asks: [],
  }];
  const add: AddView = {
    id: 'synthetic-add', profile: 'codex', key: STACK.oauthHead, command,
    auth_kind: 'chatgpt-oauth', base_url: 'https://api.fixture.invalid', models: [],
    sign_in_by: 'login', key_env: null, credential: { present: false, detail: 'Sign in to ChatGPT.' },
    sign_in: null, checks: null, saved: null,
  };
  const announced = {
    id: 'synthetic-login', state: 'waiting' as const, browser_url: signIn, verification_uri: null,
    user_code: null, failure_reason: null,
  };
  const starting: AddView = { ...add, sign_in: { ...announced, state: 'starting', browser_url: null } };
  const waiting: AddView = { ...add, sign_in: announced };
  const signedIn: AddView = { ...waiting, credential: { present: true, detail: 'Signed in' },
    sign_in: { ...announced, state: 'signed_in' } };
  const real = await read<HeadsPayload>(page, '/api/heads');
  const connected = real.heads.find((head) => head.key === STACK.oauthHead);
  if (connected === undefined) throw new Error('isolated stack has no synthetic OAuth plan');
  let loginStarted = false;
  let loginPolls = 0;
  let saves = 0;
  let tries = 0;
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.context().route('**/*', (route) => {
    const host = new URL(route.request().url()).hostname;
    if (host === 'signin.fixture.invalid') return route.fulfill({
      contentType: 'text/html', body: '<h1>Synthetic provider sign-in</h1>',
    });
    return host === '127.0.0.1' || host === 'localhost' ? route.continue() : route.abort();
  });
  await page.route('**/api/heads', (route) => route.fulfill({ json: { heads: saves === 0 ? [] : [connected] } }));
  await page.route('**/api/accounts', (route) => route.fulfill({ json: { accounts: [] } }));
  await page.route('**/api/auth', (route) => route.fulfill({ json: {} }));
  await page.route('**/api/doctor', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as Record<string, unknown>;
    return route.fulfill({ response, json: { ...body, checks: [] } });
  });
  await page.route('**/api/add/profiles', (route) => route.fulfill({ json: { profiles } }));
  await page.route('**/api/add', (route) => {
    expect(route.request().postDataJSON()).toEqual({ profile: 'codex' });
    return route.fulfill({ json: add });
  });
  await page.route('**/api/add/synthetic-add/login', (route) => {
    loginStarted = true;
    return route.fulfill({ json: starting });
  });
  await page.route('**/api/add/synthetic-add', (route) => {
    if (!loginStarted) return route.fulfill({ json: add });
    loginPolls += 1;
    return route.fulfill({ json: loginPolls === 1 ? starting : loginPolls === 2 ? waiting : signedIn });
  });
  await page.route('**/api/add/synthetic-add/save', (route) => {
    saves += 1;
    return route.fulfill({ json: { ...signedIn,
      saved: { path: '/synthetic/splice.toml', wrapper: { linked: true }, restart: { status: 'draining' } } } });
  });
  await page.route('**/api/playground', (route) => {
    expect(route.request().postDataJSON()).toEqual({ head: STACK.oauthHead, prompt: 'Say hello and name your model.' });
    tries += 1;
    return route.fulfill({ json: {
      request: { url: 'https://api.fixture.invalid/responses', method: 'POST', headers: {}, body: { model: STACK.model } },
      response: tries === 1
        ? { status: 200, body: { model: STACK.model, output: [{ type: 'message', content: [{ type: 'output_text', text: 'Synthetic hello' }] }], usage: { input_tokens: 9, output_tokens: 2 } } }
        : { status: 429, body: { error: { message: 'Synthetic limit reached' } } },
    } });
  });
  await open(page, 'models');
  await page.getByRole('button', { name: 'Add a command', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('button', { name: /ChatGPT Sign in with your ChatGPT plan/ }).click();
  const popupReady = page.waitForEvent('popup');
  await dialog.getByRole('button', { name: 'Sign in', exact: true }).click();
  const popup = await popupReady;
  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await expect(popup).toHaveURL(signIn, { timeout: 15_000 });
  await expect(popup.getByRole('heading', { name: 'Synthetic provider sign-in' })).toBeVisible();
  expect(await popup.evaluate(() => window.opener)).toBeNull();
  await expect.poll(() => saves, { timeout: 15_000 }).toBe(1);
  await expect(dialog).toContainText('ChatGPT saved');
  await expect(dialog.locator('code.cmd')).toHaveText(command);
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
  await dialog.getByRole('button', { name: 'Copy', exact: true }).click();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe(command);
  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  // The new command is tried on the Playground, where the one-command form in Settings › Health moved.
  await page.goto(env('CONSOLE_E2E_BASE') + '/#/playground');
  const lane = page.getByRole('list', { name: 'Answers', exact: true }).getByRole('listitem');
  await expect(lane).toHaveCount(1);
  await lane.getByRole('button', { name: 'Command 1', exact: true }).click();
  const choice = page.getByRole('menuitemradio', { name: connected.label, exact: true });
  await expect.poll(async () => {
    const box = await choice.boundingBox();
    const height = page.viewportSize()?.height ?? 0;
    return box !== null && box.y + box.height / 2 >= 0 && box.y + box.height / 2 < height;
  }, { message: 'the opened command menu must remain inside the viewport' }).toBe(true);
  await choice.click();
  const main = page.getByRole('main');
  await main.getByLabel('Prompt', { exact: true }).fill('Say hello and name your model.');
  await main.getByRole('button', { name: 'Send', exact: true }).click();
  await expect(lane).toContainText('Synthetic hello');
  await expect(lane).toContainText('Answered with 200');
  await expect(lane).toContainText('9 tokens in, 2 out');
  await main.getByRole('button', { name: 'Send', exact: true }).click();
  await expect(lane.getByRole('alert')).toHaveText('Synthetic limit reached');
  await expect(lane).toContainText('Answered with 429');
  await expect(lane).not.toContainText('Synthetic hello');
  expect(tries).toBe(2);
  expect(saves).toBe(1);
  expect(errors).toEqual([]);
});

test('an isolated key backend prints every refused verification check and discards without saving or restarting', async ({ page }) => {
  const original = readFileSync(env('CONSOLE_E2E_CONFIG'), 'utf8');
  const faults = await open(page, 'models');
  let saves = 0;
  page.on('request', (request) => {
    if (/^\/api\/add\/[^/]+\/save$/.test(new URL(request.url()).pathname)) saves += 1;
  });
  await page.getByRole('button', { name: 'Add a command', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('button', { name: /^API key / }).click();
  await dialog.getByRole('textbox', { name: 'Command name', exact: true }).fill('synthetic-refused-plan');
  await dialog.getByRole('textbox', { name: 'Provider address', exact: true }).fill('http://127.0.0.1:9/v1');
  await dialog.getByRole('textbox', { name: 'Model id', exact: true }).fill('synthetic/refused-model');
  const opening = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/add' && response.request().method() === 'POST');
  await dialog.getByRole('button', { name: 'Continue', exact: true }).click();
  const opened = await opening;
  expect(opened.status()).toBe(200);
  const view = await opened.json() as AddView;
  expect(view.key).toBe('synthetic-refused-plan');
  await expect(dialog.getByRole('button', { name: 'Store the key', exact: true })).toBeVisible();
  const verification = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/add/' + view.id + '/verify' && response.request().method() === 'POST');
  await dialog.getByRole('button', { name: 'Run the checks', exact: true }).click();
  const verified = await verification;
  expect(verified.status()).toBe(409);
  expect(verified.request().postDataJSON()).toEqual({});
  const failed = await verified.json() as AddChecksFailed;
  expect(failed.checks.some((check) => !check.ok)).toBe(true);
  await expect(dialog.getByRole('alert')).toContainText(failed.error);
  const checks = dialog.getByRole('list', { name: 'Checks', exact: true }).getByRole('listitem');
  await expect(checks).toHaveCount(failed.checks.length);
  for (const check of failed.checks) {
    const row = checks.filter({ has: page.getByText(check.name, { exact: true }) });
    const detail = check.name === 'windows' && check.ok && check.detail === 'declared rows fit the window sizes the provider lists'
      ? 'Every model’s context window fits what the provider serves.' : check.detail;
    await expect(row).toContainText(detail);
    await expect(row).toContainText(check.ok ? 'Passed' : 'Failed');
  }
  const deletion = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/add/' + view.id && response.request().method() === 'DELETE');
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  expect((await deletion).status()).toBe(200);
  await expect(dialog).toHaveCount(0);
  expect(saves).toBe(0);
  expect(readFileSync(env('CONSOLE_E2E_CONFIG'), 'utf8')).toBe(original);
  expect(faults.pageErrors).toEqual([]);
});
