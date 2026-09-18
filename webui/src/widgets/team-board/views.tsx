// The board's other two views: the same team read by ROLE (comp team-board-b) and as the TIMELINE
// of its day (comp team-board-c). Both are the hero's world — the same strips, the same bays, the
// same palette — composed against the geometry measured off their own comps.
//
// THE BAY GEOMETRY IS MEASURED, NOT INVENTED. A column scan of team-board-b over its two
// strip-free bands (y300-410 and y810-920, which agree to under 4px of vertical deviation) finds
// six uprights at x138-148 / 455-466 (lead), 474-485 / 814-825 (builder) and 833-845 / 1036-1046
// (reviewer), with 7px gutters between the bays and the right column starting at x1053. Over the
// board's own box (x138.24-1536, y59.4-1024) that is the track list in ROLE_TRACKS and the
// positions in views.css.
//
// THE SESSION STRIP WRAPS, and the numbers are why (reported to the orchestrator and approved on
// row M2-08 before this was written). Measured by cloning a live board field so the condensed face
// applies: the six column headers the board declares total 240.2px, the widest row of values
// 471.7px — against bay interiors of 305, 328 and 190px. There is no apportionment of 328px that
// holds 471.7px, so a field keeps max(header, value) and the strip breaks onto another line rather
// than a header being cut or the type being shrunk. The comp does the same thing: it drops the
// builder's `state` onto a second line.
import type { ReactNode } from 'react';
import { Bay, Empty, Figure, HolderEdge, ScopeInset, Strip, StripField } from '@shared/ui';
import type { TeamMemberRow, TeamPayload } from '@entities/team';
import { BoardFooter, BoardHeader, MessageStrip } from './parts';
import {
  JOIN_PREFIX, costPerRole, focusMember, groupByRole, lastReceived, roleRows, slotName, timeRule,
  timelineRows, turnsPerMember,
} from './model';
import type { TeamTurn, TeamViewData } from './model';
import { S } from './strings';
import './views.css';

/** The three bay widths of team-board-b as a grid track list, in the comp's own proportions. A
 *  team with another number of roles has no measured geometry, so its bays share the rack
 *  equally rather than borrowing numbers measured for three. */
const ROLE_TRACKS = '23.538fr 25.183fr 15.310fr';
const tracksFor = (bays: number): string => (bays === 3 ? ROLE_TRACKS : `repeat(${Math.max(bays, 1)}, 1fr)`);

const thousand = (value: number): string => value.toLocaleString('en-US');

/** A strip whose fields keep their own width and wrap. Every field prints its header and its
 *  value whole; the strip is as tall as the bay makes it. */
function WrapStrip({ edge, ariaLabel, className, children }: {
  edge: 'green' | 'grey' | 'amber' | 'red';
  ariaLabel: string;
  className?: string;
  children: ReactNode;
}) {
  return (
    <Strip className={`myx-wrap-strip ${className ?? ''}`} edge={edge} edgeLabel="" ariaLabel={ariaLabel}>
      {children}
    </Strip>
  );
}

/** The session strip of the by-role board: the six fields comp-b prints on one line, wrapped. */
function SessionStrip({ member }: { member: TeamMemberRow }) {
  return (
    <WrapStrip edge={member.role === 'lead' ? 'green' : 'grey'} ariaLabel={`${member.name} session`}>
      <StripField w={0} fixed label={S.session} value={member.name} mono={false} />
      <StripField w={0} fixed label={S.head} value={member.head} mono={false} />
      <StripField w={0} fixed label={S.model} value={member.model ?? 'n/r'} mono={false} />
      <StripField w={0} fixed label={S.account} value={member.account ?? 'n/r'} mono={false} />
      {/* A window no provider reports is named as that, never as a zero or a dash. */}
      <StripField w={0} fixed label={S.window} value={member.window ?? 'not reported by provider'} mono={false} />
      <StripField w={0} fixed label={S.state} value={member.state} mono={false} />
    </WrapStrip>
  );
}

