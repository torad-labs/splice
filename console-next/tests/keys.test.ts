// What an api-key head's key control reads from the key store list, and how it words a write's answer.
import { describe, expect, test } from 'vitest';
import { answerLines, headKeys, readerLine, sourceWord } from '../src/lib/keys';
import type { KeysPayload } from '../src/types/login';

const store: KeysPayload = {
  path: '/home/x/.config/splice/keys',
  keys: [
    { name: 'DEEPSEEK_API_KEY', stored: true, heads: [{ head: 'claude-deepseek', source: 'store' }, { head: 'claude-ds2', source: 'environment' }] },
    { name: 'OPENROUTER_API_KEY', stored: false, heads: [{ head: 'claude-router', source: 'missing' }] },
  ],
};

describe('the keys an api-key head reads', () => {
  test('are the store entries that name the head, with where that head reads each from', () => {
    expect(headKeys(store, 'claude-deepseek')).toEqual([{ name: 'DEEPSEEK_API_KEY', stored: true, source: 'store' }]);
    expect(headKeys(store, 'claude-ds2')).toEqual([{ name: 'DEEPSEEK_API_KEY', stored: true, source: 'environment' }]);
    expect(headKeys(store, 'claude-router')).toEqual([{ name: 'OPENROUTER_API_KEY', stored: false, source: 'missing' }]);
  });
  test('are none for a head no key lists, and none before the store has answered', () => {
    expect(headKeys(store, 'claudex')).toEqual([]);
    expect(headKeys(undefined, 'claude-deepseek')).toEqual([]);
  });
  test('each link of the read chain has a word, and a link this console does not know prints as itself', () => {
    expect(['environment', 'file', 'store', 'missing'].map(sourceWord)).toEqual(['Environment', 'Key file', 'Key store', 'Nowhere']);
    expect(sourceWord('vault')).toBe('vault');
  });
});

describe('what a write answers', () => {
  test('says, per link, what the head reads now, and a shadowed stored key is never called applied', () => {
    expect(readerLine({ head: 'h', source: 'store' }, 'K')).toBe('h uses the stored key from its next request.');
    expect(readerLine({ head: 'h', source: 'environment' }, 'K')).toBe('h still reads K from the service environment.');
    expect(readerLine({ head: 'h', source: 'file' }, 'K')).toBe('h still reads the key in its key file.');
    expect(readerLine({ head: 'h', source: 'missing' }, 'K')).toBe('h has no key now.');
    expect(readerLine({ head: 'h', source: 'vault' }, 'K')).toBe('h reads its key from: vault.');
  });
  test('names only the head whose page it is, and nothing when no head reads the key', () => {
    const state = { name: 'K', stored: true, heads: [{ head: 'a', source: 'store' }, { head: 'b', source: 'missing' }] };
    expect(answerLines(state, 'a')).toEqual(['a uses the stored key from its next request.']);
    expect(answerLines({ ...state, heads: [] }, 'a')).toEqual([]);
  });
});
