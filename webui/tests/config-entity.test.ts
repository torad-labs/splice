// JW-06: the head-selector roster and the per-head fetch parameter — the per-head override
// layer existed in the daemon's precedence chain but was invisible to every consumer.
import { describe, expect, it } from 'vitest';
import { headOptions } from '../src/entities/config/model/store';

describe('headOptions (JW-06)', () => {
  it('is global-only when no head overrides anything', () => {
    expect(headOptions(undefined)).toEqual(['global']);
    expect(headOptions({})).toEqual(['global']);
  });

  it('lists override-carrying heads after global, sorted', () => {
    expect(headOptions({ kimi: { maxInflight: 8 }, claudex: { effort: 'high' } })).toEqual([
      'global',
      'claudex',
      'kimi',
    ]);
  });
});
