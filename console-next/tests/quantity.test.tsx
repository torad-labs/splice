import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { afterEach, expect, test, vi } from 'vitest';
import { AllSettings } from '../src/pages/settings/AllSettings';
import { NumberInput } from '../src/ui/controls';
import { quantityText, quantityUnits, quantityValue } from '../src/lib/quantity';
import type { ConfigPayload } from '../src/types/core';

vi.mock('../src/lib/show-keys', () => ({ useShowKeys: () => false }));
vi.mock('../src/lib/restart-pending', () => ({ useRestartPending: () => [], recordSaved: vi.fn() }));
afterEach(() => vi.unstubAllGlobals());

test('display conversion round-trips every supported unit without rounding away base units', () => {
  for (const unit of ['ms', 'bytes'] as const) for (const { factor } of quantityUnits(unit)) {
    for (const value of [0, 1, -1, 1501, 8388609, Number.MAX_SAFE_INTEGER, -Number.MAX_SAFE_INTEGER]) {
      expect(quantityValue(quantityText(value, factor), factor)).toBe(value);
    }
  }
});

test('fractional base units, malformed input and unsafe values are rejected rather than rounded', () => {
  expect(quantityValue('1.001', 1000)).toBe(1001);
  expect(quantityValue('1.0001', 1000)).toBeNull();
  expect(quantityValue('1.5', 1024)).toBe(1536);
  expect(quantityValue('0.0000001', 1024)).toBeNull();
  expect(quantityValue('1e3', 1)).toBe(1000);
  for (const text of ['', 'NaN', 'Infinity', '1.5', '9007199254740992', '1e999999999', '1e-999999999']) expect(quantityValue(text, 1)).toBeNull();
});

test('whole-number fields expose native numeric semantics', () => {
  const html = renderToStaticMarkup(<NumberInput label="Synthetic count" value={3} onCommit={() => undefined} />);
  expect(html).toContain('type="number"');
  expect(html).toContain('step="1"');
});

test('Advanced edits time and size in readable units rather than raw base-unit fields', () => {
  vi.stubGlobal('location', { host: 'synthetic.invalid' });
  const client = new QueryClient();
  const config: ConfigPayload = {
    effective: { requestReadTimeoutMs: 1501, maxRequestBytes: 8 * 1024 * 1024, maxQueued: 3 },
    layers: { defaults: {}, toml: {}, perHead: {}, file: {}, env: {}, runtime: {} },
    restart_required_keys: [], source: 'synthetic',
  };
  client.setQueryData(['config', '/api/config'], config);
  const html = renderToStaticMarkup(<QueryClientProvider client={client}><MemoryRouter><AllSettings /></MemoryRouter></QueryClientProvider>);
  expect(html).toMatch(/<input[^>]*type="number"[^>]*value="1\.501"/);
  expect(html).toContain('>Seconds');
  expect(html).toMatch(/<input[^>]*type="number"[^>]*value="8"/);
  expect(html).toContain('>MiB');
  expect(html).not.toContain('value="8388608"');
  expect(html).not.toContain('value="1501"');
});
