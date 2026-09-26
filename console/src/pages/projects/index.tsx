// The projects page (docs/design/DESIGN.md section 7): one row per repo the daemon has seen, the day
// summed over them above, and the opened repo beside the table with what governs it and its files.
//
// A project here is a repository, not a filter: [[compaction.project]] is already keyed by path in
// the daemon, and the operator asked for a view per repo (FEATURES.md 4.14). Three reads, all
// ProjectsRoutes: the list, the opened repo's own row (GET /api/projects/{id}, detail.tsx), and its
// files (the file view).
//
// WHAT THE ROW SAYS, shown before it is said (DESIGN.md section 3): the day's turns are a bar
// against the busiest repo's, so the table reads as where the work went; the state closes the row
// as a badge, the way the sessions board prints its status last.
import { useEffect, useState } from 'react';
import { useLocation } from 'react-router';
import { ArrowUpRightIcon } from '@phosphor-icons/react/dist/csr/ArrowUpRight';
import { ViewTabs, useViews } from '@features/views';
import type { View } from '@features/views';
import { startProjectsPolling, useProjects } from '@entities/project';
import type { ProjectFilesPayload, ProjectRow, ProjectsPayload } from '@entities/project';
import { FileView } from '@widgets/file-view';
import { Badge, DataTable, DetailPanel, Empty, InfoTip, KeyValue, Meter, PageHeader, Section, StackedBar, Stat, StatRow, weightedColumns } from '@shared/ui';
import type { Column } from '@shared/ui';
import { Fault } from '@shared/controls';
import { timeAgo } from '@shared/lib';
import { ProjectCompaction, ProjectStatusline, StateBadge, costText, dayText, detailRowsOf, unpricedText, unpricedTurnsOf, useOpenProject } from './detail';
import { ProjectStanding } from './standing';
import { H, S } from './strings';
import './projects.css';

const PAGE_ID = 'projects';

/** The one view this page ships. Its fields are the table's middle columns: the repo always opens
 *  the row and its state always closes it. */
const DEFAULT_VIEWS: View[] = [
  { id: 'all', name: S.allRepos, layout: 'rack', filter: {}, sort: null, group: null, fields: ['sessions', 'teams', 'turns', 'cost', 'last'] },
];

/** Each column's share of the table, normalised over the columns a view shows. */
const WEIGHTS: Record<string, number> = { repo: 30, sessions: 9, teams: 7, turns: 19, cost: 10, last: 13, state: 12 };

/**
 * The root as the TABLE prints it: the home directory collapsed to `~`. A root under the home
 * directory says the same thing in fewer characters; the cell's title and the detail's heading
 * carry it whole, which is where an operator copies it from.
 */
