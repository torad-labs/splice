// The budget entity's pure half: which budget a head has. Ported from console/tests/mcp-doctor.test.ts
// ('a head absent from the payload has no budget'); the rest of that file drives old widgets and stores.
import { describe, expect, test } from 'vitest';
import { budgetFor } from '../src/lib/budget';

describe('which budget a head has', () => {
  test('a head absent from the payload has no budget', () => {
    expect(budgetFor({ budgets: [] }, 'a')).toBeNull();
    expect(budgetFor(null, 'a')).toBeNull();
  });
});
