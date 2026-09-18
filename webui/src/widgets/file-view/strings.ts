// Labels of the file view. Three words or fewer, lowercase, no em-dash (the
// label wall globs this file). The honest empties that name a pending route or
// a missing directory are not labels and live in the component
// (CONTRACTS.md section 4).
export const S = {
  instructions: 'instructions',
  memory: 'memory',
  /** The head column of a repo's own file, which belongs to no head's config dir. */
  repo: 'repo',
  path: 'path',
  head: 'head',
  contents: 'show contents',
} as const;
