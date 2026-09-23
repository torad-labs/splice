// M4-05: the compaction page reads GET /api/compaction/instructions (V4-136) for every head. The
// route answers per head, one entry per configured rule that applies to it ({scopes: [{scope,
// source, chars}]}, CompactionInstructionsRoute), and no text. Under test: per-head answers merge
// into one strip per rule carrying its heads, a model rule keeps only the heads that listed it, one
// head's refusal is named without blanking the rest, and a length's two special values print as
// what they mean.
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { fetchInstructions } from '../src/entities/compact-stats';
import type { InstructionsWire } from '../src/entities/compact-stats';
import { mergeInstructions } from '../src/entities/compact-stats/model/instructions';
import { instructionsStore } from '../src/entities/compact-stats/model/store';
import { InstructionsBay, charsText } from '../src/pages/compaction';

const h = React.createElement;

// What the live daemon answered for the e2e stack on 2026-09-23: the codex head's roster carries
// the model the model rule names, the solo head's does not.
const CODEX: InstructionsWire = {
  scopes: [
    { scope: 'project', source: 'project:/tmp/e2e-repo', chars: 36 },
    { scope: 'model', source: 'model:e2e-model', chars: 31 },
    { scope: 'global', source: 'global', chars: 41 },
  ],
};
const SOLO: InstructionsWire = {
  scopes: [
    { scope: 'project', source: 'project:/tmp/e2e-repo', chars: 36 },
    { scope: 'global', source: 'global', chars: 41 },
  ],
};

describe('merging the per-head answers', () => {
  test('one rule per configured entry, with every head that listed it, in precedence order', () => {
    expect(mergeInstructions([{ head: 'e2e-codex', wire: CODEX }, { head: 'e2e-codex-solo', wire: SOLO }])).toEqual([
      { scope: 'project', source: 'project:/tmp/e2e-repo', chars: 36, heads: ['e2e-codex', 'e2e-codex-solo'] },
      { scope: 'model', source: 'model:e2e-model', chars: 31, heads: ['e2e-codex'] },
      { scope: 'global', source: 'global', chars: 41, heads: ['e2e-codex', 'e2e-codex-solo'] },
    ]);
  });

  test('a project-model rule outranks a project rule, whatever order the heads came in', () => {
    const merged = mergeInstructions([
      { head: 'a', wire: { scopes: [{ scope: 'global', source: 'global', chars: 1 }] } },
      { head: 'b', wire: { scopes: [{ scope: 'project-model', source: 'project:/r model:m', chars: 2 }] } },
    ]);
    expect(merged.map((rule) => rule.scope)).toEqual(['project-model', 'global']);
  });

  test('no configured rule on any head is no rule', () => {
    expect(mergeInstructions([{ head: 'a', wire: { scopes: [] } }])).toEqual([]);
  });
});

describe('reading every head', () => {
  afterEach(() => vi.unstubAllGlobals());

  function stub(answers: Record<string, { status: number; body: unknown }>): string[] {
    const urls: string[] = [];
    vi.stubGlobal('fetch', (input: unknown): Promise<Response> => {
      const url = String(input);
      urls.push(url);
      const answer = url === '/api/heads'
        ? { status: 200, body: { heads: Object.keys(answers).map((key) => ({ key })) } }
        : answers[new URLSearchParams(url.split('?')[1]).get('head') ?? ''] ?? { status: 404, body: {} };
      return Promise.resolve(new Response(JSON.stringify(answer.body), { status: answer.status, headers: { 'content-type': 'application/json' } }));
    });
    return urls;
  }

  test('asks each configured head by key, and merges the answers', async () => {
    const urls = stub({ 'e2e-codex': { status: 200, body: CODEX }, 'e2e-codex-solo': { status: 200, body: SOLO } });
    await fetchInstructions();
    expect(urls).toEqual([
      '/api/heads',
      '/api/compaction/instructions?head=e2e-codex',
      '/api/compaction/instructions?head=e2e-codex-solo',
    ]);
    expect(instructionsStore.get().data?.rules.map((rule) => [rule.source, rule.heads])).toEqual([
      ['project:/tmp/e2e-repo', ['e2e-codex', 'e2e-codex-solo']],
      ['model:e2e-model', ['e2e-codex']],
      ['global', ['e2e-codex', 'e2e-codex-solo']],
    ]);
    expect(instructionsStore.get().data?.unread).toEqual([]);
  });

  test('one head refused is named in the daemon\'s words, and the others still land', async () => {
    const reason = 'the daemon did not wire the compaction table; /api/compaction/instructions cannot report it';
    stub({ 'e2e-codex': { status: 200, body: CODEX }, 'e2e-openrouter': { status: 503, body: { error: reason } } });
    await fetchInstructions();
    expect(instructionsStore.get().data?.rules).toHaveLength(3);
    expect(instructionsStore.get().data?.unread).toEqual([{ head: 'e2e-openrouter', reason }]);
  });

  test('every head refused is an error, never an empty rule list', async () => {
    stub({ 'e2e-codex': { status: 400, body: { error: 'unknown head: e2e-codex' } } });
    await fetchInstructions();
    expect(instructionsStore.get().error).toBe('unknown head: e2e-codex');
  });
});

describe('the rules bay', () => {
  test('a length prints as a count, an opt-out as a decision, an unreadable file as unavailable', () => {
    expect(charsText(41)).toBe('41');
    expect(charsText(0)).toBe('opt-out');
    expect(charsText(null)).toBe('unavailable');
  });

  test('prints one strip per rule, labelled by its source, with its length and heads', () => {
    const rules = mergeInstructions([{ head: 'e2e-codex', wire: CODEX }, { head: 'e2e-codex-solo', wire: SOLO }]);
    const out = renderToStaticMarkup(h(InstructionsBay, { instructions: { rules, unread: [] } }));
    expect(out).toContain('aria-label="instruction model:e2e-model"');
    expect(out).toContain('>31<');
    expect(out).toContain('e2e-codex e2e-codex-solo');
  });

  test('no rule says the client\'s instructions stand, and names where rules are declared', () => {
    const out = renderToStaticMarkup(h(InstructionsBay, { instructions: { rules: [], unread: [] } }));
    expect(out).toContain('no rule configured: the client instructions stand');
    expect(out).toContain('[compaction] in splice.toml');
  });

  test('a head that could not be asked is named with its reason', () => {
    const out = renderToStaticMarkup(h(InstructionsBay, { instructions: { rules: [], unread: [{ head: 'e2e-openrouter', reason: 'HTTP 503' }] } }));
    expect(out).toContain('e2e-openrouter: HTTP 503');
  });
});
