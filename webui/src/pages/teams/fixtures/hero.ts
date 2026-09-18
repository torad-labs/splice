// The comp's own board, as data. Every word here is copied from the approved
// comp (.impeccable/mocks/team-board-a.png) — the operator approved that screen
// with those words, so the hero reproduces them rather than inventing its own.
//
// It is a FIXTURE: it loads only when import.meta.env.DEV is true and the
// address carries ?fixture=hero (CONTRACTS.md section 4). The shipped dist
// carries none of these bytes, which the row's note proves by grepping the
// built file.
import type { TeamActivity, TeamMemberRow, TeamMessage, TeamPayload, TeamRow } from '@entities/team';

const TEAM: TeamRow = {
  id: 'team_7f2c1b4a',
  name: 'storefront-api',
  goal: 'promoter bookkeeping lives on fly raw',
  repo: '~/Documents/dev/infra/storefront-api',
  created_epoch_millis: Date.UTC(2025, 4, 22, 9, 12, 33),
  updated_epoch_millis: Date.UTC(2025, 4, 22, 14, 2, 5),
  slots: [
    { role: 'lead', head: 'claude', model: 'fable', account: 'acct-a', lead: true, session: 'gs-backend-claude' },
    { role: 'builder', head: 'claude-deepseek', model: 'deepseek-flash', account: 'acct-a', lead: false, session: 'gs-backend-builder' },
    { role: 'builder', head: 'claude', model: 'fable', account: 'acct-b', lead: false, session: 'gs-backend-builder2' },
  ],
};

// The comp racks two sessions: the lead on head claude and one builder on head
// claude-deepseek. The team's third slot is bound (the header says 3 bound) but its
// session is not racked in this viewport, so it is not a member row here.
const MEMBERS: TeamMemberRow[] = [
  {
    name: 'gs-backend-claude',
    role: 'lead',
    head: 'claude',
    model: 'fable',
    account: 'acct-a',
    window: '5h 74%',
    lastTurn: '14:01',
    state: 'driving',
    sessionId: 's-1a7c9e2d',
    created: '09:12:33',
    uptime: '4h 49m',
    turns: 27,
    tokensIn: 182341,
    tokensOut: 71552,
    costEst: 0.412,
    contextLeftPct: 35,
    scratchpadKb: 12.8,
    workspace: '~/.../storefront-api',
    branch: 'feature/gs-41',
    base: 'main',
    diff: '+412 -37',
    checks: 'pass',
  },
  {
    name: 'gs-backend-builder',
    role: 'builder',
    head: 'claude-deepseek',
    model: 'deepseek-flash',
    account: 'acct-a',
    window: null,
    lastTurn: '13:58',
    state: 'building GS-41 done',
    sessionId: 's-5f3b8a11',
    created: '09:18:02',
    uptime: '4h 44m',
    turns: 18,
    tokensIn: 93274,
    tokensOut: 38611,
    costEst: 0.183,
    contextLeftPct: null,
    scratchpadKb: 8.1,
    workspace: '~/.../storefront-api',
    branch: 'feature/gs-41',
    base: 'main',
    diff: '+289 -37',
    checks: 'pass',
  },
];

const MESSAGES: TeamMessage[] = [
  {
    time: '13:41',
    from: 'gs-backend-claude',
    to: 'gs-backend-builder',
    packet: 'GS-41',
    text: 'packet GS-41: promoter bookkeeping lives on fly raw. build, run parity, report',
    fromHead: 'claude',
  },
  {
    time: '13:58',
    from: 'gs-backend-builder',
    to: 'gs-backend-claude',
    packet: 'GS-41',
    text: 'GS-41 done, see ledger',
    fromHead: 'claude-deepseek',
  },
  {
    time: '14:01',
    from: 'gs-backend-claude',
    to: 'gs-backend-builder2',
    packet: 'GS-42',
    text: 'packet GS-42: dearm the machine-update schedule',
    fromHead: 'claude',
  },
];

const ACTIVITY: TeamActivity[] = [
  { time: '14:01:30', member: 'gs-backend-claude', activity: 'editing PromoterLedger.kt', detail: 'src/ledger/PromoterLedger.kt:142' },
  { time: '14:01:30', member: 'gs-backend-builder', activity: 'running checks/gate.sh', detail: 'checks/gate.sh --all' },
  { time: '14:01:30', member: 'gs-backend-builder2', activity: 'searching for fly_raw', detail: 'rg -n "fly_raw"' },
  { time: '14:02:00', member: 'gs-backend-builder', activity: 'reading MANIFEST.toml', detail: 'MANIFEST.toml' },
  { time: '14:02:00', member: 'gs-backend-claude', activity: 'messaging a peer session', detail: 'to gs-backend-builder2' },
];

/** The whole first viewport, as the comp draws it. */
export const heroBoard: TeamPayload = {
  team: TEAM,
  members: MEMBERS,
  messages: MESSAGES,
  activity: ACTIVITY,
  coldCacheHint: false,
};

/** The hand-off the board draws between the bays: the newest message edge. */
export const heroHandoff: TeamMessage = MESSAGES[2];
