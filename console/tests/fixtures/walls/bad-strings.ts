// The copy wall's synthetic violations (half 1: copy modules). CONTRACTS.md / the 2026-09-25 voice
// ruling: a label is sentence case, three words or fewer; `H` is help text, one sentence, at most
// twelve words, starting uppercase; `U` is a unit fragment, at most two words, any case. Each
// violation below is isolated to exactly one problem so the wall's exact findings list stays
// legible; the compliant entries prove the checker does not flag everything. No `src/**/strings.ts`
// exists with this shape yet, so this fixture — not the tree — is what proves the wall can fail.
export const S = {
  sessions: 'Sessions',
  lastSeen: 'Last seen',
  commandK: '⌘K',
  lowercaseStart: 'fleet',
  fourWords: 'Stop every running head',
  emDash: 'Splice — daemon',
  trailingPeriod: 'Restart daemon.',
} as const;

export const H = {
  ok: 'Restart daemon now.',
  tooLong: 'Restart moves the head to a clean process and this sentence keeps going past the limit.',
  twoSentences: 'Restart the head. It comes back on its own.',
} as const;

export const U = {
  ok: 'ago',
  threeWords: 'turns per minute',
} as const;
