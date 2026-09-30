// PARITY: the replacement covers everything the console it replaces covers. Off by default (the wall in
// coverage.test.ts holds while the replacement is built); `npm run parity` turns it on and fails, by name, on
// every name still `pending`. The packaging switch waits for this to pass.
import { describe, expect, test } from 'vitest';
import type { DispositionSource } from '../src/coverage/checks';
import { denominator, sources } from './support/coverage';

const pendingIn = (from: readonly DispositionSource[]): string[] => {
  const effective = new Map<string, string>();
  for (const source of from) for (const declared of source.dispositions) {
    if (source.baseline !== true || !effective.has(declared.name)) effective.set(declared.name, declared.disposition);
  }
  return [...effective].filter(([, disposition]) => disposition === 'pending').map(([name]) => name).sort();
};

const pending = (): string[] => pendingIn(sources);

describe.skipIf(process.env.PARITY !== '1')('parity', () => {
  test('no name is still pending', () => {
    expect(pending()).toEqual([]);
  });
});

describe('the parity check itself', () => {
  test('counts what is still pending against a denominator that is not empty', () => {
    expect(denominator.length).toBeGreaterThan(0);
    // The check can fail: a page that still calls a name pending, over a baseline that once covered it, is counted by name.
    const planted: DispositionSource = { source: 'planted', dispositions: [{ kind: 'route', name: '/api/planted', disposition: 'pending', where: 'V4-444' }] };
    expect(pendingIn([...sources, planted])).toEqual(['/api/planted']);
    expect(pendingIn([planted, { source: 'page', dispositions: [{ kind: 'route', name: '/api/planted', disposition: 'read-only' }] }])).toEqual([]);
  });
});
