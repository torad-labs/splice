import { describe, expect, test } from 'vitest';
import { moveKey, sortByOrder } from '../src/lib/order';
import { shellLine, shellWord } from '../src/lib/shell';

describe('the operator\'s order', () => {
  test('sorts by the kept order; unknown keys follow the known ones in the order given', () =>
    expect(sortByOrder(['a', 'b', 'c'], (key) => key, ['c', 'a'])).toEqual(['c', 'a', 'b']));
  test('with no kept order nothing moves', () => expect(sortByOrder(['b', 'a'], (key) => key, [])).toEqual(['b', 'a']));
  test('a first drag fixes the whole order', () => expect(moveKey([], ['a', 'b', 'c'], 'c', 'a')).toEqual(['c', 'a', 'b']));
  test('moving onto itself, or an unknown key, changes nothing', () => {
    expect(moveKey(['a', 'b'], ['a', 'b'], 'a', 'a')).toEqual(['a', 'b']);
    expect(moveKey(['a', 'b'], ['a', 'b'], 'zzz', 'a')).toEqual(['a', 'b']);
  });
});

describe('a copied command', () => {
  test('plain words stay plain, anything else is single-quoted with its own quotes escaped', () => {
    expect(shellWord('claude-grok')).toBe('claude-grok');
    expect(shellWord('-r')).toBe('-r');
    expect(shellWord('two words')).toBe("'two words'");
    expect(shellWord("it's")).toBe("'it'\\''s'");
  });
  test('a resume line is the argv, quoted', () =>
    expect(shellLine(['claude-grok', '-r', 'abc 1'])).toBe("claude-grok -r 'abc 1'"));
});
