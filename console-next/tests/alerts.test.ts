// Ported from the old console's mcp-doctor.test.ts: the one block that exercises only the alert
// derivation (canTest) with plain data. The rest of that file renders pages and is not ported.
import { describe, expect, test } from 'vitest';
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
