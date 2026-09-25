// Every word the team composer prints (docs/design/DESIGN.md section 10). S holds labels: three words
// or fewer, sentence case. H holds help: one sentence of twelve words or fewer, here the fields'
// hints. U holds the unit words printed beside a value. tests/copy.test.ts holds all three.
export const S = {
  compose: 'New team',
  edit: 'Edit',
  create: 'Create team',
  save: 'Save team',
  close: 'Close',
  name: 'Name',
  goal: 'Goal',
  features: 'Features',
  repo: 'Repo',
  slots: 'Slots',
  slot: 'Slot',
  role: 'Role',
  head: 'Head',
  lead: 'Lead',
  notLead: 'Not lead',
  instructions: 'Instructions',
  session: 'Session',
  /** The session choice that binds no session: the seat stays on the team, empty. */
  open: 'Open seat',
  /** What an unset head choice prints until one is picked. */
  pickHead: 'Choose a head',
  addSlot: 'Add slot',
  removeSlot: 'Remove slot',
  archived: 'Archived',
  live: 'Active',
  problems: 'Before saving',
  saved: 'Saved',
  /** What stops a save, one line each, in the order the form shows its fields. */
  noName: 'No name',
  noRepo: 'No repo',
  noSlot: 'No slot',
  noRole: 'No role',
  noHead: 'No head',
  onTwoSlots: 'On two slots',
} as const;

export const H = {
  name: 'A short name',
  repo: 'Path to the repo',
  goal: 'What the team is here to finish',
  features: 'One feature per line',
  role: 'Lead, builder, reviewer',
  instructions: 'What this role owns and how it hands work on',
  oneLead: 'Exactly one slot must lead.',
} as const;

export const U = {
  /** Between a saved team's name and the id the daemon gave it. */
  as: 'as',
} as const;
