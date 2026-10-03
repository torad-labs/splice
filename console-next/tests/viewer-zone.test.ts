import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test } from 'vitest';

function fixedZones(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    const path = join(dir, entry.name);
    return entry.isDirectory() ? fixedZones(path) : readFileSync(path, 'utf8').includes('America/Chicago') ? [path] : [];
  });
}

test('console source never forces the operator into a fixed Chicago zone', () => {
  expect(fixedZones(new URL('../src', import.meta.url).pathname)).toEqual([]);
});
