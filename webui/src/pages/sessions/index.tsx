// The sessions page: one bay of session strips per group, under four saved
// views (by head, by project, by team, and a timeline), with the opened strip
// swelling into its detail column.
//
// WHAT THE STRIP SAYS, and why the states are printed rather than coloured. The
// holder edge carries availability (green live, amber stale, grey gone) and
// ALWAYS prints the word beside it, so a grayscale screenshot still says which
// session stopped reporting. A stale session also cocks, which is the world's
// gesture for "this one needs me": it is alive but has not been heard from
// inside the stale window. A gone session is not struck: striking is the
// verdict on a disabled or excluded row, and a gone session is still readable
// (its transcript and its perf rows stay).
//
// WHAT IS DELIBERATELY NOT HERE: the per-session turn join of FEATURES.md 4.4
// (turns by the perf session tag, tokens by bucket, cost). Those numbers come
// from /api/perf/turns, which is pending V4-127, so the block could only print
// an honest empty today; it belongs with the turns page that owns that route.
//
// The file exports two things: SessionsPage, which reads the entities and loads
// a capture fixture, and SessionsBoard, which draws a payload it is handed. The
// split is what lets the test render the board from data instead of from a
// store (a static render sees a zustand store's initial state, never its
// current one), and it is also the fixture seam.
import { useEffect, useState } from 'react';
import { ViewTabs, useViews } from '@features/views';
import type { View } from '@features/views';
import {
  fetchSessionEdges,
  nameForAddress,
  peerAddresses,
  sessionLabel,
  startSessionsPolling,
  useSession,
  useSessionEdges,
  useSessionRegistry,
} from '@entities/session';
import type { SessionEdgesSlice, SessionRow, SessionsPayload } from '@entities/session';
import { Conversation } from '@widgets/conversation';
import { FileView } from '@widgets/file-view';
import { Bay, Empty, Figure, HolderEdge, Reveal, Strip, StripField } from '@shared/ui';
import { Fault } from '@shared/controls';
import { timeAgo } from '@shared/lib';
import { S } from './strings';
import { groupByOf, groupHref, selectionOf } from './select';
import { columnsOf, projectKeyOf, SessionStrip } from './strip';
import './sessions.css';

const PAGE_ID = 'sessions';

/** The rack's column names, printed once on the bay head instead of on every strip (CONTRACTS
 *  section 2, m1 design review B9). The boxes carry the cell's own inline padding so a name sits
 *  over the value it names; the bay's head row supplies the face and the colour. */
function ColumnHeads({ columns }: { columns: readonly { key: string; label: string; w: number }[] }) {
  return (
    <>
      {columns.map((column) => (
        <span className="myx-sx-col" key={column.key} style={{ width: `${column.w}ch` }}>
          <span className="myx-sx-col-name">{column.label}</span>
        </span>
      ))}
    </>
  );
}

/** The four views this page ships. `by head` is the default and stands first. */
const DEFAULT_VIEWS: View[] = [
  { id: 'by-head', name: 'by head', layout: 'rack', filter: {}, sort: null, group: 'head', fields: ['name', 'head', 'project', 'started', 'seen', 'peer'] },
  { id: 'by-project', name: 'by project', layout: 'rack', filter: {}, sort: null, group: 'repo', fields: ['name', 'project', 'head', 'started', 'seen', 'peer'] },
  { id: 'by-team', name: 'by team', layout: 'rack', filter: {}, sort: null, group: 'team', fields: ['name', 'head', 'project', 'started', 'seen', 'peer'] },
  { id: 'timeline', name: 'timeline', layout: 'timeline', filter: { window: '24h', bucket: '1h' }, sort: null, group: null, fields: ['name', 'head', 'project', 'started', 'peer'] },
];

const pad = (value: number): string => String(value).padStart(2, '0');

