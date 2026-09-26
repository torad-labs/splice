// Every word the teams page prints (docs/design/DESIGN.md section 10). S holds labels: three words or
// fewer, sentence case. H holds help: one sentence of twelve words or fewer. U holds the unit words
// printed beside a figure. tests/copy.test.ts holds all three.
export const S = {
  title: 'Teams',
  about: 'About teams',
  sample: 'Sample data',
  /** The views. */
  lanes: 'Lanes',
  byHead: 'By head',
  byRole: 'By role',
  timeline: 'Timeline',
  /** The team list and the opened team. */
  teams: 'Teams',
  team: 'Team',
  openTeam: 'Open team',
  newTeam: 'New team',
  edit: 'Edit team',
  name: 'Name',
  goal: 'Goal',
  repo: 'Repo',
  features: 'Features',
  slots: 'Slots',
  state: 'State',
  created: 'Created',
  updated: 'Updated',
  active: 'Active',
  archived: 'Archived',
  /** The empties, one factual line each. */
  reading: 'Reading teams',
  unavailable: 'Teams unavailable',
  unreadable: 'Teams unreadable',
  noTeams: 'No teams yet',
  /** The prefix of a failed turn log read on the timeline. */
  turnLog: 'Turn log',
} as const;

export const H = {
  about: 'Sessions on different heads working one goal, and their hand-offs.',
  unavailable: 'This splice version does not serve teams.',
  reading: 'Waiting for the daemon to answer.',
  noTeams: 'Name a team, point it at a repo, and seat its roles.',
  turnsPending: 'This splice version does not serve the turn log.',
} as const;

export const U = {
  of: 'of',
  /** After "2,000 of 2,345": a head the route clamped, its earliest turns missing. */
  turnsRead: 'turns read',
} as const;
