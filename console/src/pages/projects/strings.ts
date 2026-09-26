// Copy of the projects page. The copy gate reads this file: `S` holds labels (three words or fewer,
// sentence case), `H` the one-line help a tip shows on hover and focus, `U` the fragments printed
// beside a figure.
import { ABSENT } from '@shared/lib';

export const S = {
  title: 'Projects',
  about: 'About projects',
  sample: 'Sample',
  detail: 'Project detail',
  close: 'Close',
  /** The one saved view the page ships. */
  allRepos: 'All repos',
  /** The table's columns, in the order the view declares them; the state closes the row. */
  repo: 'Repo',
  sessions: 'Sessions',
  teams: 'Teams',
  turns: 'Turns today',
  cost: 'API cost today',
  last: 'Last seen',
  state: 'State',
  /** Something is running in the repo now, or nothing is. */
  busy: 'Busy',
  quiet: 'Quiet',
  /** The summary over the table. */
  repos: 'Repos',
  running: 'Sessions running',
  /** The opened project's own row (GET /api/projects/{id}). */
  activity: 'Activity',
  day: 'Day',
  openSessions: 'Open sessions',
  /** What governs the repo (FEATURES.md 4.14): its compaction rules and each head's statusline root. */
  compaction: 'Compaction rules',
  statusline: 'Statusline roots',
  head: 'Head',
  root: 'Root',
  /** Which entry of a head's trusted set covers the repo, as a badge; none, and its statusline
   *  shows no branch here. */
  trust: 'Trusted by',
  home: 'Home',
  tmp: 'Temp',
  gitRoots: 'Git roots',
  untrusted: 'No branch',
  files: 'Files',
  /** V4-313: the repo's standing prompt and its own compaction rule, edited here. */
  standing: 'Prompt and rule',
  prompt: 'Standing prompt',
  rule: 'Compaction rule',
  fromFile: 'From file',
  save: 'Save',
  confirmSave: 'Write splice.toml',
  saved: 'Saved',
  reaches: 'Reaches',
  noLive: 'No live sessions',
  unavailable: 'No topology route',
  noProjects: 'No projects yet',
  noRule: 'No rule here',
  noHeads: 'No heads yet',
  settings: 'Settings',
  /** What any cell with no value prints. */
  absent: ABSENT,
} as const;

export const H = {
  about: 'Every repo the daemon has seen a session run in.',
  noProjects: 'A repo shows here once a session runs in it.',
  /** Declared rates make a dollar figure an estimate; no rates is no figure, never zero. */
  cost: 'Estimated from declared rates; turns with none are counted, not priced.',
  /** The row's `compaction` is null: the daemon's failure to report, not "no rule". */
  unwired: 'The daemon did not report its compaction table.',
  clientOwn: "Claude Code's own instructions apply; rules go under [compaction] in splice.toml.",
  compaction: "The rules a compaction here resolves to, in the daemon's precedence.",
  statusline: "The trusted root each head's statusline finds this repo under.",
  noHeads: 'Add one in Settings, under Topology.',
  standing: "This repo's prompt and compaction rule, as splice.toml holds them.",
  /** Both are read at boot (Daemon.kt), so no running session sees an edit before a restart. */
  restart: 'Applies after the daemon restarts, at each session\'s next turn.',
  reaches: 'Live sessions here get it at their first turn after a restart.',
  fromFile: 'Read from this file; edit the file, or clear it in Settings.',
  unavailable: 'This daemon serves no topology to edit.',
} as const;

export const U = {
  /** After the count of turns a cost figure leaves out: their model had no rate card. */
  unpriced: 'turns unpriced',
  unpricedOne: 'turn unpriced',
  of: 'of',
} as const;
