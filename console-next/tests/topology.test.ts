// Walls for the topology validator (src/lib/topology.ts), ported from the old console's
// entities-accounts.test.ts ("the topology validator"), whole: every test there needs only the
// validator and the repo's own files. Nothing skipped.
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, test } from 'vitest';
import { SHARE_NAMES, validateTopology } from '../src/lib/topology';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const fromRepo = (relative: string): string => readFileSync(path.join(repoRoot, relative), 'utf8');

/** The share and isolate names the daemon matches: ClaudeSharingDefaults' list (TopologySchema.kt) and
 *  every alias ClaudePolicy.shares spells (ClaudeMaterializeTypes.kt), its `Keys.X` resolved through
 *  ClaudeConfigKeys.kt. Its `else -> setOf(item)` arm, any other item by its own name, has no list. */
function daemonShareNames(): string[] {
  const schema = fromRepo('core/src/main/kotlin/splice/core/topology/TopologySchema.kt');
  const defaults = /class ClaudeSharingDefaults\([\s\S]*?listOf\(([\s\S]*?)\)/.exec(schema)?.[1] ?? '';
  const client = 'integrations/claude-code/src/main/kotlin/splice/client/';
  const keys = new Map([...fromRepo(`${client}ClaudeConfigKeys.kt`).matchAll(/const val (\w+) = "([^"]*)"/g)]
    .map((match) => [`Keys.${match[1]}`, match[2]]));
  const shares = /fun shares\(item: String\)[\s\S]*?when \(item\.lowercase\(\)\) \{([\s\S]*?)\n\s*\}/
    .exec(fromRepo(`${client}ClaudeMaterializeTypes.kt`))?.[1] ?? '';
  const aliases = [...shares.matchAll(/setOf\(([^)]*)\)/g)].flatMap((match) =>
    (match[1] ?? '').split(',').map((arg) => arg.trim()).filter((arg) => arg !== 'item'));
  const names = [...[...defaults.matchAll(/"([^"]+)"/g)].map((match) => match[1] ?? ''),
    ...aliases.map((arg) => keys.get(arg) ?? /^"([^"]+)"$/.exec(arg)?.[1] ?? `unresolved ${arg}`)];
  return [...new Set(names)];
}

