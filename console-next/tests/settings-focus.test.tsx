import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, expect, test, vi } from 'vitest';
import { SettingsPage } from '../src/pages/settings/SettingsPage';
import { AllSettings } from '../src/pages/settings/AllSettings';
import type { ConfigPayload } from '../src/types/core';
import type { Fix } from '../src/types/needs';
import { NeedFix } from '../src/pages/needs/NeedFix';

vi.mock('../src/lib/theme', () => ({ useThemeChoice: () => 'system', setThemeChoice: vi.fn() }));
vi.mock('../src/lib/show-keys', () => ({ useShowKeys: () => false, setShowKeys: vi.fn() }));
vi.mock('../src/lib/restart-pending', () => ({ useRestartPending: () => [], recordSaved: vi.fn() }));

const config: ConfigPayload = {
  effective: { effort: 'high', maxInflight: 2, showReasoning: 'text', usageWarnPct: 80 },
  layers: { defaults: {}, toml: {}, perHead: {}, file: {}, env: {}, runtime: {} },
  restart_required_keys: [], source: 'synthetic',
};
afterEach(() => vi.unstubAllGlobals());

function render(path: string, ready = true, effective: ConfigPayload['effective'] = config.effective, fullList = false, perHead: ConfigPayload['layers']['perHead'] = {}): string {
  vi.stubGlobal('location', { host: 'synthetic.invalid' });
  const client = new QueryClient();
  if (ready) client.setQueryData(['config', '/api/config'], { ...config, effective, layers: { ...config.layers, perHead } });
  client.setQueryData(['models'], { heads: [
    { head: 'synthetic-codex-command', provider: 'synthetic-codex-provider', pinned_model: '', models: [{ id: 'synthetic-codex', label: 'Synthetic ChatGPT model' }] },
    { head: 'synthetic-grok-command', provider: 'grok', pinned_model: '', models: [{ id: 'synthetic-grok', label: 'Synthetic Grok model' }] },
  ] });
  client.setQueryData(['heads', '/api/heads'], { heads: [{ key: 'synthetic-command', label: 'Synthetic command' }, { key: 'synthetic-codex-command', label: 'Synthetic ChatGPT command', authKind: 'chatgpt-oauth' }] });
  return renderToStaticMarkup(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><Routes><Route path="/settings/:section?" element={fullList ? <AllSettings /> : <SettingsPage />} /></Routes></MemoryRouter></QueryClientProvider>);
}

test('opening one Settings section does not mount every other section and control', () => {
  const html = render('/settings/conversation');
  expect(html).toContain('id="settings-conversation"');
  for (const other of ['general', 'tools', 'storage', 'health', 'advanced']) expect(html).not.toContain(`id="settings-${other}"`);
  expect(html).toContain('href="/settings/advanced"');
});

test('thinking has an explicit command scope, a selected effective value and level explanations', () => {
  const html = render('/settings/conversation');
  expect(html).toContain('Thinking default for');
  expect(html).toContain('Currently running: High');
  expect(html).toContain('Less thinking for straightforward work');
  expect(html).toContain('More thinking for difficult work');
});

test('the warning slider visibly names its current selected value rather than a third endpoint', () => {
  const html = render('/settings');
  expect(html).toContain('Selected: 80%');
  expect(html).not.toContain('<b>80%</b>');
});

test('Advanced explains the real key-icon button rather than a nonexistent code glyph', () => {
  const html = render('/settings/advanced');
  expect(html).toContain('use the key button on a row');
  expect(html).not.toContain('&lt;&gt;');
});

test('Advanced model settings use catalog labels and keep unlisted fold selections reachable', () => {
  const html = render('/settings/advanced', true, { pinnedModel: 'synthetic-codex', grokModel: 'synthetic-grok', foldReasoningModels: 'synthetic-codex,synthetic-unlisted' }, true);
  expect(html).toMatch(/<button[^>]*aria-label="ChatGPT model"/);
  expect(html).toMatch(/<button[^>]*aria-label="Grok model"/);
  expect(html).toContain('Synthetic ChatGPT model');
  expect(html).toContain('Synthetic Grok model');
  expect(html).toContain('synthetic-unlisted');
  expect(html).toContain('Add a fold model');
  expect(html).not.toContain('value="synthetic-codex,synthetic-unlisted"');
});

