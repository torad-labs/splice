// Teams: sessions on different heads working one goal. The page lists the operator's teams, opens
// one, and draws it on the kit: its figures, its seats by head or by role or its day as lanes,
// today's chat and activity, and what each role cost.
//
// THE TEAM IS COMPOSED, NOT FETCHED. The daemon splits a team's read on purpose (there is no
// GET /api/teams/{id}), so the opened team is its row from GET /api/teams, and board.ts joins it to
// the session registry, the day's chat and activity, the lifetime economics and the day's perf
// rows, re-read while the team is open: the panels every 10 s, the turn log every minute.
//
// A sample team is available in dev only, from ?fixture=hero, and never ships.
import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { useLocation } from 'react-router';
import { fetchTeamPanels, fetchTeams, isPending, useTeamPanels, useTeams } from '@entities/team';
import { fetchSessions, sessionLabel, useSessionRegistry } from '@entities/session';
import { fetchHeads, useHeads } from '@entities/heads';
import { fetchPerfTurns, inflightFrom, usePerfTurns } from '@entities/perf';
import { ViewTabs, useViews, type View } from '@features/views';
import { CostPerRole, TeamLanes, TeamMembers, TeamStats, TeamTimeline } from '@widgets/team-board';
import type { TeamViewData } from '@widgets/team-board';
import { TeamChat } from '@widgets/team-chat';
import type { TeamChatState } from '@widgets/team-chat';
import { ActivityFeed } from '@widgets/activity-feed';
import type { ActivityFeedState } from '@widgets/activity-feed';
import { TeamCompose, draftOf } from '@features/team-compose';
import { Fault, Key } from '@shared/controls';
import { ABSENT, poll } from '@shared/lib';
import { Badge, DataTable, Empty, KeyValue, PageHeader, Pips, Section } from '@shared/ui';
import type { Column } from '@shared/ui';
import type { TeamPanels, TeamPayload, TeamRow, TeamsState } from '@entities/team';
import { boardOf, dayStartOf, viewDataOf } from './board';
import { H, S, U } from './strings';
import './teams.css';

const PAGE_ID = 'teams';

/** The name this page accepts in the hash query: the fixture's own FILE name. */
const FIXTURE = 'hero';

/** How often the opened team is re-read: the activity sampler's own pitch (30 s) would leave the
 *  page a sample behind, so a third of it. */
const READ_EVERY_MS = 10_000;

/** The perf rows read for the day's turn log: the route's own ceiling per head (PerfRoutes.kt
 *  MAX_TURNS), so the log is short only on a day busier than the route will serve. */
const TURN_TAIL = 2_000;

/** How often that log is re-read. A day of rows per head is the heaviest read on the page, and the
 *  last-hour chart it feeds has a minute's resolution, so a minute is the finest it can show. */
const TURN_LOG_EVERY_MS = 60_000;

/** Whether the address asks for THIS page's fixture, by that fixture's own FILE name. Exported
 *  because the capture marker's whole value rests on it (law 23): a name this page does not carry
 *  is not a fixture, so the page must end with no marker rather than a stale one, and a test pins
 *  that here rather than inferring it from a rendered label. */
export function wantsFixture(search: string): boolean {
  return import.meta.env.DEV && new URLSearchParams(search).get('fixture') === FIXTURE;
}

/** The views of one team: its seats as lanes (the default, added after the other three, so a browser
 *  that saved its views before is offered it once), by head, by role, and its day on a clock. */
const VIEWS: View[] = [
  { id: 'lanes', name: S.lanes, layout: 'lanes', filter: {}, sort: null, group: 'head', fields: [], introduced: '2026-09-25' },
  { id: 'by-head', name: S.byHead, layout: 'table', filter: {}, sort: null, group: 'head', fields: [] },
  { id: 'by-role', name: S.byRole, layout: 'table', filter: {}, sort: null, group: 'role', fields: [] },
  { id: 'timeline', name: S.timeline, layout: 'timeline', filter: {}, sort: null, group: null, fields: [] },
];

/** What a view draws, read from its layout and group rather than its id, so a view the operator
 *  renamed or copied draws what it was copied from. */
export function modeOf(view: Pick<View, 'layout' | 'group'>): 'lanes' | 'head' | 'role' | 'timeline' {
  if (view.layout === 'lanes') return 'lanes';
  if (view.layout === 'timeline') return 'timeline';
  return view.group === 'role' ? 'role' : 'head';
}

/** What the page shows under its list, as a pure function of what the daemon answered. Split out
 *  so each answer (a team, the honest empty naming a missing route, a real emptiness, a failure) is
 *  testable without a router. */