describe('the topology validator', () => {
  // The documented shape (FEATURES.md 2.3), one of every construct it has to walk: a plain table,
  // a table of typed keys, a table keyed by free names, an array of tables, a nested sub-table and
  // a bag whose child keys are deliberately not schema.
  const EXAMPLE = {
    daemon: { control_port: 3096, show_reasoning: 'text', mcp_hosting: true, mcp_hosting_exclude: ['x'] },
    claude: { share: ['settings', 'mcps'] },
    compaction: {
      instructions: 'Keep every file path verbatim.',
      model: [{ model: 'gpt-6-astra', file: '~/compaction.md' }],
      project: [{ path: '/home/me/app', model: 'gpt-6-astra', instructions: 'Summarize the plan first.' }],
    },
    defaults: { maxInflight: '4', effort: 'high', statuslineGitRoots: 'a,b' },
    providers: {
      codex: {
        dialect: 'openai-responses',
        base_url: 'https://chatgpt.com/backend-api/codex',
        auth: { kind: 'chatgpt-oauth' },
        quirks: {
          store: false,
          account_id_header: true,
          tool_surface: { enabled: true, defer: ['LSP'], search_limit: 8 },
        },
        extra_headers: { 'x-anything': 'a bag, not a schema' },
        models: [{ id: 'gpt-6-astra', label: 'Astra', context_window: 400000, rates: { input: 1.25, cache_read: 0.125, output: 10 } }],
      },
    },
    heads: {
      claudex: {
        provider: 'codex',
        port: 3100,
        discovery_prefix: 'claudex-',
        pinned_model: 'gpt-6-astra',
        models: [{ id: 'gpt-6-astra', slot: 'opus' }],
        overrides: { effort: 'max' },
        claude: { command: 'claude', config_dir: '~/.config/splice/claude', isolate: ['projects'] },
        system_prompt: 'you are',
        system_prompt_mode: 'append',
        rates: { 'gpt-6-astra': { input: 1.25, cache_read: 0.125, output: 10 } },
      },
    },
    projects: { '/home/me/app': { system_prompt: 'Plan first.', heads: { claudex: { system_prompt_mode: 'append' } } } },
  };

  // In the shapes the daemon's loader parses (V4-312): share and isolate are lists, a head's [claude]
  // carries config_dir and isolate, a model row carries its own rate card, and [projects] is a table.
  test('accepts the documented example', () => {
    expect(validateTopology(EXAMPLE)).toEqual([]);
  });

  test('accepts a rate card\'s long-context tier, the keys TomlRates reads (V4-240)', () => {
    const tier = {
      input: 5, cache_read: 0.5, output: 25, cache_write: 6.25, long_context_over_input_tokens: 200_000,
      long_context_input: 10, long_context_cache_read: 1, long_context_output: 37.5, long_context_cache_write: 12.5,
    };
    expect(validateTopology({ heads: { claude: { rates: { 'claude-opus-5-5': tier } } } })).toEqual([]);
  });

  test('rejects an unknown key and names its path', () => {
    expect(validateTopology({ daemon: { control_port: 3096, wibble: true } }))
      .toEqual([{ path: 'daemon.wibble', message: 'unknown key' }]);
  });

  test('reaches into quirks, a nested sub-table', () => {
    expect(validateTopology({ providers: { codex: { quirks: { nope: 1 } } } }))
      .toEqual([{ path: 'providers.codex.quirks.nope', message: 'unknown key' }]);
  });

  test('reaches into an array of tables, which is where much of a real topology lives', () => {
    expect(validateTopology({ providers: { codex: { models: [{ id: 'x', typo: 1 }] } } }))
      .toEqual([{ path: 'providers.codex.models[0].typo', message: 'unknown key' }]);
  });

  // V4-312: `share` and `isolate` are lists of names (TopologySchema.kt ClaudeSharingDefaults,
  // ClaudeWrapperConfig); the daemon refuses the table form this test once used, so it never fired.
  // V4-323: the daemon matches any other on-disk item by its own name (ClaudePolicy.shares' `else ->
  // setOf(item)`), so a name outside the known ones may be legal: it is a notice, never a refusal.
  // A misspelling is still named, the one failure the daemon reports as silence.
  test('names a share entry that is no known item, without refusing it', () => {
    expect(validateTopology({ claude: { share: ['settings', 'skils'] } }))
      .toEqual([{ path: 'claude.share[1]', message: 'no known item' }]);
    expect(validateTopology({ heads: { claudex: { claude: { isolate: ['sesions'] } } } }))
      .toEqual([{ path: 'heads.claudex.claude.isolate[0]', message: 'no known item' }]);
  });

  test("takes the shipped example's own share list (splice.example.toml, V4-323)", () => {
    const line = /^share = (\[.*\])/m.exec(fromRepo('app/src/main/resources/splice.example.toml'))?.[1];
    const share = JSON.parse(line ?? '[]') as string[];
    expect(share, 'the example shares a list').toContain('CLAUDE.md');
    expect(validateTopology({ claude: { share } })).toEqual([]);
  });

  test('knows exactly the names the daemon matches, read from its Kotlin (V4-323)', () => {
    expect([...SHARE_NAMES].sort()).toEqual(daemonShareNames().sort());
  });

  test("takes a head's model_slots under exactly the tiers the daemon knows, read from its Kotlin", () => {
    const kotlin = fromRepo('core/src/main/kotlin/splice/core/topology/Topology.kt');
    const listed = /val headModelSlots = setOf\(([^)]*)\)/.exec(kotlin)?.[1] ?? '';
    const tiers = [...listed.matchAll(/"([^"]+)"/g)].map((match) => match[1] ?? '');
    expect(tiers.length).toBeGreaterThan(0);
    for (const tier of tiers) expect(validateTopology({ heads: { h: { model_slots: { [tier]: 'wire/model' } } } })).toEqual([]);
    expect(validateTopology({ heads: { h: { model_slots: { opus: 'a', sonet: 'b' } } } }))
      .toEqual([{ path: 'heads.h.model_slots.sonet', message: 'unknown key' }]);
  });

  test('rejects an unknown top-level table', () => {
    expect(validateTopology({ daemons: {} }))
      .toEqual([{ path: 'daemons', message: 'unknown key' }]);
  });

  test('an unknown knob under [defaults] is still an unknown key', () => {
    expect(validateTopology({ defaults: { maxInflight: '4', wibble: true } }))
      .toEqual([{ path: 'defaults.wibble', message: 'unknown key' }]);
  });

  test('a bag is not key-checked, so a legal extra header stays legal', () => {
    expect(validateTopology({ providers: { codex: { extra_headers: { 'x-anything': '1' } } } })).toEqual([]);
  });

  test('never throws on a shape it does not expect', () => {
    expect(validateTopology(null)).toEqual([]);
    expect(validateTopology('not a topology')).toEqual([]);
    expect(validateTopology({ providers: 'a string where a table belongs' })).toEqual([]);
  });
});
