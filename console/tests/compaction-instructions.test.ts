// M4-05: the compaction page reads GET /api/compaction/instructions (V4-136) for every head. The
// route answers per head, one entry per configured rule that applies to it ({scopes: [{scope,
// source, chars}]}, CompactionInstructionsRoute), and no text. Under test: per-head answers merge
// into one row per rule carrying its heads, a model rule keeps only the heads that listed it, one
// head's refusal is named without blanking the rest, and a length's two special values print as
// what they mean.
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { fetchInstructions } from '../src/entities/compact-stats';
import type { InstructionsWire } from '../src/entities/compact-stats';
import { mergeInstructions } from '../src/entities/compact-stats/model/instructions';
import { instructionsStore } from '../src/entities/compact-stats/model/store';
import { CompactionRules, RuleLength } from '../src/widgets/compaction-rule';

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

describe("a rule's length", () => {
  test('a length is a bar with its count, an empty rule the client default, an unreadable file unavailable', () => {
    const cell = (chars: number | null) => renderToStaticMarkup(h(RuleLength, { rule: { scope: 'global', source: 'global', chars }, longest: 82 }));
    expect(cell(41)).toContain('role="meter"');
    expect(cell(41)).toContain('aria-valuenow="50"');
    expect(cell(41)).toContain('>41<');
    expect(cell(0)).toContain('>Client default<');
    expect(cell(0), 'zero is a decision, never drawn as a length').not.toContain('role="meter"');
    expect(cell(null)).toContain('>Unavailable<');
  });

  test('the column names its unit, so a bare count reads as characters (Hitstop, 2026-09-25)', () => {
    const table = renderToStaticMarkup(h(CompactionRules, { rules: [{ scope: 'global', source: 'global', chars: 53 }] }));
    expect(table).toContain('>Characters<');
    expect(table).not.toContain('>Length<');
  });
});
