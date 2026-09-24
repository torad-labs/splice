// Teams: the operator's own multi-session setups, as a board of printed strips.
//
// THE BOARD IS COMPOSED, NOT FETCHED. The daemon splits a team's read on purpose (there is no
// GET /api/teams/{id}), so the opened team is its row from GET /api/teams, and board.ts joins it to
// the session registry, the day's chat and activity, the lifetime economics and the day's perf
// rows, re-read while the team is open: the panels every 10 s, the turn log every minute.
//
// The comp's board is available in dev only, from ?fixture=hero, and never ships.
//
// The tabs render AFTER the board, not over it. The comp's first viewport holds no tab row, and
// the hero gate vetoes ink the comp does not have, so the view switcher sits at the end of the
// page's own flow: the first viewport is the comp, and the tabs are one scroll below it.
import { useEffect, useState } from 'react';
import { useLocation } from 'react-router';
import { fetchTeamPanels, fetchTeams, isPending, useTeamPanels, useTeams } from '@entities/team';
import { fetchSessions, sessionLabel, useSessionRegistry } from '@entities/session';
import { fetchHeads, useHeads } from '@entities/heads';
import { fetchPerfTurns, usePerfTurns } from '@entities/perf';
import { ViewTabs, useViews, type View } from '@features/views';
import { TeamBoard, TeamBoardByRole, TeamTimeline } from '@widgets/team-board';
import type { TeamViewData } from '@widgets/team-board';
import { TeamChat } from '@widgets/team-chat';
import type { TeamChatState } from '@widgets/team-chat';
import { ActivityFeed } from '@widgets/activity-feed';
import type { ActivityFeedState } from '@widgets/activity-feed';
import { TeamCompose, draftOf } from '@features/team-compose';
import { Key } from '@shared/controls';
import { poll } from '@shared/lib';
import { Bay, Empty, Strip, StripField } from '@shared/ui';
import type { TeamPanels, TeamPayload, TeamRow, TeamsState } from '@entities/team';
import { boardOf, dayStartOf, viewDataOf } from './board';
import { S } from './strings';
import './teams.css';

const PAGE_ID = 'teams';

/** The name this page accepts in the hash query: the fixture's own FILE name. */
const FIXTURE = 'hero';

/** How often the opened team is re-read: the activity sampler's own pitch (30 s) would leave the
 *  board a sample behind, so a third of it. */
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


/** The team list's columns, in ch, sized to print the fixture team whole (the first capture cut
 *  `storefront-api` at 16ch). A layout cannot compress a string: these are the widths the
 *  values need, and the bay scrolls if a longer one arrives. */
const LIST_COLS = [22, 40, 44, 16, 10];

/** The three views of one team. */
const VIEWS: View[] = [
  { id: 'by-head', name: 'board by head', layout: 'board', filter: {}, sort: null, group: 'head', fields: [] },
  { id: 'by-role', name: 'board by role', layout: 'board', filter: {}, sort: null, group: 'role', fields: [] },
  { id: 'timeline', name: 'timeline', layout: 'timeline', filter: {}, sort: null, group: null, fields: [] },
];

const ROLE_VIEW = 'by-role';
const TIMELINE_VIEW = 'timeline';

/** What the page shows, as a pure function of what the daemon answered. Split out so each answer
 *  (a board, the honest empty naming a missing route, a real emptiness, a failure) is testable
 *  without a router. */
export interface TeamsBodyInput {
  view: string;
  teams: TeamsState | null;
  error?: string | null;
  /** The opened team's board: composed from the daemon's reads, or the dev fixture's. */
  board: TeamPayload | null;
  /** The board the by-role and timeline views draw when it is not `board` (the fixture's). */
  views?: TeamPayload | null;
  /** What those views read beyond the board; null while it is being read. */
  viewData?: TeamViewData | null;
  chat?: TeamChatState;
  feed?: ActivityFeedState;
  /** Why a panel of the opened team could not be read, for the head board's own chat and feed. */
  unread?: { chat?: string; activity?: string };
}

