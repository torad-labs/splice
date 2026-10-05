import { describe, expect, test } from 'vitest';
import { routeOf } from '../src/lib/needs-page';

describe('addresses', () => {
  test('every address the derivation makes is a router path once its hash is dropped', () => {
    expect(routeOf('#/models/claudex')).toBe('/models/claudex');
    expect(routeOf('/sessions')).toBe('/sessions');
    expect(routeOf('#/models/a%20b')).toBe('/models/a%20b');
    expect(routeOf('#/sessions/s1')).toBe('/sessions/s1');
    expect(routeOf('#/settings/health')).toBe('/settings/health');
  });
});
