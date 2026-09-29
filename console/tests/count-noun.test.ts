// V4-435: a count of one is singular and every other count is plural. `noun` is the one place that
// picks, so a sentence that prints a count beside a word asks it for the word and keeps its own
// number formatting.
import { describe, expect, test } from 'vitest';
import { noun } from '../src/shared/lib';

describe('the noun that agrees with a count', () => {
  test('one takes the singular, and zero, two and a thousand take the plural', () => {
    expect(noun(1, 'message', 'messages')).toBe('message');
    expect(noun(0, 'message', 'messages')).toBe('messages');
    expect(noun(2, 'message', 'messages')).toBe('messages');
    expect(noun(1000, 'message', 'messages')).toBe('messages');
  });

  test('an irregular plural is spelled by the caller, not guessed', () => {
    expect(noun(1, 'retry', 'retries')).toBe('retry');
    expect(noun(3, 'retry', 'retries')).toBe('retries');
  });
});
