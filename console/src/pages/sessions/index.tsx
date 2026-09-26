// The sessions page. Its default view is the LANES (operator ruling 4, item 5): one strand per head
// in its hue, each session a card on its head's strand, each hand-off an arc from sender to receiver
// (shared/ui/lanes.tsx). Behind it stands the departure board (docs/design/DESIGN.md section 7): one
// row per session with fixed columns and the status printed last, grouped under four saved views (by
// head, by project, by team, and a timeline). Opening a card or a row puts its detail beside the
// board; the board stays.
//
// WHAT THE ROW SAYS, shown before it is said (DESIGN.md section 3). A session's life is one bar on
// the board's shared time axis (started, last heard from, now), so the rows read as a timeline and a
// session gone quiet shows as a trail rather than a timestamp to subtract. Its last hand-off is an
// arrow in the peer's head colour that opens the peer. The status cell carries availability (ok
// live, warn stale, neutral gone) and ALWAYS prints the word beside its dot, so a greyscale
// screenshot still says which session stopped reporting; a stale row is also tinted, because it is
// the one that needs the operator. A gone session is not struck: its transcript and perf rows stay.
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
import { ArrowLeftIcon } from '@phosphor-icons/react/dist/csr/ArrowLeft';
import { ArrowRightIcon } from '@phosphor-icons/react/dist/csr/ArrowRight';
import { ArrowUpRightIcon } from '@phosphor-icons/react/dist/csr/ArrowUpRight';
import { HeadlessMark, HeadMark, hueClass, NO_SPLICE_HEAD, NO_SPLICE_HEAD_WHY, useControlStatus, useHues } from '@entities/control-status';
import { useHeads } from '@entities/heads';
import {
  fetchResumeRecipe,
  fetchSessionEdges,
  peerLabel,
  sessionKey,
  sessionLabel,
  startBoardEdgesPolling,
  startSessionsPolling,
  useBoardEdges,
  useSession,
  useSessionEdges,
  useSessionRegistry,
  UNKNOWN_HEAD,
} from '@entities/session';
import type { BoardEdgesPayload, ResumeRecipe, SessionEdgesPayload, SessionRow, SessionsPayload } from '@entities/session';
import { Conversation } from '@widgets/conversation';
import { FileView } from '@widgets/file-view';
import { Badge, DataTable, DetailPanel, Empty, InfoTip, KeyValue, Lanes, LifetimeBar, PageHeader, Reveal, Section, StackedBar } from '@shared/ui';
import type { Column, Lane, LaneMessage, RowGroup } from '@shared/ui';
import { Choice, Copy, Fault } from '@shared/controls';
import { readFor, timeAgo, useLinkedId, useOpen } from '@shared/lib';
import type { Keyed } from '@shared/lib';
import { H, S, U } from './strings';
import { groupByOf, groupHref, isLanes, lanesOf, selectionOf, titleOf } from './select';
import { baseOf, boardFields, FIELD_LABEL, fieldsOf, fleetHandoffs, headText, peerOf, projectKeyOf, projectText, sendCall, startedText, toneOf } from './strip';
import type { Handoff, Peer } from './strip';
import './sessions.css';

const PAGE_ID = 'sessions';

/** The views this page ships. The lanes are the default and stand first; they arrived after the
 *  board's four, so a browser that saved its views before is offered them once. */
export const DEFAULT_VIEWS: View[] = [
  { id: 'lanes', name: S.lanes, layout: 'lanes', filter: {}, sort: null, group: 'head', fields: [], introduced: '2026-09-25' },
  // A grouped view does not repeat its group as a column: the bay's own label already names it.
  { id: 'by-head', name: 'By head', layout: 'rack', filter: {}, sort: null, group: 'head', fields: ['name', 'project', 'life', 'peer'] },
  { id: 'by-project', name: 'By project', layout: 'rack', filter: {}, sort: null, group: 'repo', fields: ['name', 'head', 'life', 'peer'] },
  { id: 'by-team', name: 'By team', layout: 'rack', filter: {}, sort: null, group: 'team', fields: ['name', 'head', 'project', 'life', 'peer'] },
  { id: 'timeline', name: 'Timeline', layout: 'timeline', filter: { window: '24h', bucket: '1h' }, sort: null, group: null, fields: ['name', 'head', 'project', 'life', 'peer'] },
];

