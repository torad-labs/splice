// What an api-key head's key control reads from the key store list, and how it words a write's answer.
import { describe, expect, test } from 'vitest';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { keyStoreKey } from '../src/api/auth';
import { keys } from '../src/api/queries';
import { HeadKey } from '../src/pages/fleet/HeadKey';
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

describe('the configured key file', () => {
  const render = (keyFile: string | undefined, source = 'file'): string => {
    const client = new QueryClient();
    client.setQueryData([...keyStoreKey, '/api/keys'], { path: '/synthetic/key-store', keys: [{ name: 'SYNTHETIC_KEY', stored: false, heads: [{ head: 'synthetic-head', source }] }] });
    client.setQueryData([...keys.auth, '/api/auth'], { 'synthetic-head': { kind: 'api-key', login: 'synthetic', present: true, api_key_masked: 'synthetic-mask-not-for-rendering', ...(keyFile === undefined ? {} : { key_file: keyFile }) } });
    return renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(HeadKey, { head: 'synthetic-head' })));
  };
  test.each(['file', 'environment', 'store', 'missing'])('keeps its actual configured path separate from the %s read source', source => {
    const html = render('/synthetic/provider config/provider.key', source);
    expect(html).toContain('Configured key file');
    expect(html).toContain('/synthetic/provider config/provider.key');
    expect(html).not.toContain('/synthetic/key-store');
    expect(html).not.toContain('synthetic-mask-not-for-rendering');
  });
  test('never substitutes the key-store path for an absent configured file', () => {
    const html = render(undefined);
    expect(html).not.toContain('Configured key file');
    expect(html).not.toContain('/synthetic/key-store');
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
