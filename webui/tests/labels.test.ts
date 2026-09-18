// LABEL WALL (row M1-04). CONTRACTS.md section 4: every user-visible label of a
// directory lives in that directory's `strings.ts`, lowercase, three words or
// fewer, no em-dash. The set of `strings.ts` files is globbed from the tree, so
// a directory that ships one is covered the moment it exists and cannot be
// forgotten from a list. The second test is the mutation proof: the wall MUST
// fail on the fixture's four planted violations, or a green first test means
// nothing.
import { describe, expect, test } from 'vitest';

import { checkLabels } from '../src/shared/coverage/labels';
import * as badStrings from './fixtures/walls/bad-strings';

// Exact filename: `bad-strings.ts` under tests/ must never match this.
const stringsModules = import.meta.glob<Record<string, unknown>>('../src/**/strings.ts', { eager: true });

describe('label wall', () => {
  test('every label in the tree is a label', () => {
    const files = Object.entries(stringsModules);
    console.log(`label wall: ${files.length} strings.ts file(s) under src/`);

    const findings = files.flatMap(([file, module]) =>
      checkLabels(module).map((finding) => ({ file, ...finding })),
    );

    expect(findings).toEqual([]);
  });

  test('the wall fails on its four planted violations', () => {
    expect(checkLabels(badStrings)).toEqual([
      { key: 'S.emDash', problems: ['em-dash'] },
      { key: 'S.nested.tooManyWords', problems: ['too long'] },
      { key: 'S.tooManyWords', problems: ['too long'] },
      { key: 'S.uppercase', problems: ['capitalised'] },
    ]);
  });
});
