// PARITY: the replacement covers everything the console it replaces covers. Off by default (the wall in
// coverage.test.ts holds while the replacement is built); `npm run parity` turns it on and fails, by name, on
// every name still `pending`. The packaging switch waits for this to pass.
import { describe, expect, test } from 'vitest';
import { denominator, sources } from './support/coverage';

const pending = (): string[] => {
  const effective = new Map<string, string>();
  for (const source of sources) for (const declared of source.dispositions) {
    if (source.baseline !== true || !effective.has(declared.name)) effective.set(declared.name, declared.disposition);
  }
  return [...effective].filter(([, disposition]) => disposition === 'pending').map(([name]) => name).sort();
};

describe.skipIf(process.env.PARITY !== '1')('parity', () => {
  test('no name is still pending', () => {
    expect(pending()).toEqual([]);
  });
});

describe('the parity check itself', () => {
  test('counts what is still pending against a denominator that is not empty', () => {
    expect(denominator.length).toBeGreaterThan(0);
    expect(pending().length).toBeGreaterThan(0);
  });
});
