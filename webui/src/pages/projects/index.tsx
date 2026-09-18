// The projects page: the repos the daemon has seen as strips, and the opened
// repo's instruction and memory files in the detail column.
//
// A project here is a repository, not a filter: [[compaction.project]] is
// already keyed by path in the daemon, and the operator asked for a view per
// repo (FEATURES.md 4.14). The list is one request; everything else on the page
// composes from other entities, which is why no project-detail route is asked
// for beyond the files.
import { useEffect, useState } from 'react';
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

/** The rack's column names, printed once on the bay head instead of on every strip (CONTRACTS
 *  section 2, m1 design review B9). The boxes carry the cell's own inline padding so a name sits
 *  over the value it names; the bay's head row supplies the face and the colour. */
function ColumnHeads({ columns }: { columns: readonly { key: string; label: string; w: number }[] }) {
  return (
    <>
      {columns.map((column) => (
        <span className="myx-px-col" key={column.key} style={{ width: `${column.w}ch` }}>
          <span className="myx-px-col-name">{column.label}</span>
        </span>
      ))}
    </>
  );
}


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

/** The columns a view asks for, in its own order, for the bay head. */
function columnsOf(order: readonly string[]): { key: string; label: string; w: number }[] {
  return order.flatMap((key) => (COLUMNS[key] === undefined ? [] : [{ key, ...COLUMNS[key] }]));
}

/**
 * The root as the STRIP prints it: the home directory collapsed to `~`.
 *
 * The column is 44 characters and StripField clips rather than wraps, so a root under the home
 * directory has to say the same thing in fewer of them — `/home/marcos/Documents/dev/projects/x`
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

function fixtureName(): string | null {
  if (!import.meta.env.DEV || typeof window === 'undefined') return null;
  const fromSearch = new URLSearchParams(window.location.search).get('fixture');
  if (fromSearch !== null) return fromSearch;
  const at = window.location.hash.indexOf('?');
  return at === -1 ? null : new URLSearchParams(window.location.hash.slice(at)).get('fixture');
}

/** The board, drawn from a payload. Exported so a test can hand it one. */
export function ProjectsBoard({ payload, files = {}, sample = false, error = null }: {
  payload: ProjectsSlice | null;
  /** Sample file payloads, keyed by project id: the capture fixture seam. */
  files?: Record<string, ProjectFilesPayload>;
  sample?: boolean;
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
        {sample ? <HolderEdge state="grey" label={S.sample} /> : null}
      </header>

      <div className={open === null ? 'myx-px-board' : 'myx-px-board myx-px-board-open'}>
        <div className="myx-px-bays">
          {pending ? (
            <Empty text="project list not routed yet" source="row V4-131" />
          ) : rows.length === 0 ? (
            <Empty text="no repositories seen" source="/api/projects" />
          ) : (
            <Bay
              label={S.repos}
              count={rows.length}
              fields={<ColumnHeads columns={columnsOf(active.fields)} />}
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
                    <StripField key={field.key} w={field.w} value={field.value} {...basisProp(field.basis)} />
                  ))}
                </Strip>
              ))}
            </Bay>
          )}
        </div>

        {/* The column COLLAPSES to 0 width until a strip is opened (.myx-px-board /
            .myx-px-board-open), so there is no empty to fill here: the swell only exists once
            there is something to swell to. */}
        <aside className="myx-px-detail" aria-label={S.detail}>
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
  const [fixture, setFixture] = useState<Fixture | null>(null);
  const name = fixtureName();

  useEffect(() => startProjectsPolling(15000), []);

  useEffect(() => {
    if (name === null) return undefined;
    let live = true;
    void import(/* @vite-ignore */ `./fixtures/${name}.ts`)
      .then((module: { fixture?: Fixture }) => {
        if (live) setFixture(module.fixture ?? null);
      })
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, [name]);

  return (
    <ProjectsBoard
      payload={fixture === null ? store.data : { projects: fixture.projects }}
      files={fixture?.files ?? {}}
      sample={fixture !== null}
      error={store.error}
    />
  );
}
