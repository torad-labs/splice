// The projects page: the repos the daemon has seen as strips, and the opened
// repo's instruction and memory files in the detail column.
//
// A project here is a repository, not a filter: [[compaction.project]] is
// already keyed by path in the daemon, and the operator asked for a view per
// repo (FEATURES.md 4.14). The list is one request; everything else on the page
// composes from other entities, which is why no project-detail route is asked
// for beyond the files.
import { useEffect, useState } from 'react';
import { useLocation } from 'react-router';
import { ViewTabs, useViews } from '@features/views';
import type { View } from '@features/views';
import { startProjectsPolling, useProjects } from '@entities/project';
import type { ProjectFilesPayload, ProjectRow, ProjectsSlice } from '@entities/project';
import { FileView } from '@widgets/file-view';
import { Bay, Empty, HolderEdge, Strip, StripField } from '@shared/ui';
import { Fault } from '@shared/controls';
import type { Basis } from '@shared/ui';
import { timeAgo } from '@shared/lib';
import { S } from './strings';
import './projects.css';

const PAGE_ID = 'projects';


const DEFAULT_VIEWS: View[] = [
  {
    id: 'all',
    name: 'repos',
    layout: 'rack',
    filter: {},
    sort: null,
    group: null,
    fields: ['repo', 'sessions', 'teams', 'turns', 'cost', 'last'],
  },
];

interface Field {
  key: string;
  label: string;
  w: number;
  value: string;
  /** Absent for a cell with no value: the glyph is the whole statement (m1 design review B8),
   *  and a basis word beside it said the same thing twice. */
  basis?: Basis | undefined;
}

// The repo column carries an absolute root, and the cost column carries
// "$12.84 estimated": both are measured against the widest thing they print,
// because StripField clips rather than wraps (found in the 2026-09-18 capture).
// A root deeper than 44 characters clips here and is printed whole in the
// detail header when the strip is opened.
// ONE declaration per column, because the rack prints its names once on the bay head and the
// strips below carry values only (CONTRACTS.md section 2, m1 design review B9).
const COLUMNS: Record<string, { label: string; w: number }> = {
  repo: { label: S.repo, w: 44 },
  sessions: { label: S.sessions, w: 8 },
  teams: { label: S.teams, w: 7 },
  turns: { label: S.turns, w: 8 },
  cost: { label: S.cost, w: 23 },
  last: { label: S.last, w: 12 },
};

/**
 * The root as the STRIP prints it: the home directory collapsed to `~`.
 *
 * The column is 44 characters and StripField clips rather than wraps, so a root under the home
 * directory has to say the same thing in fewer of them — `/home/user/Documents/dev/projects/x`
 * and `~/Documents/dev/projects/x` are the same location, and only one of them fits. The detail
 * header prints the path WHOLE, unshortened, which is where an operator copies it from.
 */