export function teamsBodyFor({ view, teams, error = null, board, views = null, viewData = null, chat = null, feed = null, unread = {} }: TeamsBodyInput) {
  if (view === ROLE_VIEW || view === TIMELINE_VIEW) {
    const drawn = views ?? board;
    if (drawn === null) return liveEmpty(teams, error);
    if (view === TIMELINE_VIEW) return <TeamTimeline board={drawn} data={viewData} />;
    return <TeamBoardByRole board={drawn} data={viewData} chat={<TeamChat state={chat} />} feed={<ActivityFeed state={feed} />} />;
  }
  if (board !== null) return <TeamBoard board={board} unread={unread} />;
  return liveEmpty(teams, error);
}

/** Where a first team comes from: the composer under the board. */
const COMPOSE_BELOW = 'compose one below: name it, point it at a repo, and give each role a head';

/** What the page says when no board answered: which of the four silences this is. */
function liveEmpty(teams: TeamsState | null, error: string | null) {
  // The route itself is missing (a daemon older than V4-131): that is not the same answer as a
  // daemon that answers with no teams, and neither is a failure.
  if (isPending(teams)) return <Empty text="teams unavailable" source="this splice version does not serve teams" />;
  if (teams === null) return <Empty text={error === null ? S.reading : S.unreadable} source={error ?? 'the daemon has not answered yet'} />;
  if (teams.teams.length === 0) return <Empty text={S.noTeams} source={COMPOSE_BELOW} />;
  return <Empty text={S.unreadable} source={error ?? 'the daemon did not answer'} />;
}

/** The chat and feed states of the opened team's panels, as the two widgets take them. */
export function panelStates(board: TeamPayload, panels: TeamPanels | null): { chat: TeamChatState; feed: ActivityFeedState; unread: { chat?: string; activity?: string } } {
  if (panels === null || panels.teamId !== board.team.id) return { chat: null, feed: null, unread: {} };
  return {
    chat: 'error' in panels.chat ? { error: panels.chat.error } : { messages: board.messages },
    feed: 'error' in panels.activity ? { error: panels.activity.error } : { activity: board.activity, clientMatching: true },
    unread: {
      ...('error' in panels.chat ? { chat: panels.chat.error } : {}),
      ...('error' in panels.activity ? { activity: panels.activity.error } : {}),
    },
  };
}

/** The team list bay: every team the operator owns, archived ones included and marked. Archiving
 *  is a flag and never a deletion (FEATURES 4.13), so this bay filters nothing. */
export function TeamList({ teams, opened = null, onOpen }: {
  teams: readonly TeamRow[];
  opened?: string | null;
  onOpen?: (id: string) => void;
}) {
  return (
    <Bay
      className="myx-teams-list"
      label={S.teams}
      count={teams.length}
      empty={{ text: S.noTeams, source: COMPOSE_BELOW }}
    >
      {teams.map((team) => (
        <Strip
          key={team.id}
          edge={team.archived ? 'grey' : 'green'}
          edgeLabel=""
          ariaLabel={team.name}
          selected={team.id === opened}
          {...(onOpen === undefined ? {} : { onOpen: () => onOpen(team.id) })}
        >
          <StripField w={LIST_COLS[0]} label={S.name} value={team.name} mono={false} />
          <StripField w={LIST_COLS[1]} label={S.goal} value={team.goal} mono={false} />
          <StripField w={LIST_COLS[2]} label={S.repo} value={team.repo} mono={false} />
          <StripField w={LIST_COLS[3]} label={S.slots} value={`${team.slots.length} slots, ${team.slots.filter((slot) => slot.session !== null).length} bound`} mono={false} />
          <StripField w={LIST_COLS[4]} label={S.state} value={team.archived ? S.archived : S.live} mono={false} />
        </Strip>
      ))}
    </Bay>
  );
}

