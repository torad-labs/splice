// Teams: the operator's own multi-session setups, as a board of printed strips.
//
// This row builds the FIRST VIEWPORT of the board-by-head view, and only that:
// the other two views the team has (board by role, timeline) are tabs that say
// which row will build them. Chat and the activity feed are panels of every
// view, so they are part of this viewport too.
//
// Against the live daemon the team routes do not exist yet (V4-131), so the page
// prints the honest empty naming that item. The comp's board is available in dev
// only, from ?fixture=hero, and never ships.
//
// The tabs render AFTER the board, not over it. The comp's first viewport holds
// no tab row, and the hero gate vetoes ink the comp does not have, so the view
// switcher sits at the end of the page's own flow: the first viewport is the
// comp, and the tabs are one scroll below it. M2-08, which builds the other two
// views, decides where they live once there is more than one view to switch to.
import { useEffect, useState } from 'react';
import { useLocation } from 'react-router';
import { PENDING_TEAMS, fetchTeam, fetchTeams, isPending, useTeam, useTeams } from '@entities/team';
import { ViewTabs, useViews, type View } from '@features/views';
import { TeamBoard, TeamBoardByRole, TeamTimeline } from '@widgets/team-board';
import type { TeamViewData } from '@widgets/team-board';
import { TeamChat } from '@widgets/team-chat';
import { ActivityFeed } from '@widgets/activity-feed';
import { TeamCompose, draftOf } from '@features/team-compose';
import { Bay, Empty, Strip, StripField } from '@shared/ui';
import type { TeamPayload, TeamRow, TeamState, TeamsState } from '@entities/team';
import { S } from './strings';
import './teams.css';

const PAGE_ID = 'teams';

/** The name this page accepts in the hash query: the fixture's own FILE name. */
const FIXTURE = 'hero';

/** Whether the address asks for THIS page's fixture, by that fixture's own FILE name. Exported
 *  because the capture marker's whole value rests on it (law 23): a name this page does not carry
 *  is not a fixture, so the page must end with no marker rather than a stale one, and a test pins
 *  that here rather than inferring it from a rendered label. */
export function wantsFixture(search: string): boolean {
  return import.meta.env.DEV && new URLSearchParams(search).get('fixture') === FIXTURE;
}


/** The team list's columns, in ch, sized to print the fixture team whole (the first capture cut
 *  `grailseeker-backend` at 16ch). A layout cannot compress a string: these are the widths the
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

/** What the page shows, as a pure function of what the daemon answered.
 *  Split out so each of the three answers — the board, the honest empty naming
 *  the missing route, and a real emptiness — is testable without a router. */
export interface TeamsBodyInput {
  fixture: TeamPayload | null;
  view: string;
  teams: TeamsState | null;
  team: TeamState | null;
  error?: string | null;
  /** The board the by-role and timeline views draw, when the address asked for the fixture. */
  views?: TeamPayload | null;
  /** The turns, economics and hour samples those views read. Null against the live daemon, where
   *  every panel that needs them prints the honest empty naming V4-131. */
  viewData?: TeamViewData | null;
}

export function teamsBodyFor({ fixture, view, teams, team, error = null, views = null, viewData = null }: TeamsBodyInput) {
  const live = team !== null && !isPending(team) ? team : null;
  // The by-role board and the timeline read the same team. Against the fixture they read the
  // payload its own comps draw (team-board-b and team-board-c); against the daemon they read the
  // live one, and their panels print the honest empty naming V4-131 for every route still to come.
  const board = views ?? fixture ?? live;
  if (view === ROLE_VIEW || view === TIMELINE_VIEW) {
    if (board === null) return liveEmpty({ teams, team, error });
    if (view === TIMELINE_VIEW) return <TeamTimeline board={board} data={viewData} />;
    return (
      <TeamBoardByRole
        board={board}
        data={viewData}
        chat={<TeamChat state={viewData === null ? { pending: PENDING_TEAMS } : { messages: board.messages }} />}
        feed={<ActivityFeed state={viewData === null ? { pending: PENDING_TEAMS } : { activity: board.activity, clientMatching: true }} />}
      />
    );
  }
  if (fixture !== null) return <TeamBoard board={fixture} />;
  if (live !== null) return <TeamBoard board={live} />;
  return liveEmpty({ teams, team, error });
}

