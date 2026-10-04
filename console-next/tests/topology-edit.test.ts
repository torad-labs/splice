// The configuration-file editor's model: scalars by path, the tables a file is written in, copy-on-write edits and the review.
import { describe, expect, test } from 'vitest';
import { changedPaths, coerce, flattenTopology, groupOf, headOverrideOf, parseList, setAtPath, toToml, topologyTables, valueAtPath } from '../src/lib/topology-edit';

const doc = {
  daemon: { control_port: 3096, mcp_hosting: true },
  providers: { xai: { dialect: 'openai-chat', models: [{ id: 'grok-4' }, { id: 'grok-5' }] } },
  heads: { grok: { provider: 'xai', share: ['skills', 'hooks'], overrides: { maxInflight: '4' } } },
};

describe('reading the document', () => {
  test('lists every scalar by dotted path with array indices, sorted', () => {
    expect(flattenTopology(doc).map((leaf) => leaf.path)).toEqual([
      'daemon.control_port', 'daemon.mcp_hosting', 'heads.grok.overrides.maxInflight', 'heads.grok.provider', 'heads.grok.share[0]', 'heads.grok.share[1]', 'providers.xai.dialect', 'providers.xai.models[0].id', 'providers.xai.models[1].id',
    ]);
    expect(valueAtPath(doc, 'providers.xai.models[1].id')).toBe('grok-5');
    expect(valueAtPath(doc, 'providers.nope.x')).toBeUndefined();
  });
  test('groups tables as the file writes them, with a choice for the provider and a list for share', () => {
    const tables = topologyTables(doc);
    expect(tables.map((table) => table.path)).toEqual(['daemon', 'providers.xai', 'providers.xai.models[0]', 'providers.xai.models[1]', 'heads.grok', 'heads.grok.overrides']);
    const head = tables.find((table) => table.path === 'heads.grok');
    expect(head?.fields.find((field) => field.key === 'provider')).toMatchObject({ kind: 'choice', choices: ['xai'] });
    expect(head?.fields.find((field) => field.key === 'share')).toMatchObject({ kind: 'list' });
    expect(tables.find((table) => table.path === 'daemon')?.fields.map((field) => field.kind)).toEqual(['number', 'flag']);
    expect(groupOf('providers.xai.models[0]')).toBe('providers');
  });
});

describe('a head with model_slots and no models list', () => {
  const slotted = { heads: { openrouter: { provider: 'openrouter', model_slots: { opus: 'anthropic/claude-opus-5', sonnet: 'anthropic/claude-sonnet-5' } } } };
  test('its tiers are text fields of their own table, and an edit changes one path', () => {
    const table = topologyTables(slotted).find((entry) => entry.path === 'heads.openrouter.model_slots');
    expect(table?.fields.map((field) => [field.key, field.kind, field.value])).toEqual([
      ['opus', 'text', 'anthropic/claude-opus-5'], ['sonnet', 'text', 'anthropic/claude-sonnet-5'],
    ]);
    const next = setAtPath(slotted, 'heads.openrouter.model_slots.sonnet', 'other/model');
    expect(changedPaths(slotted, next)).toEqual(['heads.openrouter.model_slots.sonnet']);
  });
  test('the raw view writes it as its own table under the head', () => {
    expect(toToml(slotted)).toContain('[heads.openrouter.model_slots]\nopus = "anthropic/claude-opus-5"');
  });
});

describe('editing', () => {
  test('a write returns a new document and leaves the old one as it was', () => {
    const before = JSON.stringify(doc);
    const next = setAtPath(doc, 'providers.xai.models[1].id', 'grok-6');
    expect(valueAtPath(next, 'providers.xai.models[1].id')).toBe('grok-6');
    expect(valueAtPath(next, 'providers.xai.models[0].id')).toBe('grok-4');
    expect(JSON.stringify(doc)).toBe(before);
  });
  test('a blank plan override removes the key, and the last one removes the table', () => {
    expect(headOverrideOf('heads.grok.overrides.maxInflight')).toEqual({ head: 'grok', key: 'maxInflight' });
    expect(headOverrideOf('heads.grok.provider')).toBeNull();
    const next = setAtPath(doc, 'heads.grok.overrides.maxInflight', '');
    expect(next).toEqual({ ...doc, heads: { grok: { provider: 'xai', share: ['skills', 'hooks'] } } });
  });
  test('a text edit keeps the type the value had, and an unparseable number keeps the old one', () => {
    expect(coerce('3097', 3096)).toBe(3097);
    expect(coerce('abc', 3096)).toBe(3096);
    expect(coerce('true', false)).toBe(true);
    expect(coerce('3097', 'text')).toBe('3097');
    expect(parseList(' a, b ,,c', ['x'])).toEqual(['a', 'b', 'c']);
    expect(parseList('1, 2, x', [1])).toEqual([1, 2]);
  });
  test('the review names exactly the paths that differ', () => {
    const next = setAtPath(setAtPath(doc, 'daemon.control_port', 3100), 'heads.grok.provider', 'other');
    expect(changedPaths(doc, next)).toEqual(['daemon.control_port', 'heads.grok.provider']);
    expect(changedPaths(doc, doc)).toEqual([]);
  });
});

describe('the raw view', () => {
  test('renders scalars, inline lists, tables and arrays of tables, and skips null', () => {
    const text = toToml({ a: 1, b: null, c: ['x', 'y'], t: { s: 'q"r' }, list: [{ id: 'one' }] });
    expect(text).toBe('a = 1\nc = ["x", "y"]\n\n[[list]]\nid = "one"\n\n[t]\ns = "q\\"r"\n');
  });
});
