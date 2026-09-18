// The label wall's synthetic violations: exactly four, one per key, mixed in
// with four compliant labels so the fixture also proves the checker does not
// flag everything. No `src/**/strings.ts` exists yet, so this fixture — not the
// tree — is what proves the wall can fail.
export const S = {
  okShort: 'fleet',
  okThreeWords: 'open log tail',
  tooManyWords: 'stop every running head now',
  emDash: 'splice — daemon',
  uppercase: 'Fleet',
  nested: {
    tooManyWords: 'read the newest log tail',
  },
} as const;
