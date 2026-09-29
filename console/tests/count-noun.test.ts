// V4-435: a count of one is singular and every other count is plural. `noun` is the one place that
// picks, so a sentence that prints a count beside a word asks it for the word and keeps its own
// number formatting.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, test } from 'vitest';
import { S as KEPT } from '../src/pages/kept/strings';
import { noun } from '../src/shared/lib';

describe('the noun that agrees with a count', () => {
  test('one takes the singular, and zero, two and a thousand take the plural', () => {
    expect(noun(1, 'message', 'messages')).toBe('message');
    expect(noun(0, 'message', 'messages')).toBe('messages');
    expect(noun(2, 'message', 'messages')).toBe('messages');
    expect(noun(1000, 'message', 'messages')).toBe('messages');
  });

  test('a kept capture of one record and one byte reads singular, and two of each plural', () => {
    expect(KEPT.traceCount(1, 1)).toBe('1 record, 1 byte kept.');
    expect(KEPT.traceCount(2, 2048)).toBe('2 records, 2048 bytes kept.');
  });

  test('an irregular plural is spelled by the caller, not guessed', () => {
    expect(noun(1, 'retry', 'retries')).toBe('retry');
    expect(noun(3, 'retry', 'retries')).toBe('retries');
  });
});

// THE SWEEP. The denominator is the source tree: every .ts/.tsx under src/ is read, so a file added
// tomorrow is swept without anyone listing it. A count printed beside a word is one of two shapes:
//   `${n} ${U.key}` (or JSX `{n} {U.key}`): the word is resolved from the strings module beside the file;
//   `${n} words`: the word is right there in the template.
// Either reading a plural word is a count that says "1 messages" the day it is one. `noun` in place of the
// word takes the site out of both shapes. What a plural-looking word may stay is spelled in EXEMPT with the
// reason, keyed by file and word; an exemption whose site is gone fails, so none outlives the code it excuses.
const SRC = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../src');

const EXEMPT: Record<string, string> = {
  'features/add-model/strings.ts models': 'a ternary on the count picks "Add 1 model" one branch up',
  'features/api-key/strings.ts uses': '`${head}` is a plan name and "uses" its verb',
  'features/api-key/strings.ts has': '`${head}` is a plan name and "has" its verb',
  'features/api-key/strings.ts reads': '`${head}` is a plan name and "reads" its verb',
  'widgets/rule/index.tsx without limits': '"N without limits" is the idiom for any N: the phrase is a fragment, not a counted noun',
};

type Strings = Record<string, Record<string, unknown> | undefined>;

const COUNT_THEN_KEY = /(?:\$\{[^{}]+\}\s+\$\{|\}\s+\{)(U|S|H)\.(\w+)\}/g;
const COUNT_THEN_WORD = /\$\{[^{}]+\}\s+([A-Za-z]+)\b/g;

const isPluralLooking = (word: string): boolean => /^[a-z]{3,}$/i.test(word) && /s$/i.test(word) && !/(ss|us|is)$/i.test(word);

/** Every count printed beside a plural-looking word in [text]: the word, and the line it is on. */
function pluralBesideCount(text: string, strings: Strings): { word: string; line: number; shown: string }[] {
  const lineOf = (index: number | undefined): number => text.slice(0, index).split('\n').length;
  const hits: { word: string; line: number; shown: string }[] = [];
  for (const m of text.matchAll(COUNT_THEN_KEY)) {
    const word = strings[m[1]]?.[m[2]];
    if (typeof word === 'string' && isPluralLooking(word.split(' ').pop() ?? '')) {
      hits.push({ word, line: lineOf(m.index), shown: `${m[0]} reads "${word}"` });
    }
  }
  for (const m of text.matchAll(COUNT_THEN_WORD)) {
    if (isPluralLooking(m[1])) {
      hits.push({ word: m[1], line: lineOf(m.index), shown: m[0] });
    }
  }
  return hits;
}

const walk = (dir: string): string[] =>
  fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => (entry.isDirectory() ? walk(path.join(dir, entry.name)) : [path.join(dir, entry.name)]));

async function sweepSrc(): Promise<{ sites: string[]; keys: Set<string> }> {
  const sites: string[] = [];
  const keys = new Set<string>();
  for (const file of walk(SRC).filter((f) => /\.tsx?$/.test(f))) {
    let strings: Strings;
    try {
      strings = await import(path.join(path.dirname(file), 'strings.ts'));
    } catch {
      strings = {};
    }
    const rel = path.relative(SRC, file);
    for (const hit of pluralBesideCount(fs.readFileSync(file, 'utf8'), strings)) {
      keys.add(`${rel} ${hit.word}`);
      if (!(`${rel} ${hit.word}` in EXEMPT)) sites.push(`${rel}:${hit.line}  ${hit.shown}`);
    }
  }
  return { sites, keys };
}

describe('no count is printed beside a plural word', () => {
  test('the detector flags the plural forms and passes their lawful twins', () => {
    const strings: Strings = { U: { messages: 'messages', message: 'message', turns: 'turns', kb: 'kb' } };
    const flagged = (text: string): number => pluralBesideCount(text, strings).length;
    expect(flagged('`${n} ${U.messages}`')).toBe(1);
    expect(flagged('<b>{fmtInt(n)} {U.turns}</b>')).toBe(1);
    expect(flagged('`${bytes} bytes kept`')).toBe(1);
    expect(flagged('`${n} ${noun(n, U.message, U.messages)}`')).toBe(0);
    expect(flagged('`${n} ${U.message}`')).toBe(0);
    expect(flagged('`${n} ${U.kb}`')).toBe(0);
    expect(flagged("`${n} ${n === 1 ? 'byte' : 'bytes'}`")).toBe(0);
  });

  test('the sweep reads the source tree, not a list of files', () => {
    expect(walk(SRC).filter((file) => /\.tsx?$/.test(file)).length).toBeGreaterThan(200);
  });

  test('every site in src/ that prints a count beside a word lets the count pick it', async () => {
    const { sites } = await sweepSrc();
    expect(sites, `use noun(count, one, many):\n${sites.join('\n')}`).toEqual([]);
  });

  test('every exemption still excuses a site that exists', async () => {
    const { keys } = await sweepSrc();
    expect(Object.keys(EXEMPT).filter((key) => !keys.has(key))).toEqual([]);
  });
});
