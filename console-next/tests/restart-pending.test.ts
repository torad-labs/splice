// The store over lib/config's restart reducers: settings wait under the boot the write reached, and only another boot clears them.
import { beforeEach, describe, expect, test } from 'vitest';
import { observeBoot, pendingNow, recordSaved, resetRestartPending } from '../src/lib/restart-pending';

describe('the restart-pending store', () => {
  beforeEach(resetRestartPending);
  test('a saved boot-only key waits, and the same boot does not clear it', () => {
    recordSaved(['usageWarnPct'], 100);
    observeBoot(100);
    expect(pendingNow()).toEqual(['usageWarnPct']);
  });
  test('a different boot is the restart and clears what waited', () => {
    recordSaved(['usageWarnPct', 'debug'], 100);
    observeBoot(200);
    expect(pendingNow()).toEqual([]);
  });
  test('no keys saved leaves nothing waiting', () => {
    recordSaved([], 100);
    expect(pendingNow()).toEqual([]);
  });
});
