// The sessions page: the departure board (docs/design/DESIGN.md section 7). One row per session with
// fixed columns and the status printed last, grouped under four saved views (by head, by project,
// by team, and a timeline). Opening a row puts its detail beside the board; the board stays.
//
// WHAT THE ROW SAYS, and why the states are printed rather than coloured. The status cell carries
// availability (ok live, warn stale, neutral gone) and ALWAYS prints the word beside its dot, so a
// greyscale screenshot still says which session stopped reporting. A stale row also marks its
// leading edge, because it is the one that needs the operator. A gone session is not struck:
// striking is the verdict on a disabled or excluded row, and a gone session is still readable
// (its transcript and its perf rows stay).
//
// WHAT IS DELIBERATELY NOT HERE: the per-session turn join of FEATURES.md 4.4 (turns by the perf
// session tag, tokens by bucket, cost). Those numbers come from /api/perf/turns, which is pending
// V4-127, so the block could only print an honest empty today; it belongs with the turns page that
// owns that route.
//
// The file exports two things: SessionsPage, which reads the entities and loads a capture fixture,
// and SessionsBoard, which draws a payload it is handed. The split is what lets the test render
// the board from data instead of from a store (a static render sees a zustand store's initial
// state, never its current one), and it is also the fixture seam.
import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { ViewTabs, useViews } from '@features/views';
import type { View } from '@features/views';
import { HeadMark, hueClass, useHues } from '@entities/control-status';
import {
  fetchSessionEdges,
  latestPeer,
  peerLabel,
  sessionLabel,
  startBoardEdgesPolling,
  startSessionsPolling,
  useBoardEdges,
  useSession,
  useSessionEdges,
  useSessionRegistry,
  UNKNOWN_HEAD,
} from '@entities/session';
import type { BoardEdgesPayload, SessionEdgesPayload, SessionRow, SessionsPayload } from '@entities/session';
import { Conversation } from '@widgets/conversation';
import { FileView } from '@widgets/file-view';
import { Badge, DataTable, DetailPanel, Empty, PageHeader, Reveal, Section, Tally } from '@shared/ui';
import type { Column, RowGroup } from '@shared/ui';
import { Fault } from '@shared/controls';
import { timeAgo } from '@shared/lib';
import { S } from './strings';
import { groupByOf, groupHref, selectionOf } from './select';
import { baseOf, FIELD_LABEL, fieldsOf, headText, projectKeyOf, toneOf } from './strip';
import './sessions.css';

const PAGE_ID = 'sessions';

/** The four views this page ships. `by head` is the default and stands first. */
const DEFAULT_VIEWS: View[] = [
  // A grouped view does not repeat its group as a column: the bay's own label already names it.
  { id: 'by-head', name: 'by head', layout: 'rack', filter: {}, sort: null, group: 'head', fields: ['name', 'project', 'started', 'seen', 'peer'] },
  { id: 'by-project', name: 'by project', layout: 'rack', filter: {}, sort: null, group: 'repo', fields: ['name', 'head', 'started', 'seen', 'peer'] },
  { id: 'by-team', name: 'by team', layout: 'rack', filter: {}, sort: null, group: 'team', fields: ['name', 'head', 'project', 'started', 'seen', 'peer'] },
  { id: 'timeline', name: 'timeline', layout: 'timeline', filter: { window: '24h', bucket: '1h' }, sort: null, group: null, fields: ['name', 'head', 'project', 'started', 'peer'] },
];

const pad = (value: number): string => String(value).padStart(2, '0');

/** Why a session has no head: splice did not start it, or the daemon could not read how it was
 *  started. A sentence, so it lives here (CONTRACTS.md 4). It says only what the registry knows:
 *  it used to add "so their turns do not pass through splice", and the walkthrough watched a
 *  headless session's turn go through a head (S3); where a turn goes is the turns page's fact. */
export const NO_HEAD_WHY = 'splice did not start these sessions, or could not tell which head did';

/** Why a group of sessions has no head, from each row's route when the daemon reports one: a
 *  session started with `claude` directly skips splice; one whose environment could not be read
 *  may not. A daemon that reports no route keeps the sentence above, which covers both. */
export function noHeadWhy(rows: readonly SessionRow[]): string {
  const direct = rows.filter((row) => row.route === 'direct').length;
  const unread = rows.filter((row) => row.route === 'unknown').length;
  if (direct + unread === 0) return NO_HEAD_WHY;
  const parts = [
    direct === 0 ? null : `${direct} started with claude directly, not with a splice head`,
    unread === 0 ? null : `${unread} could not be read, so splice cannot tell which head started them`,
  ];
  return parts.filter((part) => part !== null).join('; ');
}

