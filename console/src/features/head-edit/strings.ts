// Every word this feature prints (docs/design/DESIGN.md section 10): labels, three words or fewer,
// sentence case. A validator finding is one of these too: what is missing, in the fewest words.
export const S = {
  heads: 'Heads',
  declared: 'Declared',
  disable: 'Disable',
  /** The armed second half of the disable gesture. */
  confirmDisable: 'Confirm disable',
  addHead: 'Add head',
  provider: 'Provider',
  port: 'Port',
  prefix: 'Discovery prefix',
  pinned: 'Pinned model',
  window: 'Context window',
  key: 'Head key',
  /** Where a head row lives: the TOML file the topology section writes. */
  source: 'TOML',
  noProviders: 'No providers declared',
  keyRequired: 'Key required',
  providerRequired: 'Provider required',
  portRequired: 'Numeric port required',
  prefixRequired: 'Prefix required',
  modelRequired: 'Model required',
} as const;