export function TeamsPage() {
  const { search } = useLocation();
  const { active } = useViews(PAGE_ID, VIEWS);
  const [sample, setSample] = useState<{ name: string; payload: TeamPayload; views: TeamPayload | null; data: TeamViewData | null } | null>(null);
  const fixture = sample === null ? null : sample.payload;
  const [opened, setOpened] = useState<string | null>(null);
  const [composing, setComposing] = useState(false);
  /** The team the last save answered, and the line printed for it: the list re-read that carries
   *  a new team lands after the save does, and until it does the page opens this row, not another. */
  const [saved, setSaved] = useState<{ team: TeamRow; answer: string } | null>(null);
  const [now, setNow] = useState(() => Date.now());

  // A fixture loads only in dev and only when the address asks for it by name
  // (CONTRACTS.md section 4). It is reached by a dynamic import inside the
  // guard and never by a static one: a static import puts the comp's words in
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
      .then((module: { heroBoard?: TeamPayload; viewsBoard?: TeamPayload; viewsData?: TeamViewData }) => {
        setSample(module.heroBoard === undefined ? null : {
          name: FIXTURE,
          payload: module.heroBoard,
          views: module.viewsBoard ?? null,
          data: module.viewsData ?? null,
        });
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
  const views = sample?.views ?? null;
  const states = views !== null
    ? { chat: { messages: views.messages }, feed: { activity: views.activity, clientMatching: true }, unread: {} }
    : live === null ? { chat: null, feed: null, unread: {} } : panelStates(live, panels.data);

  const body = teamsBodyFor({
    view: active.id,
    teams: teams.data,
    error: teams.error,
    board,
    views,
    viewData: sample !== null ? sample.data : live === null ? null : viewDataOf(live, rows, panels.data, now),
    ...states,
  });

  // The list and the composer are the page's own flow, under the board: the comp's first viewport
  // is the board and nothing else (the hero gate vetoes ink the comp does not have), so everything
  // this page adds to it lives one scroll below.
  const listed = fixture !== null ? [fixture.team] : list;
  const editing = fixture === null && !composing ? open : null;

  return (
    <div
      className="myx-teams"
      {...(import.meta.env.DEV && sample !== null ? { 'data-sample': sample.name } : {})}
    >
      {/* With no board to draw there is no comp to be faithful to, and the page printed a bare empty
          with no title over tabs stranded at the end of the flow. It then heads itself like every
          other page: title and tabs first, the empty under them. */}
      {board === null ? (
        <header className="myx-page-head">
          <h1 className="myx-page-title">{S.teams}</h1>
          <ViewTabs pageId={PAGE_ID} defaults={VIEWS} />
        </header>
      ) : null}
      {body}
      {board === null ? null : <ViewTabs pageId={PAGE_ID} defaults={VIEWS} />}
      {/* The list is drawn when there is a team to list: with none, the board above already says
          "no teams yet" in the same words, and a second empty rack saying it again under the view
          tabs read as the page repeating itself. A pending route is said once, by the board. */}
      {isPending(teams.data) && fixture === null ? null : listed.length === 0 ? null : (
        <TeamList teams={listed} opened={board?.team.id ?? null} onOpen={(id) => { setOpened(id); setComposing(false); }} />
      )}
      {fixture === null && open !== null ? (
        <div className="myx-teams-compose-switch">
          <Key onClick={() => setComposing(!composing)}>{composing ? `${S.edit} ${open.name}` : S.newTeam}</Key>
        </div>
      ) : null}
      {/* Keyed by the team it drafts: a form's state is seeded once, so without the key the
          composer kept the draft it was born with when the fixture or another team arrived
          (measured in the first capture of this page). */}
      {fixture !== null
        ? <TeamCompose key={fixture.team.id} initial={draftOf(fixture.team)} />
        : (
          <TeamCompose
            key={editing?.id ?? 'new'}
            team={editing}
            heads={(heads ?? []).map((head) => ({ value: head.key, label: head.label }))}
            sessions={sessions
              .filter((row) => row.session_id !== null && row.availability !== 'gone')
              .map((row) => ({ value: row.session_id ?? '', label: sessionLabel(row) }))}
            answer={saved !== null && saved.team.id === editing?.id ? saved.answer : null}
            onSaved={(team, answer) => {
              setSaved({ team, answer });
              setOpened(team.id);
              setComposing(false);
              void fetchTeams();
            }}
          />
        )}
    </div>
  );
}

export default TeamsPage;