/** What the page says when no board answered: which of the four silences this is. */
function liveEmpty({ teams, team, error }: { teams: TeamsState | null; team: TeamState | null; error: string | null }) {
  // The route itself is missing (V4-131): that is not the same answer as a
  // daemon that answers with no teams, and neither is a failure.
  if (isPending(teams) || isPending(team)) return <Empty text="no teams route" source="V4-131 pending" />;
  // Nothing has answered yet: say that, rather than claiming a failure or an
  // absence the daemon never reported.
  if (teams === null && team === null) return <Empty text={S.reading} source="GET /api/teams" />;
  if (teams !== null && !isPending(teams) && teams.teams.length === 0) {
    return <Empty text={S.noTeams} source="GET /api/teams" />;
  }
  return <Empty text={S.unreadable} source={error ?? 'the daemon did not answer'} />;
}

/** The team list bay: every team the operator owns, archived ones included and marked. Archiving
 *  is a flag and never a deletion (FEATURES 4.13), so this bay filters nothing. */
export function TeamList({ teams }: { teams: readonly (TeamRow & { archived?: boolean })[] }) {
  return (
    <Bay
      className="myx-teams-list"
      label={S.teams}
      count={teams.length}
      empty={{ text: S.noTeams, source: 'GET /api/teams' }}
    >
      {teams.map((team) => (
        <Strip key={team.id} edge={team.archived === true ? 'grey' : 'green'} edgeLabel="" ariaLabel={team.name} struck={team.archived === true}>
          <StripField w={LIST_COLS[0]} label={S.name} value={team.name} mono={false} />
          <StripField w={LIST_COLS[1]} label={S.goal} value={team.goal} mono={false} />
          <StripField w={LIST_COLS[2]} label={S.repo} value={team.repo} mono={false} />
          <StripField w={LIST_COLS[3]} label={S.slots} value={`${team.slots.length} slots, ${team.slots.filter((slot) => slot.session !== null).length} bound`} mono={false} />
          <StripField w={LIST_COLS[4]} label={S.state} value={team.archived === true ? S.archived : S.live} mono={false} />
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
  const team = useTeam((state) => state);

  useEffect(() => {
    if (fixture === null) void fetchTeams();
  }, [fixture]);

  const first = teams.data !== null && !isPending(teams.data) ? teams.data.teams[0] : undefined;
  useEffect(() => {
    if (fixture === null && first !== undefined) void fetchTeam(first.id);
  }, [fixture, first]);

  const body = teamsBodyFor({
    fixture,
    view: active.id,
    teams: teams.data,
    team: team.data,
    error: team.error ?? teams.error,
    views: sample?.views ?? null,
    viewData: sample?.data ?? null,
  });

  // The list and the composer are the page's own flow, under the board: the comp's first viewport
  // is the board and nothing else (the hero gate vetoes ink the comp does not have), so everything
  // this page adds to it lives one scroll below.
  const listed = fixture !== null
    ? [fixture.team]
    : teams.data !== null && !isPending(teams.data) ? teams.data.teams : [];
  const draft = fixture === null ? null : draftOf(fixture.team);

  return (
    <div
      className="myx-teams"
      {...(import.meta.env.DEV && sample !== null ? { 'data-sample': sample.name } : {})}
    >
      {body}
      <ViewTabs pageId={PAGE_ID} defaults={VIEWS} />
      {isPending(teams.data) && fixture === null
        ? <Empty text="no teams route" source={`${PENDING_TEAMS} pending`} />
        : <TeamList teams={listed} />}
      {/* Keyed by the team it drafts: the fixture arrives after the first render, and a form's
          state is seeded once, so without the key the composer kept the blank draft it was born
          with (measured in the first capture of this page). */}
      {draft === null ? <TeamCompose key="blank" /> : <TeamCompose key={fixture?.team.id ?? 'team'} initial={draft} />}
    </div>
  );
}

export default TeamsPage;