/** The daemon's availability word, as the board prints it. */
const AVAILABILITY: Record<SessionRow['availability'], string> = { live: S.live, stale: S.stale, gone: S.gone };

/** Why a session has no head: splice did not start it, or the daemon could not read how it was
 *  started. It says only what the registry knows: where a turn goes is the turns page's fact. */
export const NO_HEAD_WHY = NO_SPLICE_HEAD_WHY;

/** Why a group of sessions has no head, for its tip, from each row's route when the daemon reports
 *  one: a session started with `claude` directly skips splice; one whose environment could not be
 *  read may not. A daemon that reports no route keeps the sentence above, which covers both. */
export function noHeadWhy(rows: readonly SessionRow[]): string {
  const direct = rows.filter((row) => row.route === 'direct').length;
  const unread = rows.filter((row) => row.route === 'unknown').length;
  if (direct + unread === 0) return NO_HEAD_WHY;
  const parts = [
    direct === 0 ? null : `${direct} ${U.direct}`,
    unread === 0 ? null : `${unread} ${U.unread}`,
  ];
  return parts.filter((part) => part !== null).join(', ');
}

/** The hand-off cell: an arrow pointing the way the message went, in the peer's head colour, and
 *  the peer's name. A peer that is a registered session opens it; one that is not (a session gone
 *  since) prints its label and nothing to open. */
function PeerCell({ peer, hue, onOpen }: { peer: Peer | null; hue: string; onOpen: (row: SessionRow) => void }) {
  if (peer === null) return <>{S.absent}</>;
  const arrow = peer.direction === 'out'
    ? <ArrowRightIcon className="myx-sx-arrow" aria-hidden="true" />
    : <ArrowLeftIcon className="myx-sx-arrow" aria-hidden="true" />;
  const said = `${peer.direction === 'out' ? S.sent : S.received} ${peer.label} ${timeAgo(peer.at)}`;
  if (peer.row === null) return <span className={`myx-sx-peer ${hue}`} aria-label={said}>{arrow}{peer.label}</span>;
  const row = peer.row;
  return (
    <button type="button" className={`myx-sx-peer ${hue}`} aria-label={said} onClick={() => onOpen(row)}>
      {arrow}
      <span>{peer.label}</span>
    </button>
  );
}

/** The board's shared time axis: from the oldest session's start, up to a day back, to now. A
 *  session older than the window starts at its edge. */
export function axisOf(rows: readonly SessionRow[], now: number): { from: number; to: number } {
  const DAY = 86_400_000;
  const starts = rows.map((row) => row.started_at).filter((at): at is number => at !== null);
  const oldest = starts.length === 0 ? now - 3_600_000 : Math.min(...starts);
  return { from: Math.max(oldest, now - DAY), to: now };
}

/** The address of a hand-off's other end: a sent edge carries the one its call used; a received
 *  edge carries the sender's session id, so its address is the one the registry holds for it. */
function peerAddressOf(rows: readonly SessionRow[], edge: SessionEdgesPayload['edges'][number]): string {
  if (edge.direction === 'out') return edge.to;
  return rows.find((row) => row.session_id === edge.from)?.address ?? S.absent;
}

type Edge = SessionEdgesPayload['edges'][number];

/** What a hand-off said (V4-314): its text behind a reveal, with the transcript it was read from, or
 *  why the daemon found none. The text is out of the document until asked for, as a chat line's is. */
export function HandoffText({ edge }: { edge: Edge }) {
  if (edge.text === null) {
    return (
      <span className="myx-sx-unread">
        {S.notRead}
        <InfoTip text={edge.missing_reason ?? S.absent} label={S.whyNotRead} />
      </span>
    );
  }
  return (
    <Reveal label={S.showMessage}>
      <p className="myx-sx-said">{edge.text}</p>
      <span className="myx-sx-said-from">{edge.text_source}</span>
    </Reveal>
  );
}