export interface TeamsBodyInput {
  view: Pick<View, 'layout' | 'group'>;
  teams: TeamsState | null;
  error?: string | null;
  /** When the list behind a drawn team was read, which the fault prints as stale while `error` stands. */
  lastRead?: number | null;
  /** The opened team: composed from the daemon's reads, or the dev fixture's. */
  board: TeamPayload | null;
  /** What the views read beyond the board; null while it is being read. */
  data?: TeamViewData | null;
  chat?: TeamChatState;
  feed?: ActivityFeedState;
  /** The editor's key on the opened team, and the empty's action when there is no team. */
  onEdit?: () => void;
  onNew?: () => void;
}

export function teamsBodyFor({ view, teams, error = null, lastRead = null, board, data = null, chat = null, feed = null, onEdit, onNew }: TeamsBodyInput) {
  if (board === null) return liveEmpty(teams, error, onNew);
  // A list read that fails after a team was drawn keeps the team and says so above it, with the
  // age of what it shows, so a dead daemon's team does not read as a live one.
  return (
    <>
      {error === null ? null : <Fault message={error} lastRead={lastRead} />}
      <TeamView board={board} mode={modeOf(view)} data={data} chat={chat} feed={feed} {...(onEdit === undefined ? {} : { onEdit })} />
    </>
  );
}

/** What the page says when no team answered: which of the four silences this is. */
function liveEmpty(teams: TeamsState | null, error: string | null, onNew?: () => void) {
  // The route itself is missing (a daemon older than V4-131): that is not the same answer as a
  // daemon that answers with no teams, and neither is a failure.
  if (isPending(teams)) return <Empty text={S.unavailable} source={H.unavailable} />;
  if (teams === null) return error === null ? <Empty text={S.reading} source={H.reading} /> : <Empty text={S.unreadable} source={error} />;
  if (teams.teams.length === 0) {
    return <Empty text={S.noTeams} source={H.noTeams} {...(onNew === undefined ? {} : { action: <Key onClick={onNew}>{S.newTeam}</Key> })} />;
  }
  return <Empty text={S.unreadable} source={error ?? undefined} />;
}

/** The chat and feed states of the opened team's panels, as the two widgets take them. */
export function panelStates(board: TeamPayload, panels: TeamPanels | null): { chat: TeamChatState; feed: ActivityFeedState } {
  if (panels === null || panels.teamId !== board.team.id) return { chat: null, feed: null };
  return {
    chat: 'error' in panels.chat ? { error: panels.chat.error } : { messages: board.messages },
    feed: 'error' in panels.activity ? { error: panels.activity.error } : { activity: board.activity, clientMatching: true },
  };
}

/** YYYY-MM-DD in UTC, the daemon's day. */
const utcDay = (epochMs: number): string => new Date(epochMs).toISOString().slice(0, 10);

const boundOf = (team: TeamRow): number => team.slots.filter((slot) => slot.session !== null).length;

/** One team, opened: who it is, its figures, then the view's seats or lanes, today's chat and
 *  activity, and the cost per role. */
export function TeamView({ board, mode, data, chat, feed, onEdit }: {
  board: TeamPayload;
  mode: 'lanes' | 'head' | 'role' | 'timeline';
  data: TeamViewData | null;
  chat: TeamChatState;
  feed: ActivityFeedState;
  onEdit?: () => void;
}) {
  const { team } = board;
  const facts: (readonly [string, ReactNode])[] = [
    [S.goal, team.goal === '' ? ABSENT : team.goal],
    [S.repo, <code className="myx-tm-code">{team.repo}</code>],
    ...(team.features.length === 0 ? [] : [[S.features, team.features.join(', ')] as const]),
    [S.created, utcDay(team.created_epoch_millis)],
    [S.updated, utcDay(team.updated_epoch_millis)],
  ];
  return (
    <div className="myx-tm-team">
      <Section
        title={team.name}
        meta={S.team}
        actions={(
          <>
            <Badge tone={team.archived ? 'neutral' : 'ok'} quiet>{team.archived ? S.archived : S.active}</Badge>
            {onEdit === undefined ? null : <Key onClick={onEdit}>{S.edit}</Key>}
          </>
        )}
      >
        <div className="myx-tm-head">
          <KeyValue rows={facts} />
          <TeamStats board={board} data={data} />
        </div>
      </Section>
      {mode === 'lanes' ? <TeamLanes board={board} /> : null}
      {mode === 'timeline' ? <TeamTimeline board={board} data={data} /> : null}
      {mode === 'head' || mode === 'role' ? <TeamMembers board={board} by={mode} /> : null}
      <div className="myx-tm-pair">
        <TeamChat state={chat} />
        <ActivityFeed state={feed} />
      </div>
      <CostPerRole data={data} />
    </div>
  );
}

