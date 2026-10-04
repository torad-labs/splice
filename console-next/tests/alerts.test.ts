// Ported from the old console's mcp-doctor.test.ts: the one block that exercises only the alert
// derivation (canTest) with plain data. The rest of that file renders pages and is not ported.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { Settings } from '../src/pages/usage/Alerts';
import { canTest } from '../src/lib/alerts';
import type { AlertSettings } from '../src/types/alerts';

describe('alert settings', () => {
  test('a test send needs a saved webhook: the daemon answers 409 without one', () => {
    const off: AlertSettings = { desktop: false, webhook_url: null };
    expect(canTest(off)).toBe(false);
    expect(canTest(null)).toBe(false);
    // desktop is no destination: the daemon delivers nothing to one (AlertDelivery.kt)
    expect(canTest({ desktop: true, webhook_url: null })).toBe(false);
    expect(canTest({ desktop: false, webhook_url: 'https://example.test/hook' })).toBe(true);
  });
});

describe('the alert settings page', () => {
  test('offers the webhook and no desktop switch, because nothing delivers to a desktop', () => {
    const html = renderToStaticMarkup(createElement(QueryClientProvider, { client: new QueryClient() }, createElement(Settings, { settings: { desktop: true, webhook_url: null } })));
    expect(html).toContain('Webhook');
    expect(html).not.toContain('Desktop');
    expect(html).not.toContain('role="switch"');
  });
});
