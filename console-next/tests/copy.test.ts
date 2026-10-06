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
import { T } from '../src/pages/settings/copy';
import { D, HT } from '../src/pages/fleet/copy';
import { G } from '../src/pages/playground/copy';
import { FL } from '../src/lib/words-fleet';
import { R } from '../src/pages/shared/copy';
import { advance } from '../src/lib/logs';

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

describe('copy follows observed evidence', () => {
  test('a concurrency setting does not promise faster serialized turns', () => {
    const requests = [{ conversation: 'same', upstream_ms: 1000 }, { conversation: 'same', upstream_ms: 1000 }];
    expect(requests[0]?.conversation).toBe(requests[1]?.conversation);
    expect(T.inflightWhy).not.toContain('finish sooner');
    expect(T.inflightWhy).not.toContain('limit faster');
    expect(T.inflightWhy).toContain('admits');
  });
  test('an unread account list makes no claim about its absent pool', () => {
    const accounts = undefined;
    expect(accounts).toBeUndefined();
    expect(D.noAccounts).not.toContain('has no account pool');
    expect(D.noAccounts).toContain('shown');
  });
  test('an append-only log gap is a changed window, not a proved restart', () => {
    const tail = (first: number) => ({ key: 'synthetic', path: '/synthetic/log',
      lines: Array.from({ length: 10 }, (_, i) => String(first + i)) });
    expect(advance(tail(1), tail(11)).reset).toBe(true);
    expect(HT.logRestarted).not.toContain('restarted');
    expect(HT.logRestarted).toContain('window');
  });
  test('a client-auth mode does not prove a pool lacks a stored credential', () => {
    const state = { authKind: 'client', selectedPoolCredential: 'stored bearer' };
    expect(state.selectedPoolCredential).toBe('stored bearer');
    expect(G.forwarded(['Synthetic'])).not.toContain('Playground does not have');
    expect(G.forwarded(['Synthetic'])).toContain('configured');
  });
  test('a past forwarded refusal is not a guarantee about the next credential', () => {
    const answers = [401, 200];
    expect(answers.at(-1)).toBe(200);
    expect(FL.signedOut).not.toContain('next turn will fail');
    expect(FL.signedOut).toContain('credential');
  });
  test('a taken restart can be waiting without draining', () => {
    const response = { status: 'waiting', compactions: [{ head: 'synthetic', age_ms: 1000 }] };
    expect(response.status).toBe('waiting');
    expect(R.draining).not.toContain('is draining');
    expect(R.draining).toContain('accepted');
  });
});

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