function rootText(root: string): string {
  return root.replace(/^\/(?:home|Users)\/[^/]+\//, '~/');
}

/** USD per day, two places, or null when no head declared rates. */
function costText(usd: number | null): string | null {
  return usd === null ? null : `$${usd.toFixed(2)}`;
}

/** An absent cell must not pass an explicit `basis: undefined` — shared/ui runs
 *  `exactOptionalPropertyTypes`, where `{ basis: undefined }` is not `{}` (the same rule the
 *  controls follow with `busy?: boolean | undefined`). */
function basisProp(basis: Basis | undefined): { basis?: Basis } {
  return basis === undefined ? {} : { basis };
}

function fieldsOf(row: ProjectRow, order: readonly string[]): Field[] {
  const cost = costText(row.cost_today_usd);
  const values: Record<string, { value: string; basis?: Basis }> = {
    repo: { value: rootText(row.root), basis: 'measured' },
    sessions: { value: String(row.live_sessions), basis: 'measured' },
    teams: { value: String(row.teams), basis: 'measured' },
    turns: { value: String(row.turns_today), basis: 'measured' },
    // A dollar figure the daemon derived from declared rates is an estimate, and
    // it says so on the strip (FEATURES.md 4.6). No rates at all is not a cost
    // of zero: it is the absence of a card, and it prints the absence glyph.
    cost: cost === null ? { value: S.absent } : { value: cost, basis: 'estimated' },
    last: row.last_activity === null
      ? { value: S.absent }
      : { value: timeAgo(row.last_activity), basis: 'measured' },
  };
  const fields: Field[] = [];
  for (const key of order) {
    const found = values[key];
    const column = COLUMNS[key];
    if (found === undefined || column === undefined) continue;
    fields.push({ ...column, key, value: String(found.value), ...basisProp(found.basis) });
  }
  return fields;
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
export function ProjectsBoard({ payload, files = {}, sample, error = null }: {
  payload: ProjectsSlice | null;
  /** Sample file payloads, keyed by project id: the capture fixture seam. */
  files?: Record<string, ProjectFilesPayload>;
  /** The fixture's own file name when a fixture fed this board, undefined otherwise: the capture
   *  marker and the sample chrome are the same value, so they cannot disagree. */
  sample?: string | undefined;
  error?: string | null;
}) {
  const { active } = useViews(PAGE_ID, DEFAULT_VIEWS);
  const [openId, setOpenId] = useState<string | null>(null);

  const pending = payload !== null && 'pending' in payload;
  const rows = payload !== null && !pending ? payload.projects : [];
  const open = rows.find((row) => row.id === openId) ?? null;
  const openFiles = open === null ? undefined : files[open.id];

  if (error !== null && payload === null) return <Fault message={error} />;

  return (
    <div className="myx-px">
      <header className="myx-px-head">
        <h2 className="myx-px-title">{S.title}</h2>
        <ViewTabs pageId={PAGE_ID} defaults={DEFAULT_VIEWS} />
        {sample === undefined ? null : <HolderEdge state="grey" label={S.sample} />}
      </header>

      {/* The capture marker (law 23): set on the same DEV branch as the fixture import and
          carrying that fixture's own file name, so a driver asserts "the fixture loaded" instead of
          inferring it. The guard is IN the expression, so a production build drops the branch and
          the attribute's very name - fixture-leak.mjs asserts it is absent from dist. */}
      <div
        className={open === null ? 'myx-px-board' : 'myx-px-board myx-px-board-open'}
        {...(import.meta.env.DEV && sample !== undefined ? { 'data-sample': sample } : {})}
      >
        <div className="myx-px-bays">
          {pending ? (
            <Empty text="project list not routed yet" source="row V4-131" />
          ) : rows.length === 0 ? (
            <Empty text="no repositories seen" source="/api/projects" />
          ) : (
            <Bay
              label={S.repos}
              count={rows.length}
              actions={<a className="myx-px-open" href="#/sessions">{S.sessionsWord}</a>}
            >
              {rows.map((row) => (
                // The holder edge carries the project's OWN state — something is running here, or
                // nothing is — because that is what a holder edge is for. It used to carry the
                // selection (green on the opened row) with the noun `repo` printed on it, so every
                // unopened project wore a grey edge labelled with the first field's own label and
                // read as struck (m1 design review B10). Selection is the strip's own `selected`.
                <Strip
                  key={row.id}
                  edge={row.live_sessions > 0 ? 'green' : 'grey'}
                  edgeLabel={row.live_sessions > 0 ? S.running : S.quiet}
                  selected={open !== null && row.id === open.id}
                  onOpen={() => setOpenId(row.id)}
                  ariaLabel={`${S.title} ${row.root}`}
                >
                  {/* No label on a cell: the bay head prints the column names once for the whole
                      rack (CONTRACTS.md section 2, m1 design review B9). */}
                  {fieldsOf(row, active.fields).map((field) => (
                    <StripField key={field.key} w={field.w} label={field.label} value={field.value} {...basisProp(field.basis)} />
                  ))}
                </Strip>
              ))}
            </Bay>
          )}
        </div>

        {/* The column COLLAPSES to 0 width until a strip is opened (.myx-px-board /
            .myx-px-board-open), so there is no empty to fill here: the swell only exists once
            there is something to swell to. */}
        {/* THE EMPTY LANDMARK IS HIDDEN WHILE IT IS EMPTY (M1-123, one shape across five pages).
            At rest this aside is mounted and holds nothing, and an <aside> with a label is a
            COMPLEMENTARY LANDMARK whatever else it carries — measured in the live accessibility
            tree: role=complementary, ignored=false, children=0 — so a reader's landmark list
            carried an empty "project detail". aria-hidden is gated by the SAME `open === null` that
            gates the content, so the exposure and the content cannot desync: they are one
            expression, not two facts kept in step. The element stays mounted, which is what gives
            the track something to transition from — the whole reason collapse beat unmount. */}
        <aside className="myx-px-detail" aria-label={S.detail} aria-hidden={open === null}>
          {open === null ? null : (
            <>
              <div className="myx-px-detail-head">
                <span className="myx-px-detail-name">{open.root}</span>
                <button type="button" className="myx-px-close" onClick={() => setOpenId(null)}>
                  {S.close}
                </button>
              </div>
              <Bay label={S.files}>
                {openFiles === undefined ? (
                  <FileView projectId={open.id} />
                ) : (
                  <FileView projectId={open.id} files={openFiles} />
                )}
              </Bay>
            </>
          )}
        </aside>
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
    // return left the previous state in place; a static render cannot see an effect, so the suite
    // was green while it happened).
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
      error={store.error}
    />
  );
}
