// Every label a compaction rule strip prints. Lowercase, three words or fewer, no em-dash
// (CONTRACTS.md section 4, enforced by the label wall).
export const S = {
  /** The strip's holder edge and its aria label prefix. */
  rule: 'rule',
  instruction: 'instruction',
  scope: 'scope',
  source: 'source',
  chars: 'chars',
  heads: 'heads',
  /** A rule whose text is empty: the client's own instructions stand. */
  optOut: 'opt-out',
  /** A rule whose file cannot be read. */
  unavailable: 'unavailable',
} as const;
