// The kit's own words (docs/design/DESIGN.md section 10): labels, three words or fewer, sentence
// case. tests/copy.test.ts holds them.
export const S = {
  /** The info mark beside an empty state's line, which explains what would fill it. */
  why: 'Why',
} as const;

/** A number's basis, printed only when it is not the default: a measured figure says nothing, and
 *  an estimated, stale or unavailable one says so (docs/design/DESIGN.md section 10). */
export const BASIS = {
  estimated: 'Estimated',
  unavailable: 'Unavailable',
  stale: 'Stale',
} as const;
