import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, expect, test, vi } from 'vitest';
import { SettingsPage } from '../src/pages/settings/SettingsPage';
import type { ConfigPayload } from '../src/types/core';

vi.mock('../src/lib/theme', () => ({ useThemeChoice: () => 'system', setThemeChoice: vi.fn() }));
vi.mock('../src/lib/show-keys', () => ({ useShowKeys: () => false, setShowKeys: vi.fn() }));
vi.mock('../src/lib/restart-pending', () => ({ useRestartPending: () => [], recordSaved: vi.fn() }));

const config: ConfigPayload = {
  effective: { effort: 'high', maxInflight: 2, showReasoning: 'text', usageWarnPct: 80 },
  layers: { defaults: {}, toml: {}, perHead: {}, file: {}, env: {}, runtime: {} },
  restart_required_keys: [], source: 'synthetic',
};
afterEach(() => vi.unstubAllGlobals());

function render(path: string, ready = true): string {
  vi.stubGlobal('location', { host: 'synthetic.invalid' });
  const client = new QueryClient();
  if (ready) client.setQueryData(['config', '/api/config'], config);
  client.setQueryData(['heads', '/api/heads'], { heads: [{ key: 'synthetic-command', label: 'Synthetic command' }] });
  return renderToStaticMarkup(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><Routes><Route path="/settings/:section?" element={<SettingsPage />} /></Routes></MemoryRouter></QueryClientProvider>);
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

test('a deep link stays a pending read, not a failure, until the initial settings arrive', () => {
  const html = render('/settings/health', false);
  expect(html).toContain('Reading the settings');
  expect(html).not.toContain('not answering');
});
