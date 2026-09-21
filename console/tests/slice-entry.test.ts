// EVERY SLICE'S PUBLIC DOOR OPENS IN THE TEST ENVIRONMENT (M1-81).
//
// WHY THIS EXISTS. An FSD slice has one public door: its `index.ts`, which is the only path the
// boundary rules let another slice import. M1-74 hit a failure while walking through it — a test
// importing a feature, which imports an entity's slice entry, died with "Cannot find package
// '@shared/lib'" — and the concern that came out of it is worth a wall whether or not that failure
// recurs: every test in this suite imports LEAF files directly (a page's strip module, an entity's
// model), so a door that cannot open is never noticed by the tests that don't use it. The boundary
// `eslint-plugin-boundaries` enforces is then untestable through its own entrance, and nobody chose
// that; it is a resolver gap wearing an architectural decision.
//
// WHAT IT ASSERTS, derived from the tree rather than listed: for every `src/<layer>/<slice>/index.ts`
// on disk, importing it resolves and evaluates. A door that only opens for the app and not for the
// tests fails here BY NAME, which is the only way the next person learns it before shipping.
//
// NOT A TEST OF THE LAYERS' CONTENTS: it does not assert what a door exports, only that opening it
// works. That is the property the failure was about, and pinning exports here would make this file
// a second, drifting declaration of every slice's API.
import { describe, expect, test } from 'vitest';

// A STATICALLY ANALYZABLE GLOB, and that is the second lesson this file carries (the first is
// M1-20's): a runtime-computed `import(someString)` is handed to Node rather than transformed by
// Vite, so every alias inside the door fails and the test reports the door as broken when the
// problem is the test. `import.meta.glob` is resolved at build time, so each door goes through
// exactly the resolver the app uses - which is the thing under test.
const DOORS = import.meta.glob('../src/*/*/index.{ts,tsx}') as Record<string, () => Promise<unknown>>;

describe('every slice index resolves through its public door', () => {
  const doors = Object.keys(DOORS).sort();

  test('the tree has doors to walk through', () => {
    // A derived list that came back empty would make every assertion below vacuous, which is the
    // failure this file exists to prevent in other walls (law 23).
    expect(doors.length).toBeGreaterThan(10);
  });

  for (const door of doors) {
    test(`${door.replace('../src/', '')} opens`, async () => {
      const module = await DOORS[door]();
      expect(module).toBeTypeOf('object');
    });
  }
});
