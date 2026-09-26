// The words for a head the registry does not list, shared by every page that prints one (Marlin,
// 2026-09-25: Teams printed a plain `claude` slot's head as "claude" where Sessions printed the name
// below). One source, so the two pages cannot drift apart again.
export const S = {
  /** The name of a head the registry does not list: a session splice did not start, or a team slot
   *  on plain `claude`. */
  noHead: 'No splice head',
} as const;

export const H = {
  noHead: 'Splice did not start these, or cannot tell which head did.',
} as const;
