// COPY WALL (the 2026-09-25 voice ruling). "There is a lot of mannered text all of the UI too": a
// label is a standard product term, three words or fewer, in sentence case; a state is a badge, an
// icon or a number, never a clause; the page carries no explanatory prose — that is an info icon's
// one-sentence help, on demand; an empty state is one factual line plus the action; an error says
// what failed and what to do, in one line.
//
// The denominator for BOTH halves is globbed from the tree, never hand-listed (a list that checks
// itself cannot fail for anything missing from itself):
//   half 1 — every `strings.ts` and `copy.ts` under `src`: every exported string is a label (<=3
//     words, sentence case), an `H` help sentence (<=12 words, one sentence) or a `U` unit fragment
//     (<=2 words) by its export name; every export in a copy.ts reads by help rules.
//   half 2 — every other `.ts`/`.tsx` under `src`: no JSX text and no 4+ word string literal may
//     live outside a copy module (`src/shared/coverage/copy-scan.ts` walks the real AST so a
//     template, a ternary or nested JSX still gets found).
// This file folds the old label wall (`tests/labels.test.ts`, row M1-04) into the new one: the
// lowercase-only rule that wall enforced is REVERSED here to sentence case.
//
// PENDING carries the six pages named in the ruling (and the widgets/features only they import),
// which claude-builder's Phase 3 branch is rebuilding to this voice already — their findings are
// counted and logged, never failed, so the gate does not fight a branch already in flight.
// Everything else is NOT pending and is expected to be red today; it must go green over time, by
// fixing the copy, never by widening PENDING or softening the checker.
import { existsSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, test } from 'vitest';

import { checkLabels } from '../src/shared/coverage/labels';
import { scanFileForBareCopy, type BareCopyFinding } from '../src/shared/coverage/copy-scan';
import * as badStrings from './fixtures/walls/bad-strings';

const consoleRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

// A directory prefix under `src`, one sentence why its findings are counted rather than failed.
// Six pages named by the ruling, plus every widget/feature imported ONLY from those six (found by
// grepping `@widgets/*` and `@features/*` imports: a directory whose sole importers are pending
// pages is itself pending, since it ships in the same rebuild).
const PENDING_REASON = "rebuilt in claude-builder's Phase 3 branch, which follows this voice and this gate";
const PENDING: Record<string, string> = {
  'src/pages/doctor': PENDING_REASON,
};

function isPending(file: string): boolean {
  return Object.keys(PENDING).some((prefix) => file === prefix || file.startsWith(`${prefix}/`));
}

function partition<T extends { file: string }>(findings: readonly T[]): { pending: T[]; notPending: T[] } {
  const pending: T[] = [];
  const notPending: T[] = [];
  for (const finding of findings) (isPending(finding.file) ? pending : notPending).push(finding);
  return { pending, notPending };
}

function logPendingByPrefix(half: string, pending: readonly { file: string }[]): void {
  const counts = new Map<string, number>();
  for (const finding of pending) {
    const prefix = Object.keys(PENDING).find((candidate) => finding.file === candidate || finding.file.startsWith(`${candidate}/`));
    if (prefix) counts.set(prefix, (counts.get(prefix) ?? 0) + 1);
  }
  for (const [prefix, count] of [...counts].sort()) console.log(`copy wall ${half}: ${count} pending finding(s) under ${prefix}`);
}

function topFiles(findings: readonly { file: string }[], n: number): [string, number][] {
  const counts = new Map<string, number>();
  for (const finding of findings) counts.set(finding.file, (counts.get(finding.file) ?? 0) + 1);
  return [...counts].sort((left, right) => right[1] - left[1] || (left[0] < right[0] ? -1 : 1)).slice(0, n);
}

const strip = (glob: string): string => (glob.startsWith('../') ? glob.slice(3) : glob);

// ---- half 1: every strings.ts and copy.ts, globbed, never hand-listed ----
const stringsModules = import.meta.glob<Record<string, unknown>>('../src/**/strings.ts', { eager: true });
const copyModules = import.meta.glob<Record<string, unknown>>('../src/**/copy.ts', { eager: true });

const copyModuleFiles = [
  ...Object.entries(stringsModules).map(([file, module]) => ({ file: strip(file), module, isCopyFile: false })),
  ...Object.entries(copyModules).map(([file, module]) => ({ file: strip(file), module, isCopyFile: true })),
];

type Half1Finding = { file: string; key: string; value: string; problems: readonly string[] };

const half1Findings: Half1Finding[] = copyModuleFiles.flatMap(({ file, module, isCopyFile }) =>
  checkLabels(module, isCopyFile).map((finding) => ({ file, ...finding })),
);