/** One hand-off, as a strip: which way it went, to whom, and when. */
function EdgeRows({ edges, rows }: { edges: SessionEdgesSlice | null; rows: readonly SessionRow[] }) {
  if (edges === null) return null;
  if ('pending' in edges) return <Empty text="message edges not routed yet" source="row V4-130" />;
  if (edges.edges.length === 0) return <Empty text="no hand-offs recorded" source="/api/sessions/{id}/edges" />;
  return (
    <>
      {[...edges.edges].sort((a, b) => b.at - a.at).map((edge) => (
        <Strip
          key={`${edge.from}:${edge.to}:${edge.at}`}
          edge="grey"
          edgeLabel={edge.direction === 'out' ? S.sent : S.received}
          ariaLabel={`${edge.direction} ${edge.to}`}
        >
          <StripField
            w={20}
            label={S.peer}
            value={nameForAddress(rows, edge.direction === 'out' ? edge.to : edge.from) ?? edge.to}
          />
          <StripField w={22} label={S.address} value={edge.direction === 'out' ? edge.to : edge.from} />
          <StripField w={10} label={S.at} value={timeAgo(edge.at)} />
        </Strip>
      ))}
    </>
  );
}

/** The board, drawn from a payload. Exported so a test can hand it one. */
export function SessionsBoard({ payload, edges = null, locked = false, error = null, sample = false }: {
  payload: SessionsPayload | null;
  edges?: SessionEdgesSlice | null;
  locked?: boolean;
  error?: string | null;
  /** True when a capture fixture is feeding this board, which the header prints. */
  sample?: boolean;
}) {
  const { active } = useViews(PAGE_ID, DEFAULT_VIEWS);
  const [openId, setOpenId] = useState<string | null>(null);

  // A registration with no session id still has to be openable, and its key
  // must not collide with "nothing is open": `session_id === openId` would
  // match null against null and open the first id-less row on load (found in
  // the 2026-09-18 capture).
  const keyOf = (row: SessionRow): string => row.session_id ?? `pid:${row.pid ?? 0}`;
  const columns = columnsOf(active.fields);

  const rows = payload?.sessions ?? [];
  const open = rows.find((row) => keyOf(row) === openId) ?? null;

  // Edges are read for the OPEN session only. A board-wide column would need one
  // request per row; see the note on M2-02 for the route V4-130 would need.
  useEffect(() => {
    if (open?.session_id != null) void fetchSessionEdges(open.session_id);
  }, [open?.session_id]);

  const edgePeers = edges !== null && !('pending' in edges) ? peerAddresses(edges.edges) : [];
  const peerOf = (row: SessionRow): string | null => {
    if (open === null || row.session_id !== open.session_id) return null;
    const first = edgePeers[0];
    return first === undefined ? null : nameForAddress(rows, first) ?? first;
  };

  const selection = selectionOf(rows, active, Date.now());
  const by = groupByOf(active);
  const openLabel = by === 'repo' ? S.openProject : by === 'team' ? S.openTeam : S.openHead;
  const groupWord = by === 'repo' ? S.project : by === 'team' ? S.team : S.head;
  const undated = selection.kind === 'timeline' ? selection.timeline.undated : [];
  const idleBuckets = selection.kind === 'timeline'
    ? selection.timeline.buckets.filter((bucket) => bucket.sessions.length === 0).length
    : 0;

  if (locked) return <Empty text="console locked" source="management key" />;
  if (error !== null && payload === null) return <Fault message={error} />;

  const strip = (row: SessionRow) => (
    <SessionStrip
      key={keyOf(row)}
      row={row}
      peer={peerOf(row)}
      selected={keyOf(row) === openId}
      order={active.fields}
      onOpen={() => setOpenId(keyOf(row))}
    />
  );

  return (
    <div className="myx-sx">
      <header className="myx-sx-head">
        <h2 className="myx-sx-title">{S.title}</h2>
        <ViewTabs pageId={PAGE_ID} defaults={DEFAULT_VIEWS} />
        <Reveal label={S.headless}>
          <p className="myx-sx-note">{payload?.note ?? S.registry}</p>
        </Reveal>
        {sample ? <HolderEdge state="grey" label={S.sample} /> : null}
      </header>

      <div className={open === null ? 'myx-sx-board' : 'myx-sx-board myx-sx-board-open'}>
        <div className="myx-sx-bays">
          {rows.length === 0 ? (
            <Empty text="no sessions registered" source="/api/sessions" />
          ) : selection.kind === 'groups' ? (
            selection.groups.map((group) => (
              <Bay
                key={group.key}
                label={`${groupWord}: ${group.key}`}
                count={group.count}
                fields={<ColumnHeads columns={columns} />}
                actions={<a className="myx-sx-open" href={groupHref(by)}>{openLabel}</a>}
              >
                {group.rows.map(strip)}
              </Bay>
            ))
          ) : (
            <>
              <div className="myx-sx-window">
                <Figure value={selection.window.hours} unit="h" basis="measured" />
                <Figure value={idleBuckets} unit={S.idle} basis="measured" />
              </div>
              {selection.timeline.buckets
                .filter((bucket) => bucket.sessions.length > 0)
                .map((bucket) => (
                  <Bay
                    key={bucket.start}
                    label={`${pad(new Date(bucket.start).getHours())}:00`}
                    count={bucket.sessions.length}
                    fields={<ColumnHeads columns={columns} />}
                  >
                    {bucket.sessions.map(strip)}
                  </Bay>
                ))}
              {undated.length === 0 ? null : (
                <Bay label={S.undated} count={undated.length} fields={<ColumnHeads columns={columns} />}>
                  {undated.map(strip)}
                </Bay>
              )}
            </>
          )}
        </div>

        {/* The column COLLAPSES to 0 width until a strip is opened (.myx-sx-board /
            .myx-sx-board-open): the swell only exists once there is something to swell to, so
            there is no empty to fill here. */}
        <aside className="myx-sx-detail" aria-label={S.detail}>
          {open === null ? null : (
            <>
              <div className="myx-sx-detail-head">
                <span className="myx-sx-detail-name">{sessionLabel(open)}</span>
                <button type="button" className="myx-sx-close" onClick={() => setOpenId(null)}>
                  {S.close}
                </button>
              </div>
              <Bay label={S.conversation}>
                {open.session_id === null ? (
                  <Empty text="this registration carries no session id" source="session registry" />
                ) : (
                  <Conversation sessionId={open.session_id} />
                )}
              </Bay>
              <Bay label={S.files}>
                {projectKeyOf(open) === null ? (
                  <Empty text="this registration carries no cwd" source="session registry" />
                ) : (
                  <FileView projectId={projectKeyOf(open) ?? ''} />
                )}
              </Bay>
              <Bay label={S.handoffs}>
                <EdgeRows edges={edges} rows={rows} />
              </Bay>
            </>
          )}
        </aside>
      </div>
    </div>
  );
}

