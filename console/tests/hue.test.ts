// A HEAD'S COLOUR IS ITS FAMILY (operator ruling 4, item 6, 2026-09-25): the hue comes from the
// provider family the daemon names on each registry entry, siblings of one family step in lightness
// in registry order, and a head's hue never moves when heads are added before it.
import { describe, expect, test } from 'vitest';
import type { RegistryEntry } from '@shared/api';
import { huesOf } from '../src/entities/control-status/model/hue';

function entry(key: string, family: string | null | undefined): RegistryEntry {
  return { key, label: key, authKind: 'api-key', ...(family === undefined ? {} : { family }) };
}

// The operator's ten heads as `splice status` lists them, each with the family its provider resolves to.
const OPERATOR: RegistryEntry[] = [
  entry('claudex', 'openai'),
  entry('claude-grok', 'xai'),
  entry('claude-kimi', 'moonshot'),
  entry('openrouter', 'openrouter'),
  entry('claude-splice', 'anthropic'),
  entry('claude-muse', 'meta'),
  entry('claude-deepseek', 'deepseek'),
  entry('bonsai', 'local'),
  entry('bonsai-vast', 'local'),
  entry('bonsai-second', 'local'),
];

describe('a head takes its family hue', () => {
  test('each family has the hue the operator ruled, whatever the wire it speaks', () => {
    const hues = huesOf(OPERATOR);
    expect(Object.fromEntries(hues)).toMatchObject({
      'claude-grok': '1', // blue
      'claude-splice': '2', // orange
      claudex: '3', // teal
      'claude-kimi': '4', // pink
      bonsai: '5', // lime
      'claude-muse': '6', // violet
      'claude-deepseek': '7', // sky: Anthropic-compatible on the wire, DeepSeek by provider
      openrouter: '8', // gold
    });
  });

  test('siblings share the hue and step in lightness in registry order: base, lighter, darker', () => {
    const hues = huesOf(OPERATOR);
    expect([hues.get('bonsai'), hues.get('bonsai-vast'), hues.get('bonsai-second')]).toEqual(['5', '5-up', '5-down']);
  });

  test('adding heads before a head never changes its hue', () => {
    const before = huesOf(OPERATOR);
    const grown = huesOf([entry('claude-fireworks', 'fireworks'), entry('proxy', null), entry('codex-2', 'openai'), ...OPERATOR]);
    for (const { key, family } of OPERATOR) {
      expect(grown.get(key)?.split('-')[0], `${key} (${family})`).toBe(before.get(key)?.split('-')[0]);
    }
  });
});

describe('a head with no family falls back to registry order', () => {
  test('over the slots no listed family claims, so it never wears a family colour', () => {
    const hues = huesOf([entry('claudex', 'openai'), entry('proxy', null), entry('bonsai', 'local'), entry('custom', 'fireworks')]);
    expect(hues.get('proxy')).toBe('1');
    expect(hues.get('custom')).toBe('2');
    expect(['3', '5']).not.toContain(hues.get('proxy'));
  });

  test('a daemon that names no families colours every head in registry order, as before', () => {
    const keys = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i'];
    const hues = huesOf(keys.map((key) => entry(key, undefined)));
    expect(keys.map((key) => hues.get(key))).toEqual(['1', '2', '3', '4', '5', '6', '7', '8', '1-up']);
  });
});
