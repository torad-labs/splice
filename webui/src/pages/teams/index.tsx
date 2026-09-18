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
import { fetchTeam, fetchTeams, isPending, useTeam, useTeams } from '@entities/team';
import { ViewTabs, useViews, type View } from '@features/views';
import { TeamBoard } from '@widgets/team-board';
import { Empty } from '@shared/ui';
import type { TeamPayload, TeamState, TeamsState } from '@entities/team';
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


/** The three views of one team. Only the first is built in this row. */
const VIEWS: View[] = [
  { id: 'by-head', name: 'board by head', layout: 'board', filter: {}, sort: null, group: 'head', fields: [] },
  { id: 'by-role', name: 'board by role', layout: 'board', filter: {}, sort: null, group: 'role', fields: [] },
  { id: 'timeline', name: 'timeline', layout: 'timeline', filter: {}, sort: null, group: null, fields: [] },
];

const BOARD_VIEW = 'by-head';

/** What the page shows, as a pure function of what the daemon answered.
 *  Split out so each of the three answers — the board, the honest empty naming
 *  the missing route, and a real emptiness — is testable without a router. */
export interface TeamsBodyInput {
  fixture: TeamPayload | null;
  view: string;
  teams: TeamsState | null;
  team: TeamState | null;
  error?: string | null;
}

export function teamsBodyFor({ fixture, view, teams, team, error = null }: TeamsBodyInput) {
  if (view !== BOARD_VIEW) return <Empty text="view not built" source="row M2-08" />;
  if (fixture !== null) return <TeamBoard board={fixture} />;

  const live = team !== null && !isPending(team) ? team : null;
  if (live !== null) return <TeamBoard board={live} />;

  // The route itself is missing (V4-131): that is not the same answer as a
  // daemon that answers with no teams, and neither is a failure.
  if (isPending(teams) || isPending(team)) return <Empty text="no teams route" source="V4-131 pending" />;
  // Nothing has answered yet: say that, rather than claiming a failure or an
  // absence the daemon never reported.
  if (teams === null && team === null) return <Empty text={S.reading} source="GET /api/teams" />;
  if (teams !== null && teams.teams.length === 0) {
    return <Empty text={S.noTeams} source="GET /api/teams" />;
  }
  return <Empty text={S.unreadable} source={error ?? 'the daemon did not answer'} />;
}

export function TeamsPage() {
  const { search } = useLocation();
  const { active } = useViews(PAGE_ID, VIEWS);
  const [sample, setSample] = useState<{ name: string; payload: TeamPayload } | null>(null);
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
      .then((module: { heroBoard?: TeamPayload }) => {
        setSample(module.heroBoard === undefined ? null : { name: FIXTURE, payload: module.heroBoard });
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
  });

  return (
    <div
      className="myx-teams"
      {...(import.meta.env.DEV && sample !== null ? { 'data-sample': sample.name } : {})}
    >
      {body}
      <ViewTabs pageId={PAGE_ID} defaults={VIEWS} />
    </div>
  );
}

export default TeamsPage;
