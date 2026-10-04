// COPY WALL. No explanatory prose lives outside a copy module: every `.ts`/`.tsx` under `src` except the copy modules (`lib/words*.ts`,
// a `copy.ts`) and the coverage manifests may hold no JSX text and no string literal of four words or more. The scanner
// (`src/coverage/copy-scan.ts`) walks the real AST, so a template, a ternary or nested JSX is still found.
//
// The denominator is globbed from the tree, never hand-listed (a list that checks itself cannot fail for anything missing from itself), and
// the planted fixture proves the scanner can fail.
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, test } from 'vitest';
import { scanFileForBareCopy } from '../src/coverage/copy-scan';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const strip = (glob: string): string => (glob.startsWith('../') ? glob.slice(3) : glob);

const rawSourceFiles = import.meta.glob('../src/**/*.{ts,tsx}', { eager: true, query: '?raw', import: 'default' }) as Record<string, string>;

/** A copy module is where prose lives; a coverage manifest is audit prose no component renders. */
function isCopyModule(file: string): boolean {
  if (file.endsWith('.d.ts')) return true;
  if (/^src\/lib\/words[^/]*\.ts$/.test(file)) return true;
  if (file.endsWith('/copy.ts')) return true;
  if (/^src\/pages\/[^/]+\/coverage\.ts$/.test(file)) return true;
  return file.startsWith('src/coverage/');
}

const files = Object.entries(rawSourceFiles).map(([file, source]) => ({ file: strip(file), source })).filter(({ file }) => !isCopyModule(file));
const findings = files.flatMap(({ file, source }) => scanFileForBareCopy(source, file));

describe('copy wall', () => {
  test('no JSX text or sentence literal outside a copy module', () => {
    console.log(`copy wall: ${files.length} file(s) scanned, ${findings.length} finding(s)`);
    expect(files.length).toBeGreaterThan(100);
    expect(findings).toEqual([]);
  });

  test('the scanner fails on its planted fixture', () => {
    const fixturePath = 'tests/fixtures/walls/bad-copy.tsx';
    const source = readFileSync(path.join(root, fixturePath), 'utf8');
    expect(scanFileForBareCopy(source, fixturePath)).toEqual([
      { file: fixturePath, line: 17, reason: 'sentence literal', text: 'Restart the daemon before trying again' },
      { file: fixturePath, line: 22, reason: 'jsx text', text: 'Restart the daemon now' },
    ]);
  });

  test('a copy module is not scanned, and a component beside it is', () => {
    expect(isCopyModule('src/lib/words-usage.ts')).toBe(true);
    expect(isCopyModule('src/pages/fleet/copy.ts')).toBe(true);
    expect(isCopyModule('src/lib/usage-page.ts')).toBe(false);
    expect(isCopyModule('src/pages/fleet/FleetPage.tsx')).toBe(false);
  });
});