/** The opened session's hand-offs: which way each went, to whom, when, and what it said. */
function EdgeRows({ edges, rows }: { edges: SessionEdgesPayload | null; rows: readonly SessionRow[] }) {
  if (edges === null) return null;
  if (edges.edges.length === 0) return <Empty text={S.noHandoffs} />;
  const columns: Column<Edge>[] = [
    { key: 'way', label: S.way, width: '16%', cell: (edge) => (edge.direction === 'out' ? S.sent : S.received) },
    { key: 'peer', label: S.peer, width: '22%', primary: true, cell: (edge) => peerLabel(rows, edge) },
    // The address is what a peer is reached at: read whole, so it wraps in a narrow panel.
    { key: 'address', label: S.address, width: '24%', mono: true, wrap: true, cell: (edge) => peerAddressOf(rows, edge) },
    { key: 'said', label: S.message, width: '24%', wrap: true, cell: (edge) => <HandoffText edge={edge} /> },
    { key: 'at', label: S.at, width: '14%', align: 'end', mono: true, cell: (edge) => timeAgo(edge.at) },
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

/** The call that messages the opened session from another one (V4-321), with its copy key. Splice
 *  never writes to a session, so the operator pastes it; a session not live may never answer. */
function SendCall({ row }: { row: SessionRow }) {
  const call = sendCall(row);
  if (call === null) return <Empty text={S.notLive} source={H.notLive} />;
  return (
    <div className="myx-sx-send">
      <code className="myx-sx-call">{call}</code>
      <Copy value={call} />
    </div>
  );
}

/** A word the shell reads as itself, else single-quoted: the copied line runs the argv the daemon
 *  answered, whatever a head's command is called. */
export function shellWord(word: string): string {
  return /^[\w./=-]+$/.test(word) ? word : `'${word.replaceAll("'", `'\\''`)}'`;
}

/** What resuming on the picked head does (V4-320): the command with its copy key, then the launch's
 *  own resolution of it. Exported so a test can hand it a recipe. */
export function ResumeRecipeView({ recipe }: { recipe: ResumeRecipe }) {
  const line = recipe.argv.map(shellWord).join(' ');
  const original = recipe.live === null ? S.absent : recipe.live ? (
    <>
      <Badge tone="warn" quiet>{S.stillLive}</Badge>
      <InfoTip text={H.stillLive} label={S.stillLive} />
    </>
  ) : S.notRunning;
  return (
    <>
      <div className="myx-sx-send">
        <code className="myx-sx-call">{line}</code>
        <Copy value={line} />
      </div>
      <KeyValue
        rows={[
          [S.transcript, recipe.from],
          [S.landsIn, recipe.copies ? recipe.to_tree : S.inPlace],
          [S.model, recipe.model],
          [S.original, original],
        ]}
      />
    </>
  );
}

/** The head the resume offers before the operator picks one: the first that is not the session's own,
 *  since resuming where it runs is what the session already does; its own when it is the only one. */
export function firstOtherHead(keys: readonly string[], own: string): string | null {
  return keys.find((key) => key !== own) ?? keys[0] ?? null;
}

/** The opened session resumed on a head the operator picks, first any head but its own. Splice
 *  starts no client, so the answer is the command and what its launch will do; the page keys this by
 *  session, so a pick never outlives the session it was made for. */
function ResumeElsewhere({ sessionId, own }: { sessionId: string; own: string }) {
  const heads = useHeads((state) => state.data);
  const [picked, setPicked] = useState<string | null>(null);
  const [answer, setAnswer] = useState<{ head: string; recipe: ResumeRecipe | null; fault: string | null } | null>(null);
  const keys = (heads ?? []).map((head) => head.key);
  const head = picked ?? firstOtherHead(keys, own);

  useEffect(() => {
    if (head === null) return undefined;
    let current = true;
    void fetchResumeRecipe(sessionId, head)
      .then((recipe) => ({ head, recipe, fault: null }))
      .catch((err: unknown) => ({ head, recipe: null, fault: err instanceof Error ? err.message : String(err) }))
      .then((read) => {
        if (current) setAnswer(read);
      });
    return () => {
      current = false;
    };
  }, [sessionId, head]);

  if (head === null) return <Empty text={S.noHeads} />;
  const shown = answer?.head === head ? answer : null;
  return (
    <div className="myx-sx-resume">
      <Choice label={S.resumeOn} value={head} options={(heads ?? []).map((row) => ({ value: row.key, label: row.label }))} onChange={setPicked} />
      {shown?.fault == null ? null : <Fault message={shown.fault} />}
      {shown?.recipe == null ? null : <ResumeRecipeView recipe={shown.recipe} />}
    </div>
  );
}

/** The newest hand-offs the fleet's list draws: past a screen of them the lanes carry the story. */
const HANDOFF_ROWS = 12;

/** One end of a hand-off: the session under its head's mark, or the words the edge carried. */
function HandoffEnd({ row, label }: { row: SessionRow | null; label: string }) {
  return row === null ? <>{label}</> : <HeadMark head={row.head}>{label}</HeadMark>;
}

/** The fleet's hand-offs, newest first: which session messaged which, and when. Beside the board
 *  on a wide screen, under it on a narrow one (sessions.css). */
function FleetHandoffs({ rows, board }: { rows: readonly SessionRow[]; board: BoardEdgesPayload }) {
  const handoffs = fleetHandoffs(rows, board);
  const columns: Column<Handoff>[] = [
    // No widths: three short facts a row, so the table is as wide as they are (sessions.css).
    { key: 'from', label: S.from, cell: (handoff) => <HandoffEnd row={handoff.from} label={handoff.fromLabel} /> },
    { key: 'to', label: S.to, primary: true, cell: (handoff) => <HandoffEnd row={handoff.to} label={handoff.toLabel} /> },
    { key: 'at', label: S.at, align: 'end', mono: true, cell: (handoff) => timeAgo(handoff.at) },
  ];
  return (
    <Section title={S.handoffs} count={handoffs.length} className="myx-sx-handoffs">
      {handoffs.length === 0 ? (
        <Empty text={S.noHandoffs} />
      ) : (
        <DataTable columns={columns} rows={handoffs.slice(0, HANDOFF_ROWS)} rowKey={(handoff) => handoff.key} label={S.handoffs} />
      )}
    </Section>
  );
}

/** Column widths for the board, by field key: the view with a head column gives it room from the
 *  name, project and lifetime. Status always closes the row, at 10%. */
const WIDTH_WITHOUT_HEAD: Record<string, string> = { name: '20%', project: '18%', life: '32%', peer: '20%' };
const WIDTH_WITH_HEAD: Record<string, string> = { name: '16%', head: '14%', project: '16%', life: '24%', peer: '20%' };

/** The board, drawn from a payload. Exported so a test can hand it one. */
export function SessionsBoard({ payload, view, linked = null, edges = null, boardEdges = null, edgesError = null, locked = false, error = null, lastRead = null, sample }: {
  payload: SessionsPayload | null;
  /** The view to draw; the page's active saved view when omitted. A test names the one it is about. */
  view?: View;
  /** The session a link asks to open (`?open=<session key>`), read by the page. */
  linked?: string | null;
  /** The session edges reads, by session id: the OPENED session's are its hand-offs bay. */
  edges?: Keyed<string, SessionEdgesPayload> | null;
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
  const { active: saved } = useViews(PAGE_ID, DEFAULT_VIEWS);
  const active = view ?? saved;
  const [openId, setOpenId] = useOpen(linked);

  const rows = payload?.sessions ?? [];
  const open = rows.find((row) => sessionKey(row) === openId) ?? null;

  // The OPENED session's hand-offs bay reads its own edges route when it opens; the peer column of
  // every row comes from the one board-wide read (GET /api/sessions/edges, the route M2-02 asked
  // V4-130 for), so no row needs a request of its own.
  useEffect(() => {
    if (open?.session_id != null) void fetchSessionEdges(open.session_id);
  }, [open?.session_id]);

  const handoffs = open?.session_id == null || edges === null ? null : readFor(edges, open.session_id);

  const peerFor = (row: SessionRow): Peer | null => {
    if (boardEdges === null || row.session_id === null) return null;
    return peerOf(rows, boardEdges.sessions[row.session_id] ?? []);
  };
  const now = Date.now();
  const axis = axisOf(rows, now);

  const selection = selectionOf(rows, active, Date.now());
  const by = groupByOf(active);
  const openLabel = by === 'repo' ? S.openProject : by === 'team' ? S.openTeam : S.openHead;
  const hueOf = useHues();
  const registry = useControlStatus((state) => state.data?.registry ?? null);
  const idleBuckets = selection.kind === 'timeline'
    ? selection.timeline.buckets.filter((bucket) => bucket.sessions.length === 0).length
    : 0;

  if (locked) return <Empty text={S.locked} />;
  if (error !== null && payload === null) return <Fault message={error} />;

  // THE COLUMNS ARE THE VIEW'S FIELDS, in its order, with the status closing the row the way an
  // airport board prints its remark last. A grouped view does not repeat its group as a column:
  // the group's own title row already names it.
  const order = boardFields(active.fields);
  const widths = order.includes('head') ? WIDTH_WITH_HEAD : WIDTH_WITHOUT_HEAD;
  const columns: Column<SessionRow>[] = [
    ...order.flatMap((key): Column<SessionRow>[] => {
      const label = FIELD_LABEL[key];
      if (label === undefined) return [];
      const width = widths[key];
      const base = { key, label, ...(width === undefined ? {} : { width }) };
      if (key === 'head') return [{ ...base, cell: (row) => <HeadMark head={row.head}>{headText(row)}</HeadMark> }];
      if (key === 'life') {
        return [{
          ...base,
          cell: (row) => {
            const started = startedText(row);
            const seen = fieldsOf(row, null, ['seen'])[0]?.value ?? S.absent;
            if (row.started_at === null) return S.absent;
            return (
              <LifetimeBar
                start={row.started_at}
                seen={row.updated_at ?? row.started_at}
                now={now}
                from={axis.from}
                to={axis.to}
                label={`${S.started} ${started ?? S.absent}, ${S.seen.toLowerCase()} ${seen}`}
              />
            );
          },
        }];
      }
      if (key === 'peer') {
        return [{
          ...base,
          cell: (row) => {
            const peer = peerFor(row);
            const peerHue = peer?.row == null ? hueClass(0) : hueClass(hueOf(peer.row.head));
            return <PeerCell peer={peer} hue={peerHue} onOpen={(target) => setOpenId(sessionKey(target))} />;
          },
        }];
      }
      return [{
        ...base,
        primary: key === 'name',
        cell: (row) => fieldsOf(row, null, [key])[0]?.value ?? S.absent,
      }];
    }),
    { key: 'status', label: S.status, width: '10%', cell: (row) => <Badge tone={toneOf(row)} quiet>{AVAILABILITY[row.availability]}</Badge> },
  ];

  const groupTitle = (key: string): ReactNode => {
    if (by === 'head' || by === null) {
      if (key !== UNKNOWN_HEAD) return <HeadMark head={key} />;
      const headless = rows.filter((row) => row.head === UNKNOWN_HEAD);
      return <HeadlessMark why={noHeadWhy(headless)} />;
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
          ? {}
          : { actions: <a className="myx-sx-go" href={groupHref(by)} aria-label={openLabel}><ArrowUpRightIcon aria-hidden="true" /></a> }),
      };
    })
    : [
      ...selection.timeline.buckets
        .filter((bucket) => bucket.sessions.length > 0)
        .map((bucket) => ({
          key: String(bucket.start),
          title: titleOf(bucket),
          count: bucket.sessions.length,
          rows: bucket.sessions,
        })),
      ...(selection.timeline.undated.length === 0
        ? []
        : [{ key: 'undated', title: S.undated, count: selection.timeline.undated.length, rows: selection.timeline.undated }]),
    ];

  // THE LANES: every head the daemon runs, each a strand with its sessions on it, and the fleet's
  // hand-offs as arcs between the cards of the two sessions a message joined.
  const lanes: Lane[] = lanesOf(rows, registry?.map((entry) => entry.key) ?? []).map((group) => {
    const headless = group.key === UNKNOWN_HEAD;
    return {
      key: group.key,
      name: headless ? NO_SPLICE_HEAD : registry?.find((entry) => entry.key === group.key)?.label ?? group.key,
      title: groupTitle(group.key),
      hue: hueClass(headless ? 0 : hueOf(group.key)),
      cards: group.rows.map((row) => ({
        key: sessionKey(row),
        title: sessionLabel(row),
        meta: projectText(row),
        tone: toneOf(row),
        word: AVAILABILITY[row.availability],
        start: row.started_at,
      })),
    };
  });
  // Hand-offs not read yet are null, not none: the lanes take no read of them until they are.
  const messages: LaneMessage[] | null = boardEdges === null
    ? null
    : fleetHandoffs(rows, boardEdges).flatMap((handoff) => (
      handoff.from === null || handoff.to === null ? [] : [{ from: sessionKey(handoff.from), to: sessionKey(handoff.to), at: handoff.at }]
    ));

  const count = (state: SessionRow['availability']) => rows.filter((row) => row.availability === state).length;
  const stale = count('stale');

  return (
    <div className="myx-sx">
      <PageHeader
        title={S.title}
        info={{ text: payload?.note ?? H.registry, label: S.about }}
        actions={sample === undefined ? undefined : <Badge tone="neutral">{S.sample}</Badge>}
      >
        <ViewTabs pageId={PAGE_ID} defaults={DEFAULT_VIEWS} />
      </PageHeader>

      {rows.length === 0 ? null : (
        <div className="myx-sx-tally">
          <StackedBar
            label={S.availability}
            legend
            parts={[
              { key: 'live', label: S.live, value: count('live'), mark: 'ok' },
              { key: 'stale', label: S.stale, value: stale, mark: 'warn' },
              { key: 'gone', label: S.gone, value: count('gone'), mark: 'series-3' },
            ]}
          />
          {selection.kind === 'timeline' ? (
            <p className="myx-sx-window">
              {selection.window.hours}{U.hours} {U.window}, {idleBuckets} {U.idle}
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
            <Empty text={S.noSessions} source={H.noSessions} />
          ) : isLanes(active) ? (
            <Lanes
              lanes={lanes}
              messages={messages}
              label={S.title}
              open={(key) => setOpenId(key === openId ? null : key)}
              selected={openId}
              cardLabel={(card) => `${card.title}, ${card.word}`}
            />
          ) : (
            <DataTable
              className="myx-sx-table"
              columns={columns}
              groups={groups}
              rowKey={sessionKey}
              label={S.title}
              onOpen={(row) => setOpenId(sessionKey(row))}
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
            status={<Badge tone={toneOf(open)} quiet>{AVAILABILITY[open.availability]}</Badge>}
            onClose={() => setOpenId(null)}
            closeLabel={S.close}
          >
            <Section title={S.conversation}>
              {open.session_id === null ? (
                <Empty text={S.noSessionId} />
              ) : (
                <Conversation sessionId={open.session_id} />
              )}
            </Section>
            <Section title={S.files}>
              {projectKeyOf(open) === null ? (
                <Empty text={S.noCwd} />
              ) : (
                <FileView projectId={projectKeyOf(open) ?? ''} />
              )}
            </Section>
            <Section title={S.handoffs}>
              {handoffs?.error == null ? null : <Fault message={handoffs.error} />}
              <EdgeRows edges={handoffs?.data ?? null} rows={rows} />
            </Section>
            <Section title={S.sendTo} info={{ text: H.sendTo, label: S.sendWhy }}>
              <SendCall row={open} />
            </Section>
            <Section title={S.resume} info={{ text: H.resume, label: S.resumeWhy }}>
              {open.session_id === null ? (
                <Empty text={S.noSessionId} />
              ) : (
                <ResumeElsewhere key={open.session_id} sessionId={open.session_id} own={open.head} />
              )}
            </Section>
          </DetailPanel>
        )}
      </div>

      {boardEdges === null || rows.length === 0 ? null : <FleetHandoffs rows={rows} board={boardEdges} />}
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
  const linked = useLinkedId();
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
      linked={linked}
      edges={edges}
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