/** The address of a hand-off's other end: a sent edge carries the one its call used; a received
 *  edge carries the sender's session id, so its address is the one the registry holds for it. */
function peerAddressOf(rows: readonly SessionRow[], edge: SessionEdgesPayload['edges'][number]): string {
  if (edge.direction === 'out') return edge.to;
  return rows.find((row) => row.session_id === edge.from)?.address ?? S.absent;
}

type Edge = SessionEdgesPayload['edges'][number];

/** The opened session's hand-offs: which way each went, to whom, and when. */
function EdgeRows({ edges, rows }: { edges: SessionEdgesPayload | null; rows: readonly SessionRow[] }) {
  if (edges === null) return null;
  if (edges.edges.length === 0) return <Empty text="no hand-offs yet" source="a message this session sends to another, or gets from one, shows here" />;
  const columns: Column<Edge>[] = [
    { key: 'way', label: S.way, width: '16%', cell: (edge) => (edge.direction === 'out' ? S.sent : S.received) },
    { key: 'peer', label: S.peer, width: '34%', primary: true, cell: (edge) => peerLabel(rows, edge) },
    { key: 'address', label: S.address, width: '32%', mono: true, cell: (edge) => peerAddressOf(rows, edge) },
    { key: 'at', label: S.at, width: '18%', align: 'end', mono: true, cell: (edge) => timeAgo(edge.at) },
  ];
  return (
    <DataTable
      columns={columns}
      rows={[...edges.edges].sort((a, b) => b.at - a.at)}
      rowKey={(edge) => `${edge.from}:${edge.to}:${edge.at}`}
      label={S.handoffs}
    />
  );
}

/** Column widths for the board, by field key: the view with a head column gives it room from the
 *  name and project. Status always closes the row, at 10%. */
const WIDTH_WITHOUT_HEAD: Record<string, string> = { name: '26%', project: '22%', started: '16%', seen: '12%', peer: '14%' };
const WIDTH_WITH_HEAD: Record<string, string> = { name: '20%', head: '16%', project: '16%', started: '14%', seen: '10%', peer: '14%' };