/** The DEV-only sample board a capture reads (CONTRACTS.md section 4, the fixture rule). */
function fixtureName(): string | null {
  if (!import.meta.env.DEV || typeof window === 'undefined') return null;
  const fromSearch = new URLSearchParams(window.location.search).get('fixture');
  if (fromSearch !== null) return fromSearch;
  const at = window.location.hash.indexOf('?');
  return at === -1 ? null : new URLSearchParams(window.location.hash.slice(at)).get('fixture');
}

export default function SessionsPage() {
  const locked = useSession((s) => s.locked);
  const registry = useSessionRegistry((s) => s);
  const edges = useSessionEdges((s) => s);
  const [fixture, setFixture] = useState<SessionsPayload | null>(null);
  const name = fixtureName();

  useEffect(() => startSessionsPolling(5000), []);

  // The fixture is imported by name at runtime, never bundled: the shipped dist
  // carries no sample bytes, and the branch is dead outside DEV.
  useEffect(() => {
    if (name === null) return undefined;
    let live = true;
    void import(/* @vite-ignore */ `./fixtures/${name}.ts`)
      .then((module: { fixture?: SessionsPayload }) => {
        if (live) setFixture(module.fixture ?? null);
      })
      .catch(() => undefined); // a missing fixture is not a page error: the live read stands
    return () => {
      live = false;
    };
  }, [name]);

  return (
    <SessionsBoard
      payload={fixture ?? registry.data}
      edges={edges.data}
      locked={locked}
      error={registry.error}
      sample={fixture !== null}
    />
  );
}
