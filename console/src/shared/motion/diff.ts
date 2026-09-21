// The key diff one bay runs on every render: which strips are new, which are gone, which stayed.
//
// KEYS, NOT IDENTITY. A bay holds strips whose own keys are stable across a refetch (a head key, a
// session id), so `A, B, C` becoming `B, C, D` is D printed, A struck, and B and C untouched — and
// that has to be computed from the key lists alone, because the strip data a bay is handed may be
// a brand new array of brand new objects on every poll. Comparing objects would call every row new
// on every tick and fire the print gesture at a page that has not changed.
//
// Pure, and deliberately knowable without a DOM: this is the part of the motion layer a test can
// pin, and it is the part that decides what the other two gestures even fire on.

export interface KeyDiff {
  /** Keys in `next` that were not in `previous`, in `next` order: these PRINT. */
  entered: string[];
  /** Keys in `previous` that are not in `next`, in `previous` order: these are STRUCK and leave. */
  left: string[];
  /** Keys in both, in `next` order: nothing happens to these. */
  stayed: string[];
}

export function diffKeys(previous: readonly string[], next: readonly string[]): KeyDiff {
  const before = new Set(previous);
  const after = new Set(next);
  return {
    entered: next.filter((key) => !before.has(key)),
    left: previous.filter((key) => !after.has(key)),
    stayed: next.filter((key) => before.has(key)),
  };
}

/**
 * The keys of a rendered child list, with a stable fallback for a child that carries no key.
 *
 * A keyless child gets its index. That is the correct reading of "this list is positional": an
 * unkeyed row has no identity to transfer, so it can only ever print or strike in place, and
 * React's own reconciliation says the same thing.
 */
export function keysOf(children: readonly { key: string | null }[]): string[] {
  return children.map((child, index) => child.key ?? `#${index}`);
}