test('Settings retains the shared fix actions after the retired card is removed', () => {
  const fix = (value: Fix): string => renderToStaticMarkup(<QueryClientProvider client={new QueryClient()}><MemoryRouter><NeedFix fix={value} /></MemoryRouter></QueryClientProvider>);
  expect(fix({ kind: 'start', head: 'synthetic-command' })).toContain('>Start<');
  expect(fix({ kind: 'restart', head: 'synthetic-command' })).toContain('>Restart<');
  const restart = fix({ kind: 'restart-daemon' });
  expect(restart).toContain('Restart splice');
  expect(restart).not.toContain('Drain and restart');
  expect(restart).not.toContain('Turns in flight finish first');
  expect(fix({ kind: 'login', head: 'synthetic-command' })).toContain('>Sign in<');
  expect(fix({ kind: 'login', head: 'synthetic-command', label: 'work' })).toContain('Sign in again');
  expect(fix({ kind: 'copy', command: 'splice key set SYNTHETIC_KEY' })).toContain('Copy the command');
  expect(fix({ kind: 'doctor-fix', id: 'synthetic-check' })).toContain('>Fix it<');
  const masked = fix({ kind: 'masked', command: 'splice key set <redacted:synthetic>' });
  expect(masked).toContain('keeps out of this page');
  expect(masked).not.toContain('Copy the command');
  expect(masked).not.toContain('redacted');
  const open = fix({ kind: 'open', href: '#/models/synthetic-command', label: 'Open log', fallback: 'splice logs --head synthetic-command' });
  expect(open).toContain('href="/models/synthetic-command"');
  expect(open).toContain('splice logs --head synthetic-command');
});

test('Health does not link to itself and retains the report’s advice, while other pages can open Health', () => {
  const fix: Fix = { kind: 'open', href: '#/settings/health', label: 'Open Health', fallback: 'Synthetic repair advice stays here.' };
  const at = (path: string): string => renderToStaticMarkup(<QueryClientProvider client={new QueryClient()}><MemoryRouter initialEntries={[path]}><NeedFix fix={fix} /></MemoryRouter></QueryClientProvider>);
  for (const path of ['/settings/health', '/settings/health?from=synthetic-command']) {
    const health = at(path);
    expect(health).toContain('Synthetic repair advice stays here.');
    expect(health).not.toContain('href="/settings/health"');
  }
  const elsewhere = at('/models/synthetic-command');
  expect(elsewhere).toContain('href="/settings/health"');
  expect(elsewhere).toContain('Open Health');
});

test('Advanced override notes resolve declared command labels and retain override-only identities', () => {
  const html = render('/settings/advanced', true, { replayReasoning: true }, true, {
    'synthetic-command': { replayReasoning: false },
    'synthetic-override-only': { replayReasoning: false },
  });
  expect(html).toContain('A command sets its own: Synthetic command, synthetic-override-only.');
  expect(html).not.toContain('A command sets its own: synthetic-command,');
});

test.each([true, false])('Advanced readonly boolean values use On and Off without changing controls or values: %s', value => {
  const html = render('/settings/advanced', true, { trace: value, mirrorReasoning: value }, true);
  expect(html.match(new RegExp('<span class="folder code">' + (value ? 'On' : 'Off') + '</span>', 'g'))).toHaveLength(2);
  expect(html).not.toContain('<span class="folder code">' + String(value) + '</span>');
});

test('Storage names Requests as the page backed by retained request traces', () => {
  const html = render('/settings/storage');
  expect(html).toContain('The turn-by-turn record behind Requests.');
  expect(html).not.toContain('record behind Turns');
});

test('a deep link stays a pending read, not a failure, until the initial settings arrive', () => {
  const html = render('/settings/health', false);
  expect(html).toContain('Reading the settings');
  expect(html).not.toContain('not answering');
});