/** The right column's member card: who the team is waiting on, field by field. */
function MemberCard({ board, member }: { board: TeamPayload; member: TeamMemberRow }) {
  const slot = board.team.slots.find((s) => s.session === member.name);
  const rows: [string, string][] = [
    [S.name, member.name],
    [S.head, member.head],
    [S.model, member.model ?? 'n/r'],
    [S.repo, board.team.repo],
    [S.boundSlot, slot?.role ?? 'none'],
    [S.window, member.window ?? 'not reported by provider'],
    [S.lastMessage, lastReceived(board, member.name) ?? 'none'],
  ];
  return (
    <div className="myx-board-card" aria-label={member.name}>
      <HolderEdge state={member.role === 'lead' ? 'green' : 'grey'} label="" />
      <dl className="myx-board-card-rows">
        {rows.map(([label, value]) => (
          <div className="myx-board-card-row" key={label}>
            <dt>{label}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}

/** Turns in flight over the last hour, as the daemon sampled them. A step line, because the count
 *  holds between samples rather than sliding between them. */
function TurnsChart({ data }: { data: TeamViewData | null }) {
  if (data === null || data.lastHour.length === 0) {
    return (
      <ScopeInset title={`${S.turns} over the last hour`} basis="unavailable">
        <Empty text="no turn history yet" source="V4-131 pending" />
      </ScopeInset>
    );
  }
  const peak = Math.max(15, ...data.lastHour.map((point) => point.turns));
  const top = Math.ceil(peak / 15) * 15;
  const step = 100 / Math.max(data.lastHour.length - 1, 1);
  const points = data.lastHour
    .flatMap((point, index) => {
      const y = 100 - (point.turns / top) * 100;
      // Two points per sample: the count holds to the next sample and then steps.
      return index === 0 ? [[0, y]] : [[index * step, 100 - (data.lastHour[index - 1].turns / top) * 100], [index * step, y]];
    })
    .map(([x, y]) => `${x.toFixed(2)},${y.toFixed(2)}`)
    .join(' ');
  const first = data.lastHour[0].at;
  const last = data.lastHour[data.lastHour.length - 1].at;
  return (
    <ScopeInset title={`${S.turns} over the last hour`} basis="measured">
      <div className="myx-turns-chart">
        <span className="myx-turns-top">{top}</span>
        <svg viewBox="0 0 100 100" preserveAspectRatio="none" role="img" aria-label={`${S.turns} over the last hour`}>
          <polyline className="myx-turns-line" points={points} vectorEffect="non-scaling-stroke" />
        </svg>
        <span className="myx-turns-zero">0</span>
        <span className="myx-turns-from">{first}</span>
        <span className="myx-turns-to">{last}</span>
      </div>
    </ScopeInset>
  );
}

/** The by-role board: one bay per role the team DECLARES, hand-offs crossing them in time order,
 *  and the rule of the hour along the foot. */
export function TeamBoardByRole({ board, data = null, chat, feed }: {
  board: TeamPayload;
  data?: TeamViewData | null;
  chat?: ReactNode;
  feed?: ReactNode;
}) {
  const bays = groupByRole(board);
  const events = roleRows(board, bays);
  const rows = events.reduce((most, event) => Math.max(most, event.row + 1), 0);
  const marks = data === null ? [] : timeRule(board.messages, data.now);
  const focus = focusMember(board);

  return (
    <section className="myx-board myx-board-role" aria-label={S.board}>
      <BoardHeader board={board} />

      <div
        className="myx-role-rack"
        // The first row is the bay's own title plate, which the bay draws at its top: as `auto` it
        // collapsed to nothing (no item is placed in it) and the first session strip covered the
        // plate. 5.5cqh is the plate's own height on the comp, 53px of its 964.6px board.
        style={{ gridTemplateColumns: tracksFor(bays.length), gridTemplateRows: `5.5cqh repeat(${Math.max(rows, 1)}, 1fr) auto` }}
      >
        {bays.map((bay, index) => (
          <Bay
            key={bay.role}
            className="myx-role-bay"
            label={bay.role}
            style={{ gridColumn: index + 1, gridRow: '1 / -1' }}
          >
            {/* A bay with nobody in it says so in the words the comp prints, and names the head
                the open slot declares — which is what the operator would launch. */}
            {bay.members.length === 0 ? (
              <p className="myx-role-open">
                {bay.open === null ? 'no session bound' : `no session bound, launch one: ${bay.open.head}`}
              </p>
            ) : null}
          </Bay>
        ))}

        {events.map((event) => (event.kind === 'session' ? (
          <div
            className="myx-role-cell"
            key={`session-${event.member.name}`}
            style={{ gridColumn: event.column + 1, gridRow: event.row + 2 }}
          >
            <SessionStrip member={event.member} />
          </div>
        ) : (
          <div
            className="myx-role-cell myx-role-cross"
            key={`msg-${event.message.time}-${event.message.from}`}
            style={{ gridColumn: `${event.from + 1} / ${event.to + 2}`, gridRow: event.row + 2 }}
          >
            <MessageStrip message={event.message} />
          </div>
        )))}

        {marks.length > 0 ? (
          <div className="myx-role-rule" style={{ gridColumn: '1 / -1', gridRow: '-2 / -1' }}>
            {marks.map((mark) => (
              <span className="myx-role-mark" key={mark.label} style={{ left: `${mark.at}%` }}>{mark.label}</span>
            ))}
          </div>
        ) : null}
      </div>

      <aside className="myx-board-aside myx-role-aside">
        {focus === null
          ? <Empty text="no session is racked" source="GET /api/teams/{id}" />
          : <MemberCard board={board} member={focus} />}
        <TurnsChart data={data} />
        {chat}
        {feed}
      </aside>

      <BoardFooter board={board} />
    </section>
  );
}

/** One turn, racked in its member's column at the time it started. */
function TurnStrip({ turn }: { turn: TeamTurn }) {
  return (
    <WrapStrip edge={turn.live ? 'green' : 'amber'} ariaLabel={`turn ${turn.id}`} className="myx-tl-turn">
      <StripField w={0} fixed label={S.turn} value={turn.id} />
      <StripField w={0} fixed label={S.time} value={turn.time} />
      <StripField w={0} fixed label={S.duration} value={turn.duration} />
      <StripField w={0} fixed label={S.tokensIn} value={thousand(turn.input)} />
      <StripField w={0} fixed label={S.tokensOut} value={thousand(turn.output)} />
      <StripField w={0} fixed label={S.total} value={thousand(turn.input + turn.output)} />
    </WrapStrip>
  );
}

/** The team's slots, as comp-c racks them in its right column. The edge is the window: a head that
 *  reports one is green, a head whose provider reports none is amber, because an unreported window
 *  is the thing on that card the operator cannot plan around. */
function TeamSlots({ board }: { board: TeamPayload }) {
  return (
    <div className="myx-tl-slots">
      <h3 className="myx-board-panel-title">{S.teamSlots}</h3>
      {board.team.slots.map((slot, index) => {
        const member = board.members.find((m) => m.name === slot.session);
        const window = member?.window ?? null;
        return (
          <div className="myx-board-card" key={`${slot.role}-${index}`}>
            <HolderEdge state={window === null ? 'amber' : 'green'} label="" />
            <dl className="myx-board-card-rows">
              <div className="myx-board-card-row"><dt>{S.role}</dt><dd>{slot.role}</dd></div>
              <div className="myx-board-card-row"><dt>{S.head}</dt><dd>{slot.model === null || slot.model === undefined ? slot.head : `${slot.head} (${slot.model})`}</dd></div>
              <div className="myx-board-card-row"><dt>{S.session}</dt><dd>{slot.session ?? 'none'}</dd></div>
              <div className="myx-board-card-row"><dt>{S.window}</dt><dd>{window ?? 'not reported by provider'}</dd></div>
            </dl>
          </div>
        );
      })}
    </div>
  );
}

/** Tokens per role for the day, with the two things that make the table honest printed under it:
 *  how the turns were joined to the roles, and how far back the day's oldest turn reaches. */
function CostPerRole({ board, data }: { board: TeamPayload; data: TeamViewData | null }) {
  if (data === null) {
    return (
      <div className="myx-board-panel">
        <h3 className="myx-board-panel-title">{S.costPerRole}</h3>
        <Empty text="no economics route" source="V4-131 pending" />
      </div>
    );
  }
  const table = costPerRole(board, data.economics);
  const rows = [...table.rows, table.unattributed, table.total];
  return (
    <div className="myx-board-panel">
      <h3 className="myx-board-panel-title">{S.costPerRole}</h3>
      <table className="myx-board-table">
        <thead>
          <tr>
            <th scope="col">{S.role}</th>
            <th scope="col">{S.tokensIn}</th>
            <th scope="col">{S.tokensOut}</th>
            <th scope="col">{S.total}</th>
            <th scope="col">{S.turns}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.role} className={row.role === 'total' ? 'myx-board-table-total' : undefined}>
              <th scope="row">{row.role}</th>
              <td><Figure value={thousand(row.input)} basis="measured" /></td>
              <td><Figure value={thousand(row.output)} basis="measured" /></td>
              <td><Figure value={thousand(row.total)} basis="measured" /></td>
              <td><Figure value={row.turns} basis="measured" /></td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="myx-board-panel-note">
        {`joined on the first ${JOIN_PREFIX} characters of the session id`}
        {table.oldest === null ? '' : `, oldest turn ${table.oldest.id} at ${table.oldest.at}`}
      </p>
    </div>
  );
}

/** Turns per member for the day, on the same join as the cost table. */
function TurnsPerMember({ board, data }: { board: TeamPayload; data: TeamViewData | null }) {
  if (data === null) {
    return (
      <div className="myx-board-panel">
        <h3 className="myx-board-panel-title">{S.turnsPerMember}</h3>
        <Empty text="no economics route" source="V4-131 pending" />
      </div>
    );
  }
  const rows = turnsPerMember(board, data.economics);
  const peak = Math.max(1, ...rows.map((row) => row.turns));
  return (
    <div className="myx-board-panel">
      <h3 className="myx-board-panel-title">{S.turnsPerMember}</h3>
      <ul className="myx-board-bars">
        {rows.map((row) => (
          <li className="myx-board-bar" key={row.member}>
            <span className="myx-board-bar-name">{row.member}</span>
            <span className="myx-board-bar-figure"><Figure value={row.turns} basis="measured" /></span>
            <span className="myx-board-bar-track">
              <span className="myx-board-bar-fill" style={{ width: `${(row.turns / peak) * 100}%` }} />
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** The timeline: one column per member down the day, a time gutter beside them, and the hand-offs
 *  crossing the columns at the minute they were sent. */
export function TeamTimeline({ board, data = null }: { board: TeamPayload; data?: TeamViewData | null }) {
  const empty: TeamViewData = { turns: [], economics: [], lastHour: [], now: 'now' };
  const rows = timelineRows(board, data ?? empty);
  const columns = board.members;
  // The gutter takes (197-138.24)/1040.76 = 5.646% of comp-c's rack and the three member columns
  // the rest, so the tracks are those shares: the gutter is a fraction of the rack, not a third of
  // it, which is what `4.204fr 1fr 1fr 1fr` made it in the first capture.
  const tracks = `5.646fr ${columns.map(() => `${(94.354 / Math.max(columns.length, 1)).toFixed(3)}fr`).join(' ')}`;

  return (
    <section className="myx-board myx-board-tl" aria-label={S.board}>
      <BoardHeader board={board} />

      <div className="myx-tl-rack" style={{ gridTemplateColumns: tracks }}>
        <span className="myx-tl-gutter-head" style={{ gridRow: 1, gridColumn: 1 }} />
        {columns.map((member, index) => (
          <div className="myx-tl-head" key={member.name} style={{ gridRow: 1, gridColumn: index + 2 }}>
            <span className="myx-tl-head-name">{member.name}</span>
            <span className="myx-tl-head-sub">
              {`${member.head}${member.model === null ? '' : ` (${member.model})`} · ${member.role}`}
            </span>
          </div>
        ))}

        {rows.map((row, index) => (row.kind === 'bucket' ? [
          <span className="myx-tl-mark" key={`mark-${row.label}-${index}`} style={{ gridRow: index + 2, gridColumn: 1 }}>{row.label}</span>,
          ...row.cells.map((cells, column) => (
            <div className="myx-tl-cell" key={`cell-${index}-${columns[column].name}`} style={{ gridRow: index + 2, gridColumn: column + 2 }}>
              {cells.map((cell) => (cell.kind === 'turn'
                ? <TurnStrip key={cell.turn.id} turn={cell.turn} />
                : (
                  <WrapStrip key={`${cell.member}-${cell.time}`} edge="grey" ariaLabel={`${cell.member} ${cell.activity}`} className="myx-tl-act">
                    <StripField w={0} fixed label={S.activity} value={cell.activity} mono={false} />
                    <StripField w={0} fixed label={S.time} value={cell.time} />
                  </WrapStrip>
                )))}
            </div>
          )),
        ] : [
          <span className="myx-tl-mark" key={`mark-${row.label}-${index}`} style={{ gridRow: index + 2, gridColumn: 1 }}>{row.label}</span>,
          <div
            className="myx-tl-cell myx-tl-cross"
            key={`msg-${index}`}
            style={{ gridRow: index + 2, gridColumn: `${row.from + 2} / ${row.to + 3}` }}
          >
            <WrapStrip edge={row.message.fromHead === 'claude' ? 'green' : 'grey'} ariaLabel={`${row.message.from} to ${row.message.to}`} className="myx-tl-msg">
              <StripField w={0} fixed label={S.message} value={row.message.text} mono={false} />
              <StripField w={0} fixed label={S.arrow} value={`${slotName(board.team.slots, row.message.from)} → ${slotName(board.team.slots, row.message.to)}`} mono={false} />
              <StripField w={0} fixed label={S.time} value={row.message.time} />
            </WrapStrip>
          </div>,
        ]))}
      </div>

      <aside className="myx-board-aside myx-tl-aside">
        <TeamSlots board={board} />
        <CostPerRole board={board} data={data} />
        <TurnsPerMember board={board} data={data} />
      </aside>

      <BoardFooter board={board} />
    </section>
  );
}
