// Every word this page prints (docs/design/DESIGN.md section 10). S holds labels: three words or
// fewer, sentence case. H holds help: one sentence of twelve words or fewer, shown on hover or focus.
// tests/copy.test.ts holds both.
export const S = {
  title: 'Settings',
  about: 'About settings',
  instructions: 'Instructions',
  aboutInstructions: 'About instructions',
  addInstructions: 'Add instructions',
  noInstructions: 'No shared instructions',
  commandInstructions: 'Command instructions',
  compactionInstructions: 'Compaction instructions',
  command: 'Command',
  addCommandInstructions: 'Add command instructions',
  removeCommandInstructions: 'Remove command instructions',
  saveCommandInstructions: 'Save instructions',
  instructionSource: 'Instruction source',
  inlineSource: 'Write text',
  fileSource: 'Use file',
  instructionText: 'Instruction text',
  matchingParagraphs: 'Matching paragraphs',
  instructionFile: 'Instruction file',
  instructionMode: 'How to use',
  addMode: 'Add',
  replaceMode: 'Replace',
  stripMode: 'Remove parts',
  preview: 'Preview',
  chars: 'Characters',
  firstLines: 'First lines',
  knobs: 'Runtime settings',
  /** The group of scope buttons: global, then one per plan. */
  scope: 'Setting scope',
  scopeWhy: 'About the scope',
  global: 'Global',
  find: 'Find a setting',
  findHint: 'Name or key',
  /** splice.toml as the operator edits it: the file's name, not the word for its shape (V4-403). */
  topology: 'Config file',
  plans: 'Plans',
  /** A top-level table of splice.toml as its bay is labeled: the operator's words for the two whose key is ours. */
  groupName: { daemon: 'Splice', heads: 'Plans' } as Readonly<Record<string, string>>,
  topologyWhy: 'About writing',
  /** The topology file's path, printed once for the section. */
  file: 'File',
  /** A value written at the top of splice.toml, outside any table. */
  topLevel: 'Top level',
  claudeHead: 'Claude plan',
  modeWhy: 'About this mode',
  separate: 'Separate',
  wrapped: 'Wrapped',
  wrap: 'Wrap',
  unwrapLabel: 'Unwrap',
  confirmUnwrap: 'Confirm unwrap',
  confirmWrap: 'Confirm wrap',
  rawToml: 'Raw file',
  showDiff: 'Show diff',
  write: 'Write file',
  removeOverride: 'Remove override',
  changed: 'Changed fields',
  written: 'Written',
  refused: 'Refused',
  allKnobs: 'All settings',
  live: 'Applies live',
  /** The instruction after splice.toml is written or a knob saved: the daemon reads it on start. */
  restart: 'Restart to apply',
  /** The view of restart-only knobs, named as each knob in it says it (knob-form `Applies on
   *  restart`). `Restart to apply` above is the instruction after splice.toml is written. */
  restartView: 'Applies on restart',
  sample: 'Sample data',
  onPath: 'Claude on PATH',
  shim: 'Shim',
  realBinary: 'Real binary',
  logins: 'Stored logins',
  selected: 'Selected login',
  backups: 'Backup files',
  on: 'On',
  off: 'Off',
  listHint: 'Comma-separated',
  /** The daemon found no `claude` on PATH: null, not an empty string. */
  notFound: 'Not found',
  unknown: 'Unknown',
  none: 'None',
  /** A knob whose value some heads set for themselves in splice.toml. */
  overridden: 'Overridden by',
  /** The empties, one factual line each. */
  noConfig: 'No config yet',
  noKnobs: 'No settings here',
  noHeads: 'No plans declared',
  topologyUnavailable: 'Topology unavailable',
  claudeUnread: 'Mode not read',
} as const;

export const H = {
  about: 'Instructions, runtime settings, splice.toml, and the Claude plan mode.',
  instructions: 'Set command instructions and rules for shortening conversations.',
  noInstructions: 'Set rules for shortening conversations in splice.toml.',
  previewAdd: 'Splice adds this after Claude Code instructions.',
  previewReplace: 'Replaces Claude Code instructions with:',
  previewReplaceEffect: "Removes Claude Code's operating instructions, including its tool guidance.",
  previewStrip: 'Matching paragraphs are removed from Claude Code instructions:',
  previewUnchanged: 'Claude Code instructions unchanged.',
  previewReading: 'Reading the file without saving your changes.',
  previewRuntime: "The full result depends on the session's project and client instructions.",
  globalScope: 'Saved values apply to every plan; pick one for its own.',
  headScope: "Saved values become this plan's settings in splice.toml after a restart.",
  shadowConsole: 'A console value for every plan wins; reset it globally.',
  shadowEnv: 'The environment sets this for every plan, overriding this value.',
  overridden: 'Saving here replaces the value these plans set for themselves.',
  topologyWrite: 'Writing backs the file up first and keeps comments and layout.',
  wrapped: 'Wrapping rewrote two ~/.claude files and shims claude; unwrap restores them.',
  // Not "touches nothing": by default a separate head shares ten items with ~/.claude and moves its
  // sessions and transcripts there so other heads can resume them (TopologySchema.kt ClaudeSharingDefaults,
  // ClaudeConfigMaterializer.linkShared). What it leaves alone is the claude command (Marlin, 2026-09-25).
  separate: 'Leaves claude on PATH alone; shares ~/.claude setup and sessions by default.',
  noConfig: 'Waiting for splice to answer.',
  noKnobs: 'The other view tabs hold the rest.',
  noHeads: 'Add one below, or in splice.toml.',
  topologyUnavailable: 'This splice version does not serve splice.toml editing.',
} as const;

// THE ABSENCE VOCABULARY, written down where the next person writing a cell will see it (M1-66).
// These are DIFFERENT FACTS and collapsing them destroys information; adding a word without one of
// these meanings is how the console reached eleven phrasings for "nothing here".
//   –          (en dash, ABSENT in @shared/lib) nobody reported a value for this cell. The
//              default. It was `n/r`, which no reader could expand.
//   none       the question was asked and its answer is nothing (no tier hands this model out).
//   unknown    we asked and were NOT TOLD - a different fact from none, and never a zero.
//   unavailable  it exists and we cannot reach it.
//   ineligible   it does not apply here.
//   not built    it does not exist yet; a pending route names its row.
// A site whose fact cannot be told from the code KEEPS the word it has and gets a note beside it.
// Renaming an absence you have not understood is how `unknown` silently becomes `none`.