// ---- half 2: every other .ts/.tsx under src, globbed as raw text for the AST scanner ----
const rawSourceFiles = import.meta.glob('../src/**/*.{ts,tsx}', {
  eager: true,
  query: '?raw',
  import: 'default',
}) as Record<string, string>;

function isHalf2Denominator(file: string): boolean {
  if (file.endsWith('.d.ts')) return false;
  if (file.endsWith('/strings.ts') || file.endsWith('/copy.ts')) return false;
  if (file.split('/').includes('fixtures')) return false;
  if (file.startsWith('src/shared/coverage/')) return false;
  // A page's coverage.ts is the coverage wall's manifest: no component renders a Disposition's
  // reason, so it is audit prose, not page copy.
  if (/^src\/pages\/[^/]+\/coverage\.ts$/.test(file)) return false;
  return true;
}

const half2Files = Object.entries(rawSourceFiles)
  .map(([file, source]) => ({ file: strip(file), source }))
  .filter(({ file }) => isHalf2Denominator(file));

const half2Findings: BareCopyFinding[] = half2Files.flatMap(({ file, source }) => scanFileForBareCopy(source, file));

describe('copy wall — half 1: every copy module string is within its role\'s budget', () => {
  test('label, help and unit strings across the tree', () => {
    const { pending, notPending } = partition(half1Findings);
    console.log(
      `copy wall half 1: ${copyModuleFiles.length} copy module(s), ${half1Findings.length} finding(s) ` +
        `(${pending.length} pending, ${notPending.length} not pending)`,
    );
    logPendingByPrefix('half 1', pending);
    if (notPending.length > 0) {
      console.log('copy wall half 1: sample not-pending findings:');
      for (const finding of notPending.slice(0, 10)) {
        console.log(`  ${finding.file} ${finding.key}: "${finding.value}" [${finding.problems.join(', ')}]`);
      }
    }
    expect(notPending).toEqual([]);
  });

  test('the checker fails on its planted violations', () => {
    expect(checkLabels(badStrings, false)).toEqual([
      { key: 'H.tooLong', value: 'Restart moves the head to a clean process and this sentence keeps going past the limit.', problems: ['too long'] },
      { key: 'H.twoSentences', value: 'Restart the head. It comes back on its own.', problems: ['more than one sentence'] },
      { key: 'S.emDash', value: 'Splice — daemon', problems: ['em-dash'] },
      { key: 'S.fourWords', value: 'Stop every running head', problems: ['too long'] },
      { key: 'S.lowercaseStart', value: 'fleet', problems: ['lowercase start'] },
      { key: 'S.trailingPeriod', value: 'Restart daemon.', problems: ['trailing period'] },
      { key: 'U.threeWords', value: 'turns per minute', problems: ['too long'] },
    ]);
  });
});

describe('copy wall — half 2: no bare copy outside a copy module', () => {
  test('JSX text and sentence literals across the tree', () => {
    const { pending, notPending } = partition(half2Findings);
    console.log(
      `copy wall half 2: ${half2Files.length} file(s) scanned, ${half2Findings.length} finding(s) ` +
        `(${pending.length} pending, ${notPending.length} not pending)`,
    );
    logPendingByPrefix('half 2', pending);
    if (notPending.length > 0) {
      console.log('copy wall half 2: top 15 not-pending files by finding count:');
      for (const [file, count] of topFiles(notPending, 15)) console.log(`  ${count}\t${file}`);
      console.log('copy wall half 2: sample not-pending findings:');
      for (const finding of notPending.slice(0, 10)) {
        console.log(`  ${finding.file}:${finding.line} ${finding.reason}: "${finding.text}"`);
      }
    }
    expect(notPending).toEqual([]);
  });

  test('the scanner fails on its planted fixture', () => {
    const fixturePath = 'tests/fixtures/walls/bad-copy.tsx';
    const source = readFileSync(path.join(consoleRoot, fixturePath), 'utf8');
    expect(scanFileForBareCopy(source, fixturePath)).toEqual([
      { file: fixturePath, line: 19, reason: 'sentence literal', text: 'Restart the daemon before trying again' },
      { file: fixturePath, line: 24, reason: 'jsx text', text: 'Restart the daemon now' },
    ]);
  });
});

describe('copy wall — dispositions', () => {
  test('every PENDING prefix names a real directory', () => {
    const missing = Object.keys(PENDING).filter((prefix) => {
      const full = path.join(consoleRoot, prefix);
      return !existsSync(full) || !statSync(full).isDirectory();
    });
    expect(missing).toEqual([]);
  });
});