export function rootText(root: string): string {
  return root.replace(/^\/(?:home|Users)\/[^/]+\//, '~/');
}

/** The table's columns for a view's fields, in its order, each at its share of the width. */
export function columnsOf(fields: readonly string[], rows: readonly ProjectRow[], now = Date.now()): Column<ProjectRow>[] {
  const busiest = Math.max(1, ...rows.map((row) => row.turns_today));
  const middle: Record<string, Column<ProjectRow>> = {
    sessions: { key: 'sessions', label: S.sessions, align: 'end', mono: true, cell: (row) => String(row.live_sessions) },
    teams: { key: 'teams', label: S.teams, align: 'end', mono: true, cell: (row) => String(row.teams) },
    turns: {
      key: 'turns',
      label: S.turns,
      cell: (row) => (
        <Meter tone="neutral" value={row.turns_today / busiest} label={`${S.turns} ${row.turns_today}`} figure={String(row.turns_today)} />
      ),
    },
    cost: { key: 'cost', label: S.cost, basis: 'estimated', align: 'end', mono: true, cell: (row) => costText(row.cost_today_usd) },
    last: {
      key: 'last',
      label: S.last,
      align: 'end',
      mono: true,
      cell: (row) => (row.last_activity === null ? S.absent : timeAgo(row.last_activity, now)),
    },
  };
  const shown = [...new Set(fields)].flatMap((key) => (middle[key] === undefined ? [] : [middle[key]]));
  return weightedColumns([
    { key: 'repo', label: S.repo, primary: true, cell: (row) => <span title={row.root}>{rootText(row.root)}</span> },
    ...shown,
    { key: 'state', label: S.state, cell: (row) => <StateBadge row={row} /> },
  ], WEIGHTS);
}

/** The day over every repo: how many are busy, how many sessions run, the turns and the cost. */
function Summary({ rows }: { rows: readonly ProjectRow[] }) {
  const busy = rows.filter((row) => row.live_sessions > 0).length;
  const sessions = rows.reduce((sum, row) => sum + row.live_sessions, 0);
  const turns = rows.reduce((sum, row) => sum + row.turns_today, 0);
  const priced = rows.filter((row) => row.cost_today_usd !== null);
  const cost = priced.reduce((sum, row) => sum + (row.cost_today_usd ?? 0), 0);
  const unpriced = rows.reduce((sum, row) => sum + unpricedTurnsOf(row), 0);
  return (
    <StatRow>
      <Stat
        label={S.repos}
        value={rows.length}
        chart={(
          <StackedBar
            label={S.repos}
            legend
            parts={[
              { key: 'busy', label: S.busy, value: busy, mark: 'ok' },
              { key: 'quiet', label: S.quiet, value: rows.length - busy, mark: 'series-3' },
            ]}
          />
        )}
      />
      <Stat label={S.running} value={sessions} />
      {/* Every row counts the same day, the daemon's UTC one, which the table has no column for. */}
      <Stat label={S.turns} value={turns} sub={dayText(rows[0]?.day_start ?? 0)} />
      <Stat
        label={S.cost}
        basis="estimated"
        value={priced.length === 0 ? S.absent : costText(cost)}
        {...(unpriced === 0 ? {} : {
          sub: (
            <span className="myx-px-note">
              {unpricedText(unpriced)}
              <InfoTip text={H.cost} label={S.cost} />
            </span>
          ),
        })}
      />
    </StatRow>
  );
}

/** The DEV-only sample list a capture reads (CONTRACTS.md section 4, the fixture rule). */
interface Fixture {
  projects: ProjectRow[];
  files: Record<string, ProjectFilesPayload>;
}

/** One fixture, as ONE value: the name it was asked for and the bytes that arrived. The capture
 *  marker is set from this and from nothing else, so a name with no module can never leave a
 *  marker behind - a marker that survives a failed import says the opposite of the truth (law 23:
 *  an instrument must be able to distinguish PASSED, FAILED and DID NOT RUN). Exported because a
 *  test pins exactly that, with a name that resolves to no file at all. */
export async function loadFixture(name: string): Promise<{ name: string; payload: Fixture } | null> {
  if (!import.meta.env.DEV) return null;
  const module = await import(/* @vite-ignore */ `./fixtures/${name}.ts`)
    .then((loaded: { fixture?: Fixture }) => loaded)
    .catch(() => null);
  const payload = module === null ? null : module.fixture ?? null;
  return payload === null ? null : { name, payload };
}

/** The fixture this address asks for, taking the ROUTER's own search string so the page re-renders
 *  when the address changes. Read from `window.location` instead, a page that consumes no other
 *  router value keeps the name it rendered with, and with it a stale capture marker: measured in a
 *  browser on 2026-09-18, projects held `list` after the query was dropped while ten other pages
 *  cleared, and the difference was only that they read `useLocation`. A static render cannot see an
 *  effect or a navigation, so the suite was green while it happened. */
function fixtureName(search: string): string | null {
  if (!import.meta.env.DEV) return null;
  const fromRouter = new URLSearchParams(search).get('fixture');
  if (fromRouter !== null && fromRouter.trim() !== '') return fromRouter;
  if (typeof window === 'undefined') return null;
  const fromSearch = new URLSearchParams(window.location.search).get('fixture');
  return fromSearch === null || fromSearch.trim() === '' ? null : fromSearch;
}

/** The board, drawn from a payload. Exported so a test can hand it one. */
export function ProjectsBoard({ payload, files = {}, sample, error = null, lastRead = null }: {
  payload: ProjectsPayload | null;
  /** Sample file payloads, keyed by project id: the capture fixture seam. */
  files?: Record<string, ProjectFilesPayload>;
  /** The fixture's own file name when a fixture fed this board, undefined otherwise: the capture
   *  marker and the sample chrome are the same value, so they cannot disagree. */
  sample?: string | undefined;
  error?: string | null;
  /** When the rows on screen were read, which the fault prints as stale while `error` stands. */
  lastRead?: number | null;
}) {
  const { active } = useViews(PAGE_ID, DEFAULT_VIEWS);
  const [openId, setOpenId] = useState<string | null>(null);

  const rows = payload?.projects ?? [];
  const open = rows.find((row) => row.id === openId) ?? null;
  const detail = useOpenProject(open, sample !== undefined);
  const openFiles = open === null ? undefined : files[open.id];

  if (error !== null && payload === null) return <Fault message={error} />;

  // An opened repo narrows the table to what finds a row again: its name, its sessions, its day.
  const columns = columnsOf(open === null ? active.fields : ['sessions', 'turns'], rows);

  return (
    <div className="myx-px">
      <PageHeader
        title={S.title}
        info={{ text: H.about, label: S.about }}
        actions={sample === undefined ? undefined : <Badge tone="neutral">{S.sample}</Badge>}
      >
        <ViewTabs pageId={PAGE_ID} defaults={DEFAULT_VIEWS} />
      </PageHeader>

      {/* A read that fails after one landed keeps the rows and says so: the fault used to show only
          while nothing had loaded, so a dead daemon's last list read as a live one. */}
      {error === null ? null : <Fault message={error} lastRead={lastRead} />}

      {rows.length === 0 ? null : <Summary rows={rows} />}

      {/* The capture marker (law 23): set on the same DEV branch as the fixture import and
          carrying that fixture's own file name, so a driver asserts "the fixture loaded" instead of
          inferring it. The guard is IN the expression, so a production build drops the branch and
          the attribute's very name - fixture-leak.mjs asserts it is absent from dist. */}
      <div
        className={open === null ? 'myx-px-board' : 'myx-px-board myx-px-board-open'}
        {...(import.meta.env.DEV && sample !== undefined ? { 'data-sample': sample } : {})}
      >
        <div className="myx-px-list">
          {rows.length === 0 ? (
            <Empty text={S.noProjects} source={H.noProjects} />
          ) : (
            <DataTable
              columns={columns}
              rows={rows}
              rowKey={(row) => row.id}
              label={S.title}
              onOpen={(row) => setOpenId(row.id)}
              openLabel={(row) => `${S.title} ${row.root}`}
              selectedKey={openId}
            />
          )}
        </div>

        {/* THE DETAIL IS UNMOUNTED AT REST: nothing holds a column until a row is opened, and an
            empty labelled <aside> would still be a landmark in a reader's list (M1-123). */}
        {open === null ? null : (
          <DetailPanel
            title={rootText(open.root)}
            label={S.detail}
            status={<StateBadge row={detail.row ?? open} />}
            onClose={() => setOpenId(null)}
            closeLabel={S.close}
          >
            {detail.error === null ? null : <Fault message={detail.error} lastRead={detail.lastRead} />}
            <Section
              title={S.activity}
              actions={<a className="myx-px-go" href="#/sessions" aria-label={S.openSessions}><ArrowUpRightIcon aria-hidden="true" /></a>}
            >
              <KeyValue rows={detailRowsOf(detail.row ?? open)} />
            </Section>
            <Section title={S.standing} info={{ text: H.standing, label: S.standing }}>
              <ProjectStanding root={open.root} />
            </Section>
            <Section title={S.compaction} info={{ text: H.compaction, label: S.compaction }}>
              <ProjectCompaction row={detail.row ?? open} />
            </Section>
            <Section title={S.statusline} info={{ text: H.statusline, label: S.statusline }}>
              <ProjectStatusline row={detail.row ?? open} />
            </Section>
            <Section title={S.files}>
              {openFiles === undefined ? <FileView projectId={open.id} /> : <FileView projectId={open.id} files={openFiles} />}
            </Section>
          </DetailPanel>
        )}
      </div>
    </div>
  );
}

export default function ProjectsPage() {
  const store = useProjects((s) => s);
  const { search } = useLocation();
  const [sample, setSample] = useState<{ name: string; payload: Fixture } | null>(null);
  const name = fixtureName(search);
  const fixture = sample === null ? null : sample.payload;

  useEffect(() => startProjectsPolling(15000), []);

  useEffect(() => {
    if (name === null) {
      // The address no longer asks for this page's fixture, so the marker must GO: a name that is
      // asked for and then dropped is exactly the stale marker this row exists to prevent (measured
      // in a browser on 2026-09-18 - five pages kept one across a hash change, because the early
      // return left the previous state in place; a static render cannot see an effect, so the
      // suite was green while it happened).
      setSample(null);
      return undefined;
    }
    let live = true;
    // The whole value, name and bytes together: a second state for the name would be the stale
    // marker this row exists to prevent.
    void loadFixture(name).then((loaded) => {
      if (live) setSample(loaded);
    });
    return () => {
      live = false;
    };
  }, [name]);

  return (
    <ProjectsBoard
      payload={fixture === null ? store.data : { projects: fixture.projects }}
      files={fixture?.files ?? {}}
      sample={sample?.name}
      error={fixture === null ? store.error : null}
      lastRead={store.lastUpdated}
    />
  );
}
