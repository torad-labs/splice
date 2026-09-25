// Copy of the sessions page. The copy gate reads this file: `S` holds labels (three words or fewer,
// sentence case), `H` the one-line help a tip shows on hover and focus, `U` the fragments printed
// beside a figure.
import { ABSENT } from '@shared/lib';

export const S = {
  title: 'Sessions',
  /** The default view: one strand per head, sessions as cards, hand-offs as arcs. */
  lanes: 'Lanes',
  locked: 'Console locked',
  /** The info mark beside the title: the daemon's own note on what the list holds. */
  about: 'About this list',
  registry: 'Session registry',
  sample: 'Sample',
  detail: 'Session detail',
  close: 'Close',
  openHead: 'Open head',
  openProject: 'Open project',
  openTeam: 'Open team',
  /** The group of sessions the daemon ties to no head. */
  noHead: 'No splice head',
  /** A session's head cell when it was started with `claude` directly, not through a head. */
  direct: 'Started directly',
  conversation: 'Conversation',
  files: 'Files',
  handoffs: 'Hand-offs',
  /** The fleet's hand-offs: the two ends of each message. */
  from: 'From',
  to: 'To',
  /** The board's columns, in the order the views declare them. */
  name: 'Name',
  head: 'Head',
  project: 'Project',
  team: 'Team',
  /** One bar per row on the board's shared time axis: started, last seen, now. */
  life: 'Lifetime',
  started: 'Started',
  seen: 'Last seen',
  /** The session this one last handed off to or heard from. */
  peer: 'Last hand-off',
  address: 'Address',
  at: 'At',
  sent: 'Sent',
  received: 'Received',
  /** What any cell with no value prints. */
  absent: ABSENT,
  undated: 'Not dated',
  status: 'Status',
  live: 'Live',
  stale: 'Stale',
  gone: 'Gone',
  way: 'Direction',
  noSessions: 'No sessions',
  noHandoffs: 'No hand-offs',
  noSessionId: 'No session id',
  noCwd: 'No working directory',
  /** The tally bar's name. */
  availability: 'Availability',
} as const;

export const H = {
  noSessions: 'Start Claude Code through a splice head to see it here.',
  noHead: 'Splice did not start these, or cannot tell which head did.',
  registry: 'Sessions Claude Code registered on this machine.',
} as const;

export const U = {
  /** The timeline's span and its empty hours, after their figures. */
  hours: 'h',
  window: 'window',
  idle: 'idle',
  /** Beside a count in the headless group's tip. */
  direct: 'started directly',
  unread: 'unreadable',
} as const;
