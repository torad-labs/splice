// Every word a compaction rule prints (docs/design/DESIGN.md section 10). S holds labels: three words
// or fewer, sentence case. tests/copy.test.ts holds them.
export const S = {
  rules: 'Compaction rules',
  scope: 'Scope',
  source: 'Source',
  chars: 'Length',
  heads: 'Heads',
  /** A rule whose text is empty: the client's own instructions stand. */
  optOut: 'Client default',
  /** A rule whose file cannot be read. */
  unavailable: 'Unavailable',
} as const;

// The two words the rule strip still prints on the compaction page until the routing pages' rebuild
// (claude-builder's #264) retires the strip; deleted with it.
export const LEGACY = {
  rule: 'Rule',
  instruction: 'Instruction',
} as const;