/** The board, drawn from a payload. Exported so a test can hand it one. */
export function SessionsBoard({ payload, edges = null, boardEdges = null, edgesError = null, locked = false, error = null, lastRead = null, sample }: {
  payload: SessionsPayload | null;
  /** The OPENED session's edges, for its hand-offs bay. */
  edges?: SessionEdgesPayload | null;
  /** Every session's edges (GET /api/sessions/edges), for the peer column of every row. */
  boardEdges?: BoardEdgesPayload | null;
  /** A board edges read that failed, in the daemon's words. */
  edgesError?: string | null;
  locked?: boolean;
  error?: string | null;
  /** When the rows on screen were read, which the fault prints as stale while `error` stands. */
  lastRead?: number | null;
  /** True when a capture fixture is feeding this board, which the header prints. */
  /** The fixture's own file name when a fixture fed this board, undefined otherwise: the capture
   *  marker and the sample chrome are the same value, so they cannot disagree. */
  sample?: string | undefined;
}) {
  const { active } = useViews(PAGE_ID, DEFAULT_VIEWS);
  const [openId, setOpenId] = useState<string | null>(null);

  // A registration with no session id still has to be openable, and its key
  // must not collide with "nothing is open": `session_id === openId` would
  // match null against null and open the first id-less row on load (found in
  // the 2026-09-18 capture).
  const keyOf = (row: SessionRow): string => row.session_id ?? `pid:${row.pid ?? 0}`;

  const rows = payload?.sessions ?? [];
  const open = rows.find((row) => keyOf(row) === openId) ?? null;

  // The OPENED session's hand-offs bay reads its own edges route when it opens; the peer column of
  // every row comes from the one board-wide read (GET /api/sessions/edges, the route M2-02 asked
  // V4-130 for), so no row needs a request of its own.
  useEffect(() => {
    if (open?.session_id != null) void fetchSessionEdges(open.session_id);
  }, [open?.session_id]);

  const peerOf = (row: SessionRow): string | null => {
    if (boardEdges === null || row.session_id === null) return null;
    return latestPeer(rows, boardEdges.sessions[row.session_id] ?? []);
  };

  const selection = selectionOf(rows, active, Date.now());
  const by = groupByOf(active);
  const openLabel = by === 'repo' ? S.openProject : by === 'team' ? S.openTeam : S.openHead;
  const hueOf = useHues();
  const idleBuckets = selection.kind === 'timeline'
    ? selection.timeline.buckets.filter((bucket) => bucket.sessions.length === 0).length
    : 0;

  if (locked) return <Empty text="console locked" source="management key" />;
  if (error !== null && payload === null) return <Fault message={error} />;

  // THE COLUMNS ARE THE VIEW'S FIELDS, in its order, with the status closing the row the way an
  // airport board prints its remark last. A grouped view does not repeat its group as a column:
  // the group's own title row already names it.
  const order = active.fields;
  const widths = order.includes('head') ? WIDTH_WITH_HEAD : WIDTH_WITHOUT_HEAD;
  const columns: Column<SessionRow>[] = [
    ...order.flatMap((key): Column<SessionRow>[] => {
      const label = FIELD_LABEL[key];
      if (label === undefined) return [];
      const width = widths[key];
      const base = { key, label, ...(width === undefined ? {} : { width }) };
      if (key === 'head') return [{ ...base, cell: (row) => <HeadMark head={row.head}>{headText(row)}</HeadMark> }];
      return [{
        ...base,
        primary: key === 'name',
        mono: key === 'started' || key === 'seen',
        cell: (row) => fieldsOf(row, peerOf(row), [key])[0]?.value ?? S.absent,
      }];
    }),
    { key: 'status', label: S.status, width: '10%', cell: (row) => <Badge tone={toneOf(row)} quiet>{row.availability}</Badge> },
  ];

  const groupTitle = (key: string): ReactNode => {
    if (by === 'head' || by === null) {
      return key === UNKNOWN_HEAD ? <HeadMark head="" hue={0}>{S.noHead}</HeadMark> : <HeadMark head={key} />;
    }
    if (by === 'repo') return key === 'unattributed' ? key : baseOf(key);
    return key;
  };

  const groups: RowGroup<SessionRow>[] = selection.kind === 'groups'
    ? selection.groups.map((group) => {
      // The daemon's own word for a session it ties to no head. There is no head to open, and
      // `head: unknown head` read as a fault rather than a fact about how the session was started,
      // so the group says which and why.
      const headless = (by === 'head' || by === null) && group.key === UNKNOWN_HEAD;
      const byHead = by === 'head' || by === null;
      return {
        key: group.key,
        title: groupTitle(group.key),
        count: group.count,
        rows: group.rows,
        ...(byHead ? { hue: hueClass(headless ? 0 : hueOf(group.key)) } : {}),
        ...(headless
          ? { note: noHeadWhy(group.rows) }
          : { actions: <a href={groupHref(by)}>{openLabel}</a> }),
      };
    })
    : [
      ...selection.timeline.buckets
        .filter((bucket) => bucket.sessions.length > 0)
        .map((bucket) => ({
          key: String(bucket.start),
          title: `${pad(new Date(bucket.start).getHours())}:00`,
          count: bucket.sessions.length,
          rows: bucket.sessions,
        })),
      ...(selection.timeline.undated.length === 0
        ? []
        : [{ key: 'undated', title: S.undated, count: selection.timeline.undated.length, rows: selection.timeline.undated }]),
    ];

  const count = (state: SessionRow['availability']) => rows.filter((row) => row.availability === state).length;
  const stale = count('stale');

  return (
    <div className="myx-sx">
      <PageHeader
        title={S.title}
        actions={(
          <>
            {sample === undefined ? null : <Badge tone="neutral">{S.sample}</Badge>}
            <Reveal label={S.headless}>
              <p className="myx-sx-note">{payload?.note ?? S.registry}</p>
            </Reveal>
          </>
        )}
      >
        <ViewTabs pageId={PAGE_ID} defaults={DEFAULT_VIEWS} />
      </PageHeader>

      {rows.length === 0 ? null : (
        <div className="myx-sx-tally">
          <Tally
            items={[
              { label: S.live, value: count('live') },
              { label: S.stale, value: stale, ...(stale > 0 ? { tone: 'warn' as const } : {}) },
              { label: S.gone, value: count('gone') },
            ]}
          />
          {selection.kind === 'timeline' ? (
            <p className="myx-sx-window">
              {selection.window.hours}h {S.window}, {idleBuckets} {S.idle}
            </p>
          ) : null}
        </div>
      )}

      {/* The capture marker (law 23): set on the same DEV branch as the fixture import and
          carrying that fixture's own file name, so a driver asserts "the fixture loaded" instead of
          inferring it. The guard is IN the expression, so a production build drops the branch and
          the attribute's very name - fixture-leak.mjs asserts it is absent from dist. */}
      <div
        className={open === null ? 'myx-sx-board' : 'myx-sx-board myx-sx-board-open'}
        {...(import.meta.env.DEV && sample !== undefined ? { 'data-sample': sample } : {})}
      >
        <div className="myx-sx-list">
          {/* A registry read that fails after one landed keeps the rows and says so: the fault used
              to show only while nothing had loaded, so a dead daemon's sessions read as live. */}
          {error === null ? null : <Fault message={error} lastRead={lastRead} />}
          {/* An edges read that failed leaves every peer unknown, and says why: unwatched is not
              the same fact as "no hand-offs". */}
          {edgesError === null ? null : <Fault message={edgesError} />}
          {rows.length === 0 ? (
            <Empty text="no sessions in flight" source="start claude code through a splice head and it lands here" />
          ) : (
            <DataTable
              className="myx-sx-table"
              columns={columns}
              groups={groups}
              rowKey={keyOf}
              label={S.title}
              onOpen={(row) => setOpenId(keyOf(row))}
              openLabel={(row) => `${S.title} ${sessionLabel(row)}`}
              selectedKey={openId}
              rowTone={(row) => (row.availability === 'stale' ? 'warn' : null)}
              rowHue={(row) => hueClass(hueOf(row.head))}
            />
          )}
        </div>

        {/* THE DETAIL IS UNMOUNTED AT REST: nothing holds a column until a row is opened, and an
            empty labelled <aside> would still be a landmark in a reader's list (M1-123). */}
        {open === null ? null : (
          <DetailPanel
            title={sessionLabel(open)}
            label={S.detail}
            status={<Badge tone={toneOf(open)} quiet>{open.availability}</Badge>}
            onClose={() => setOpenId(null)}
            closeLabel={S.close}
          >
            <Section title={S.conversation}>
              {open.session_id === null ? (
                <Empty text="this registration carries no session id" source="session registry" />
              ) : (
                <Conversation sessionId={open.session_id} />
              )}
            </Section>
            <Section title={S.files}>
              {projectKeyOf(open) === null ? (
                <Empty text="this registration carries no cwd" source="session registry" />
              ) : (
                <FileView projectId={projectKeyOf(open) ?? ''} />
              )}
            </Section>
            <Section title={S.handoffs}>
              <EdgeRows edges={edges} rows={rows} />
            </Section>
          </DetailPanel>
        )}
      </div>
    </div>
  );
}

