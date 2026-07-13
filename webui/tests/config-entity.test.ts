// Config domain logic: diff computation (powers the confirm modal) and input
// parsing back into the config value space.
import { describe, expect, test } from 'vitest';
import { diffPatch, parseConfigInput } from '../src/entities/config/model/store';

describe('diffPatch', () => {
  const effective = { effort: null, maxInflight: 0, debug: false, port: 3099 };

  test('reports only actual changes, with restart flags', () => {
    const entries = diffPatch(effective, { effort: 'low', maxInflight: 0, port: 4099 }, ['port']);
    expect(entries).toEqual([
      { key: 'effort', from: null, to: 'low', restartRequired: false },
      { key: 'port', from: 3099, to: 4099, restartRequired: true },
    ]);
  });

  test('empty patch and no-op patch produce no entries', () => {
    expect(diffPatch(effective, {}, [])).toEqual([]);
    expect(diffPatch(effective, { debug: false }, [])).toEqual([]);
  });
});

describe('parseConfigInput', () => {
  test('null forms', () => {
    expect(parseConfigInput('', null)).toBeNull();
    expect(parseConfigInput('null', 'text')).toBeNull();
  });
  test('booleans follow the reference value or literal true/false', () => {
    expect(parseConfigInput('true', false)).toBe(true);
    expect(parseConfigInput('false', true)).toBe(false);
  });
  test('numbers parse when reference is numeric or the string is integral', () => {
    expect(parseConfigInput('4242', 3099)).toBe(4242);
    expect(parseConfigInput('180000', null)).toBe(180000);
  });
  test('strings pass through', () => {
    expect(parseConfigInput('gpt-5.6-luna', 'gpt-5.6-sol')).toBe('gpt-5.6-luna');
  });
});