/** Every team the operator owns, archived ones included and marked: archiving is a flag and never a
 *  deletion (FEATURES 4.13), so the list filters nothing. */
export function TeamList({ teams, opened = null, onOpen }: {
  teams: readonly TeamRow[];
  opened?: string | null;
  onOpen?: (id: string) => void;
}) {
  const columns: Column<TeamRow>[] = [
    { key: 'name', label: S.name, primary: true, width: '20%', cell: (team) => team.name },
    { key: 'goal', label: S.goal, cell: (team) => team.goal },
    { key: 'repo', label: S.repo, width: '26%', mono: true, cell: (team) => team.repo },
    {
      key: 'slots',
      label: S.slots,
      width: '14%',
      cell: (team) => (
        <span className="myx-tm-slots">
          <Pips used={boundOf(team)} total={team.slots.length} label={S.slots} />
          <span className="myx-tm-figure">{`${boundOf(team)} ${U.of} ${team.slots.length}`}</span>
        </span>
      ),
    },
    {
      key: 'state',
      label: S.state,
      width: '10%',
      cell: (team) => <Badge tone={team.archived ? 'neutral' : 'ok'} quiet>{team.archived ? S.archived : S.active}</Badge>,
    },
  ];
  return (
    <Section title={S.teams} count={teams.length}>
      <DataTable
        columns={columns}
        rows={teams}
        rowKey={(team) => team.id}
        label={S.teams}
        {...(onOpen === undefined ? {} : { onOpen: (team: TeamRow) => onOpen(team.id) })}
        openLabel={(team) => `${S.openTeam} ${team.name}`}
        selectedKey={opened}
      />
    </Section>
  );
}

