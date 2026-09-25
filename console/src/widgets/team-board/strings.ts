// Every word a team's views print (docs/design/DESIGN.md section 10). S holds labels: three words or
// fewer, sentence case. H holds help: one sentence of twelve words or fewer. U holds the unit words
// printed beside a figure. tests/copy.test.ts holds all three.
import { ABSENT } from '@shared/lib';

export const S = {
  /** The stat row. */
  slotsBound: 'Slots bound',
  turns: 'Turns',
  tokens: 'Tokens',
  cost: 'Cost',
  inFlight: 'In flight',
  messages: 'Messages',
  lastHour: 'Last hour',
  unpriced: 'Unpriced',
  /** The members table. */
  members: 'Members',
  membersWhy: 'About members',
  tokensKey: 'Token key',
  tokensIn: 'Tokens in',
  tokensOut: 'Tokens out',
  name: 'Name',
  role: 'Role',
  head: 'Head',
  model: 'Model',
  state: 'State',
  window: 'Window',
  lastTurn: 'Last turn',
  checks: 'Checks',
  lead: 'Lead',
  openSeat: 'Open seat',
  open: 'Open',
  unlisted: 'Not listed',
  pass: 'Pass',
  fail: 'Fail',
  /** The member detail. */
  detail: 'Member detail',
  close: 'Close',
  account: 'Account',
  sessionId: 'Session id',
  started: 'Started',
  uptime: 'Uptime',
  workspace: 'Workspace',
  contextLeft: 'Context left',
  scratchpad: 'Scratchpad',
  branch: 'Branch',
  base: 'Base',
  diff: 'Diff',
  lastMessage: 'Last message',
  instructions: 'Instructions',
  showInstructions: 'Show instructions',
  noInstructions: 'No instructions',
  noSession: 'No session bound',
  none: 'None',
  /** The day's timeline. */
  today: 'Today',
  todayWhy: 'About the timeline',
  timelineKey: 'Timeline key',
  handoffs: 'Hand-offs',
  landed: 'Landed',
  running: 'Running',
  noTurnsToday: 'No turns today',
  readingTurns: 'Reading turns',
  /** The economics. */
  costPerRole: 'Cost per role',
  turnsPerSlot: 'Turns per slot',
  economicsWhy: 'About lifetime totals',
  untaggedWhy: 'About untagged turns',
  readingCosts: 'Reading costs',
  costsUnreadable: 'Costs unreadable',
  noTurns: 'No turns yet',
  /** What any cell with no value prints (ABSENT in @shared/lib). */
  absent: ABSENT,
} as const;

export const H = {
  members: 'Every slot the team declares, with the session bound to it.',
  openSeat: 'Bind a session to this slot in the team editor.',
  today: "Each bar is one turn, over the daemon's UTC day.",
  economics: 'Lifetime totals, joined to slots on the session tag.',
  untagged: "Turns on the team's heads that carried no session tag.",
  noTurns: "Turns show here once the team's sessions run them.",
} as const;

export const U = {
  of: 'of',
  today: 'today',
  untagged: 'untagged',
  since: 'since',
  turns: 'turns',
  running: 'running',
  kb: 'KB',
} as const;