/** The DEV-only sample board a capture reads (CONTRACTS.md section 4, the fixture rule). */
/** One fixture, as ONE value: the name it was asked for and the bytes that arrived. The capture
 *  marker is set from this and from nothing else, so a name with no module can never leave a
 *  marker behind - a marker that survives a failed import says the opposite of the truth (law 23:
 *  an instrument must be able to distinguish PASSED, FAILED and DID NOT RUN). Exported because a
 *  test pins exactly that, with a name that resolves to no file at all. */
export async function loadFixture(name: string): Promise<{ name: string; payload: SessionsPayload } | null> {
  if (!import.meta.env.DEV) return null;
  const module = await import(/* @vite-ignore */ `./fixtures/${name}.ts`)
    .then((loaded: { fixture?: SessionsPayload }) => loaded)
    .catch(() => null);
  const payload = module === null ? null : module.fixture ?? null;
  return payload === null ? null : { name, payload };
}

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
  const boardEdges = useBoardEdges((s) => s);
  const [sample, setSample] = useState<{ name: string; payload: SessionsPayload } | null>(null);
  const name = fixtureName();
  const fixture = sample === null ? null : sample.payload;

  useEffect(() => {
    const stops = [startSessionsPolling(5000), startBoardEdgesPolling(5000)];
    return () => stops.forEach((stop) => stop());
  }, []);

  // The fixture is imported by name at runtime, never bundled: the shipped dist
  // carries no sample bytes, and the branch is dead outside DEV.
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
    // marker this row exists to prevent. A missing fixture is not a page error: the live read
    // stands, and the page carries no marker.
    void loadFixture(name).then((loaded) => {
      if (live) setSample(loaded);
    });
    return () => {
      live = false;
    };
  }, [name]);

  return (
    <SessionsBoard
      payload={fixture ?? registry.data}
      edges={edges.data}
      // Live edges never join a sample's rows: a capture's peers would be another board's.
      boardEdges={fixture === null ? boardEdges.data : null}
      edgesError={fixture === null ? boardEdges.error : null}
      locked={locked}
      error={fixture === null ? registry.error : null}
      lastRead={registry.lastUpdated}
      sample={sample?.name}
    />
  );
}