export function TeamsPage() {
  const { search } = useLocation();
  const { active } = useViews(PAGE_ID, VIEWS);
  const [sample, setSample] = useState<{ name: string; payload: TeamPayload; data: TeamViewData | null } | null>(null);
  const fixture = sample === null ? null : sample.payload;
  const [opened, setOpened] = useState<string | null>(null);
  /** The editor: closed, composing a new team, or editing the opened one. */
  const [composing, setComposing] = useState<'new' | 'edit' | null>(null);
  /** The team the last save answered, and the line printed for it: the list re-read that carries
   *  a new team lands after the save does, and until it does the page opens this row, not another. */
  const [saved, setSaved] = useState<{ team: TeamRow; answer: string } | null>(null);
  const [now, setNow] = useState(() => Date.now());

  // A fixture loads only in dev and only when the address asks for it by name
  // (CONTRACTS.md section 4). It is reached by a dynamic import inside the
  // guard and never by a static one: a static import puts the sample's words in
  // the shipped bundle whatever the guard says, because the module is then
  // reachable whether or not the branch is (measured: the words survived the
  // production build until this became an import the dead branch could drop).
  useEffect(() => {
    if (!wantsFixture(search)) {
    // The address no longer asks for this page's fixture, so the marker must GO: a name that is
    // asked for and then dropped is exactly the stale marker this row exists to prevent (measured
    // in a browser on 2026-09-18 - five pages kept one across a hash change, because the early
    // return left the previous state in place; a static render cannot see an effect, so the suite
    // was green while it happened).
      setSample(null);
      return;
    }
    // The specifier is BUILT AT RUNTIME, not written as a literal: a statically analyzable
    // `import('./fixtures/x')` stays a dependency edge through the single-file build even when the
    // branch around it is dead, so the module's bytes are inlined into dist/index.html (measured
    // 2026-09-18: this page shipped its own literals that way; the pages that compose the specifier
    // at runtime shipped none). CONTRACTS.md section 4 asks for the dynamic import; this is the half
    // of it the bundler can actually drop.
    void import(/* @vite-ignore */ `./fixtures/${FIXTURE}.ts`)
      .then((module: { sampleBoard?: TeamPayload; sampleData?: TeamViewData }) => {
        setSample(module.sampleBoard === undefined ? null : { name: FIXTURE, payload: module.sampleBoard, data: module.sampleData ?? null });
      })
      .catch(() => undefined);
  }, [search]);

  const teams = useTeams((state) => state);
  const panels = useTeamPanels((state) => state);
  const registry = useSessionRegistry((state) => state);
  const heads = useHeads((state) => state.data);

  // The composer picks a slot's head and session from what the daemon lists, so both are read once
  // here; the live stream re-reads each when it changes (widgets/rule/wire.ts).
  useEffect(() => {
    if (fixture !== null) return;
    void fetchHeads();
    void fetchSessions();
  }, [fixture]);
  const turns = usePerfTurns((state) => state);

  // The list is re-read on the panels' cadence, with or without a team open: a team or a binding
  // made elsewhere (the CLI, another console) reaches an open page rather than waiting for a reload.
  useEffect(() => (fixture === null ? poll(fetchTeams, READ_EVERY_MS) : undefined), [fixture]);

  // The opened team: the one the operator picked, else the first live one, else the first.
  const list = teams.data !== null && !isPending(teams.data) ? teams.data.teams : [];
  const open = list.find((team) => team.id === opened)
    ?? (saved !== null && saved.team.id === opened ? saved.team : undefined)
    ?? list.find((team) => !team.archived) ?? list[0] ?? null;
  const openId = open?.id ?? null;

  useEffect(() => {
    if (fixture !== null || openId === null) return undefined;
    return poll(async () => {
      setNow(Date.now());
      await Promise.all([fetchTeamPanels(openId), fetchSessions()]);
    }, READ_EVERY_MS);
  }, [fixture, openId]);

  useEffect(() => {
    if (fixture !== null || openId === null) return undefined;
    return poll(() => fetchPerfTurns(undefined, TURN_TAIL, dayStartOf(Date.now())), TURN_LOG_EVERY_MS);
  }, [fixture, openId]);

  const sessions = registry.data?.sessions ?? [];
  const rows = turns.data !== null && !isPending(turns.data) ? turns.data.landed : [];
  const live = open === null ? null : boardOf(open, sessions, panels.data, now);
  const board = fixture ?? live;
  const states = fixture !== null
    ? { chat: { messages: fixture.messages }, feed: { activity: fixture.activity, clientMatching: true } }
    : live === null ? { chat: null, feed: null } : panelStates(live, panels.data);

  const listed = fixture !== null ? [fixture.team] : list;
  const editing = composing === 'edit' ? (fixture?.team ?? open) : null;

  const body = teamsBodyFor({
    view: active,
    teams: teams.data,
    error: fixture === null ? teams.error : null,
    lastRead: teams.lastUpdated,
    board,
    data: sample !== null ? sample.data : live === null ? null : viewDataOf(live, rows, heads === null ? null : inflightFrom(heads), panels.data, now),
    ...states,
    onEdit: () => setComposing('edit'),
    onNew: () => setComposing('new'),
  });

  return (
    <div
      className="myx-tm"
      {...(import.meta.env.DEV && sample !== null ? { 'data-sample': sample.name } : {})}
    >
      <PageHeader
        title={S.title}
        info={{ text: H.about, label: S.about }}
        actions={(
          <>
            {sample === null ? null : <Badge tone="neutral">{S.sample}</Badge>}
            {/* With no team the empty carries this key, so the page never prints it twice. */}
            {listed.length === 0 ? null : <Key onClick={() => setComposing('new')}>{S.newTeam}</Key>}
          </>
        )}
      >
        <ViewTabs pageId={PAGE_ID} defaults={VIEWS} />
      </PageHeader>

      {/* Keyed by the team it drafts: a form's state is seeded once, so without the key the
          composer kept the draft it was born with when another team arrived (measured in the
          first capture of this page). */}
      {composing === null ? null : fixture !== null ? (
        <TeamCompose key={composing} team={editing} {...(editing === null ? {} : { initial: draftOf(editing) })} onClose={() => setComposing(null)} />
      ) : (
        <TeamCompose
          key={editing?.id ?? 'new'}
          team={editing}
          heads={(heads ?? []).map((head) => ({ value: head.key, label: head.label }))}
          sessions={sessions
            .filter((row) => row.session_id !== null && row.availability !== 'gone')
            .map((row) => ({ value: row.session_id ?? '', label: sessionLabel(row) }))}
          answer={saved !== null && saved.team.id === editing?.id ? saved.answer : null}
          onClose={() => setComposing(null)}
          onSaved={(team, answer) => {
            setSaved({ team, answer });
            setOpened(team.id);
            setComposing('edit');
            void fetchTeams();
          }}
        />
      )}

      {/* The list is drawn when there is a choice to make: with one team it would repeat the
          opened team's own head. */}
      {listed.length < 2 ? null : (
        <TeamList teams={listed} opened={board?.team.id ?? null} onOpen={(id) => { setOpened(id); setComposing(null); }} />
      )}
      {body}
    </div>
  );
}

export default TeamsPage;
