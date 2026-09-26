// One team, drawn on the kit: its figures in a stat row, its seats as a table grouped by head or by
// role (an open seat is a row with nobody in it), the day's turns as lanes on one clock, and what
// each role cost. Every number with a shape gets its shape (docs/design/DESIGN.md section 9):
// tokens are a split bar on one scale for the table, the seats a row of pips, the last hour a
// sparkline, a turn a bar as long as it ran.
//
// A figure no route reports prints its absence, never a zero. The member fields only the dev
// fixture carries (window, context left, branch, diff) are printed where a member has them and
// left out where none does, so a live team is not a table of dashes.
import { useState } from 'react';
import type { ReactNode } from 'react';
import { CrownSimpleIcon } from '@phosphor-icons/react/dist/csr/CrownSimple';
import { HeadlessMark, HeadMark, hueClass, NO_SPLICE_HEAD, registryLists, useHues } from '@entities/control-status';
import { UNLISTED } from '@entities/team';
import type { TeamPayload, TeamSlot } from '@entities/team';
import {
  Badge, BasisTag, DataTable, DetailPanel, Empty, InfoTip, KeyValue, Lanes, Legend, Meter, Pips, Reveal, Section, Sparkline,
  StackedBar, Stat, StatRow, weightedColumns,
} from '@shared/ui';
import type { Column, Lane, LaneMessage, RowGroup, Tone } from '@shared/ui';
import { cx, fmtInt, fmtMs, fmtTokens } from '@shared/lib';
import { clockText, costTable, dayAxis, lanesOf, lastReceived, roleName, seatGroups, seatsOf, slotName } from './model';
import type { RoleCost, Seat, TeamViewData } from './model';
import { H, S, U } from './strings';
import './team-board.css';

export {
  SESSION_TAG_CHARS, costTable, dayAxis, lanesOf, lastReceived, roleName, rolesOf, seatGroups, seatsOf, slotName,
  tokensIn, clockText,
} from './model';
export type { CostTable, DayAxis, RoleCost, Seat, SeatGroup, TeamHourPoint, TeamTurn, TeamViewData } from './model';

const money = (value: number | null): string => (value === null ? S.absent : `$${value.toFixed(3)}`);

/** YYYY-MM-DD HH:MM on the operator's own clock. */
const stamp = (epochMs: number): string => {
  const at = new Date(epochMs);
  return `${at.getFullYear()}-${String(at.getMonth() + 1).padStart(2, '0')}-${String(at.getDate()).padStart(2, '0')} ${clockText(epochMs)}`;
};

/** A word as a badge prints it: the client's own status words arrive lowercase. */
const capital = (word: string): string => word.charAt(0).toUpperCase() + word.slice(1);

/** The tones of the two token parts, named once in each section's legend. */
const TOKEN_KEY = [{ mark: 'series-1', label: S.tokensIn }, { mark: 'series-2', label: S.tokensOut }] as const;

/** A seat's state as its badge: open, not listed by the registry, gone, stale, or the session's own
 *  status word. */
export function stateOf(seat: Seat): { tone: Tone; word: string } {
  const member = seat.member;
  if (member === null) return { tone: 'neutral', word: S.open };
  if (member.state === UNLISTED) return { tone: 'neutral', word: S.unlisted };
  if (member.state === 'gone') return { tone: 'neutral', word: capital(member.state) };
  if (member.state === 'stale') return { tone: 'warn', word: capital(member.state) };
  return { tone: 'ok', word: capital(member.state) };
}

function ChecksBadge({ checks }: { checks: string | null }) {
  if (checks === null) return <>{S.absent}</>;
  if (checks === 'pass') return <Badge tone="ok" quiet>{S.pass}</Badge>;
  if (checks === 'fail') return <Badge tone="danger" quiet>{S.fail}</Badge>;
  return <Badge tone="neutral" quiet>{capital(checks)}</Badge>;
}

/** A role with the lead's mark: an icon, so a role that is itself called `lead` does not read
 *  twice. The lead is the slot's flag, never the role's name. */
function RoleCell({ slot }: { slot: TeamSlot }) {
  return (
    <span className="myx-tb-role">
      <span className="myx-tb-role-name">{slot.role}</span>
      {slot.lead ? (
        <span className="myx-tb-lead" role="img" aria-label={S.lead} title={S.lead}>
          <CrownSimpleIcon weight="fill" aria-hidden="true" />
        </span>
      ) : null}
    </span>
  );
}

/** Whether the registry, once read, leaves [head] out: a head splice does not run. An unread
 *  registry names every head as it is. */
const unlisted = (board: TeamPayload, head: string): boolean => registryLists(board.spliceHeads, head) === false;

/** A slot's head: its mark, or the one name and tip every page gives a head splice does not run
 *  (Marlin, 2026-09-25: a plain `claude` slot printed "claude" here and "No splice head" on
 *  Sessions). */
function HeadCell({ board, head }: { board: TeamPayload; head: string }) {
  return unlisted(board, head) ? <HeadlessMark /> : <HeadMark head={head} />;
}

/** In and out tokens as one bar on the table's shared scale, with the sum after it. */
function TokenSplit({ input, output, scale }: { input: number | null; output: number | null; scale: number }) {
  if (input === null || output === null) return <>{S.absent}</>;
  return (
    <span className="myx-tb-split">
      <StackedBar
        label={S.tokens}
        total={scale}
        format={fmtTokens}
        parts={[
          { key: 'in', label: S.tokensIn, value: input, mark: TOKEN_KEY[0].mark },
          { key: 'out', label: S.tokensOut, value: output, mark: TOKEN_KEY[1].mark },
        ]}
      />
      <span className="myx-tb-figure">{fmtTokens(input + output)}</span>
    </span>
  );
}

// ---- the stat row --------------------------------------------------------------------------

/** The team's figures: its seats, its lifetime turns, tokens and cost, the last hour's turns in
 *  flight, and today's messages. The lifetime figures are the daemon's own role tallies summed. */
export function TeamStats({ board, data }: { board: TeamPayload; data: TeamViewData | null }) {
  const slots = board.team.slots;
  const bound = slots.filter((slot) => slot.session !== null).length;
  const table = data === null || 'error' in data.economics ? null : costTable(data.economics, slots);
  const hour = data?.lastHour ?? [];
  // The lifetime figures leave out the roles splice never saw, and say which.
  const without = table === null || table.unseen.length === 0 ? null : `${U.without} ${table.unseen.join(', ')}`;
  const turnsSub = [table !== null && table.unattributed > 0 ? `${fmtInt(table.unattributed)} ${U.untagged}` : null, without]
    .filter((part) => part !== null)
    .join(' · ');
  // The cost is the priced turns' dollars, and it counts the turns no card priced (V4-264).
  const costSub = table === null ? '' : table.total.cost === null ? S.unpriced
    : [table.total.unpriced > 0 ? `${fmtInt(table.total.unpriced)} ${table.total.unpriced === 1 ? U.unpricedOne : U.unpriced}` : null, without]
      .filter((part) => part !== null)
      .join(' · ');
  return (
    <StatRow>
      <Stat
        label={S.slotsBound}
        value={bound}
        unit={`${U.of} ${slots.length}`}
        chart={<Pips used={bound} total={slots.length} label={S.slotsBound} />}
      />
      <Stat
        label={S.turns}
        value={table === null ? S.absent : fmtInt(table.total.turns)}
        {...(turnsSub === '' ? {} : { sub: turnsSub })}
      />
      <Stat
        label={S.tokens}
        value={table === null ? S.absent : fmtTokens(table.total.input + table.total.output)}
        {...(without === null ? {} : { sub: without })}
        {...(table === null ? {} : {
          chart: (
            <StackedBar
              label={S.tokens}
              format={fmtTokens}
              parts={[
                { key: 'in', label: S.tokensIn, value: table.total.input, mark: TOKEN_KEY[0].mark },
                { key: 'out', label: S.tokensOut, value: table.total.output, mark: TOKEN_KEY[1].mark },
              ]}
            />
          ),
        })}
      />
      <Stat
        label={S.cost}
        basis="estimated"
        value={table === null ? S.absent : money(table.total.cost)}
        {...(costSub === '' ? {} : { sub: costSub })}
      />
      <Stat
        label={S.inFlight}
        value={data === null || data.inFlight === null ? S.absent : data.inFlight}
        {...(hour.length === 0 ? {} : { trend: <Sparkline values={hour.map((point) => point.turns)} label={S.lastHour} /> })}
      />
      <Stat label={S.messages} {...(board.messages === null ? { value: S.absent } : { value: board.messages.length, unit: U.today })} />
    </StatRow>
  );
}

// ---- the members ---------------------------------------------------------------------------

/** The columns kept while a seat is open beside the table. */
const OPEN_KEYS: ReadonlySet<string> = new Set(['name', 'role', 'state', 'turns', 'cost']);

/** Each column's share of the table, before the shown columns are scaled to the whole: the table
 *  drops the head column by head, a window no member reports, and all but five columns while a seat
 *  is open, and fixed widths in rem overran it and crushed the names. */
const WEIGHTS: Record<string, number> = {
  name: 14, role: 9, head: 11, model: 10, window: 6, state: 11, turns: 5, tokens: 14, cost: 6.5, last: 6.5, checks: 7,
};

/** A seat's name: its member's, or its numbered role when it is open. */
const seatName = (board: TeamPayload, seat: Seat): string => seat.member?.name ?? roleName(board.team.slots, seat.slot);

/** Every seat of the team in runs by head or by role, and the opened seat beside them. */
export function TeamMembers({ board, by }: { board: TeamPayload; by: 'head' | 'role' }) {
  const [openSlot, setOpenSlot] = useState<string | null>(null);
  const hueOf = useHues();
  const opened = seatsOf(board).find((seat) => seat.slot.id === openSlot) ?? null;
  const scale = Math.max(1, ...board.members.map((member) => (member.tokensIn ?? 0) + (member.tokensOut ?? 0)));
  const hasWindow = board.members.some((member) => member.window !== null);

  const all: Column<Seat>[] = [
    {
      key: 'name',
      label: S.name,
      primary: true,
      cell: (seat) => seat.member?.name ?? <span className="myx-tb-open">{S.openSeat}</span>,
    },
    { key: 'role', label: S.role, cell: (seat) => <RoleCell slot={seat.slot} /> },
    ...(by === 'head' ? [] : [{ key: 'head', label: S.head, cell: (seat: Seat) => <HeadCell board={board} head={seat.slot.head} /> }]),
    { key: 'model', label: S.model, mono: true, cell: (seat) => seat.slot.model ?? S.absent },
    ...(hasWindow ? [{ key: 'window', label: S.window, mono: true, cell: (seat: Seat) => seat.member?.window ?? S.absent }] : []),
    { key: 'state', label: S.state, cell: (seat) => <Badge tone={stateOf(seat).tone} quiet>{stateOf(seat).word}</Badge> },
    { key: 'turns', label: S.turns, align: 'end', mono: true, cell: (seat) => seat.member?.turns ?? S.absent },
    {
      key: 'tokens',
      label: S.tokens,
      cell: (seat) => <TokenSplit input={seat.member?.tokensIn ?? null} output={seat.member?.tokensOut ?? null} scale={scale} />,
    },
    { key: 'cost', label: S.cost, basis: 'estimated', align: 'end', mono: true, cell: (seat) => money(seat.member?.costEst ?? null) },
    { key: 'last', label: S.lastTurn, mono: true, cell: (seat) => seat.member?.lastTurn ?? S.absent },
    { key: 'checks', label: S.checks, cell: (seat) => <ChecksBadge checks={seat.member?.checks ?? null} /> },
  ];
  const columns = weightedColumns(opened === null ? all : all.filter((column) => OPEN_KEYS.has(column.key)), WEIGHTS);

  const groups: RowGroup<Seat>[] = seatGroups(board, by).map((group) => ({
    key: group.key,
    title: by === 'head' ? <HeadCell board={board} head={group.key} /> : group.key,
    count: group.seats.length,
    rows: group.seats,
    ...(by === 'head' ? { hue: hueClass(hueOf(group.key)) } : {}),
  }));

  return (
    <Section
      title={S.members}
      count={board.team.slots.length}
      info={{ text: H.members, label: S.membersWhy }}
      actions={<Legend items={TOKEN_KEY} label={S.tokensKey} />}
    >
      <div className={cx('myx-tb-board', opened !== null && 'myx-tb-board-open')}>
        <DataTable
          className="myx-tb-table"
          columns={columns}
          groups={groups}
          rowKey={(seat) => seat.slot.id}
          label={S.members}
          onOpen={(seat) => setOpenSlot(seat.slot.id === openSlot ? null : seat.slot.id)}
          openLabel={(seat) => `${S.detail} ${seatName(board, seat)}`}
          selectedKey={openSlot}
          rowTone={(seat) => (seat.member?.state === 'stale' ? 'warn' : null)}
          rowHue={(seat) => hueClass(hueOf(seat.slot.head))}
        />
        {/* UNMOUNTED at rest, the sessions pattern: the table takes the whole width until a seat
            is opened, and no empty landmark stands in a reader's list (tests/detail-rest.test.ts). */}
        {opened === null ? null : (
          <DetailPanel
            title={seatName(board, opened)}
            label={S.detail}
            status={<Badge tone={stateOf(opened).tone} quiet>{stateOf(opened).word}</Badge>}
            onClose={() => setOpenSlot(null)}
            closeLabel={S.close}
          >
            <SeatDetail board={board} seat={opened} />
          </DetailPanel>
        )}
      </div>
    </Section>
  );
}

/** The team as lanes (operator ruling 4, item 5): one strand per head its slots run on, each seat a
 *  card on its strand in slot order, and the day's messages between members as arcs from sender to
 *  receiver. Opening a card opens the seat beside the lanes, as a row does on the table. */
export function TeamLanes({ board }: { board: TeamPayload }) {
  const [openSlot, setOpenSlot] = useState<string | null>(null);
  const hueOf = useHues();
  const seats = seatsOf(board);
  const opened = seats.find((seat) => seat.slot.id === openSlot) ?? null;

  const lanes: Lane[] = seatGroups(board, 'head').map((group) => ({
    key: group.key,
    name: unlisted(board, group.key) ? NO_SPLICE_HEAD : group.key,
    title: <HeadCell board={board} head={group.key} />,
    hue: hueClass(hueOf(group.key)),
    cards: group.seats.map((seat) => {
      const title = seatName(board, seat);
      const role = roleName(board.team.slots, seat.slot);
      // A member named for its role would print the word twice; the second line says only news.
      // A seat stands at its session's start; an open or unlisted seat has none and stands apart.
      return { key: seat.slot.id, title, meta: role === title ? null : role, tone: stateOf(seat).tone, word: stateOf(seat).word, start: seat.member?.startedAt ?? null };
    }),
  }));
  // A message names its parties as the board prints them (pages/teams/board.ts messagesOf): a
  // seated member by name, so a card is found by its member's name; a party no seat holds is not.
  const slotOf = new Map(seats.flatMap((seat) => (seat.member === null ? [] : [[seat.member.name, seat.slot.id] as const])));
  const messages: LaneMessage[] | null = board.messages === null ? null : board.messages.flatMap((message) => {
    const from = slotOf.get(message.from);
    const to = slotOf.get(message.to);
    return from === undefined || to === undefined ? [] : [{ from, to, at: message.at }];
  });

  return (
    <Section title={S.members} count={board.team.slots.length} info={{ text: H.members, label: S.membersWhy }}>
      <div className={cx('myx-tb-board', opened !== null && 'myx-tb-board-open')}>
        <Lanes
          lanes={lanes}
          messages={messages}
          label={S.members}
          open={(key) => setOpenSlot(key === openSlot ? null : key)}
          selected={openSlot}
          cardLabel={(card) => `${card.title}, ${card.word}`}
        />
        {opened === null ? null : (
          <DetailPanel
            title={seatName(board, opened)}
            label={S.detail}
            status={<Badge tone={stateOf(opened).tone} quiet>{stateOf(opened).word}</Badge>}
            onClose={() => setOpenSlot(null)}
            closeLabel={S.close}
          >
            <SeatDetail board={board} seat={opened} />
          </DetailPanel>
        )}
      </div>
    </Section>
  );
}

/** An opened seat's body: where its session runs and what it has done, and the slot's standing
 *  instructions behind a reveal. */
export function SeatDetail({ board, seat }: { board: TeamPayload; seat: Seat }) {
  const { slot, member } = seat;
  /** A row only the dev fixture fills: printed when the member has it, left out when not. */
  const reported = (label: string, value: ReactNode | null): (readonly [string, ReactNode])[] => (value === null ? [] : [[label, value]]);
  const rows: (readonly [string, ReactNode])[] = [
    [S.role, <RoleCell slot={slot} />],
    [S.head, <HeadCell board={board} head={slot.head} />],
    [S.model, slot.model ?? S.absent],
    [S.account, slot.account ?? S.absent],
    ...(member === null ? [] : [
      [S.sessionId, <code className="myx-tb-code">{member.sessionId}</code>] as const,
      [S.started, member.created ?? S.absent] as const,
      [S.uptime, member.uptime ?? S.absent] as const,
      [S.workspace, member.workspace === null ? S.absent : <code className="myx-tb-code">{member.workspace}</code>] as const,
      ...reported(S.window, member.window),
      ...reported(S.contextLeft, member.contextLeftPct === null ? null : (
        <Meter value={member.contextLeftPct / 100} tone="neutral" label={S.contextLeft} figure={`${member.contextLeftPct}%`} />
      )),
      ...reported(S.scratchpad, member.scratchpadKb === null ? null : `${member.scratchpadKb} ${U.kb}`),
      ...reported(S.branch, member.branch),
      ...reported(S.base, member.base),
      ...reported(S.diff, member.diff),
      [S.tokensIn, member.tokensIn === null ? S.absent : fmtInt(member.tokensIn)] as const,
      [S.tokensOut, member.tokensOut === null ? S.absent : fmtInt(member.tokensOut)] as const,
      [S.cost, <>{money(member.costEst)}<BasisTag basis="estimated" /></>] as const,
      [S.lastTurn, member.lastTurn ?? S.absent] as const,
      [S.lastMessage, board.messages === null ? S.absent : (lastReceived(board.messages, member.name) ?? S.none)] as const,
      [S.checks, <ChecksBadge checks={member.checks} />] as const,
    ]),
  ];
  return (
    <>
      {member === null ? <Empty text={S.noSession} source={H.openSeat} /> : null}
      <KeyValue rows={rows} />
      <Section title={S.instructions}>
        {slot.instructions === null
          ? <Empty text={S.noInstructions} />
          : <Reveal label={S.showInstructions}><p className="myx-tb-instructions">{slot.instructions}</p></Reveal>}
      </Section>
    </>
  );
}

// ---- the day's timeline --------------------------------------------------------------------

const TIMELINE_KEY = [
  { mark: 'series-1', label: S.landed },
  { mark: 'ok', label: S.running },
  { mark: 'series-2', label: S.handoffs },
] as const;

/** The day as lanes: one per member, each turn a bar from its start as long as it ran, the
 *  hand-offs as ticks on a lane of their own, all on one clock that ends now. */
export function TeamTimeline({ board, data }: { board: TeamPayload; data: TeamViewData | null }) {
  const section = (body: ReactNode) => (
    <Section title={S.today} info={{ text: H.today, label: S.todayWhy }} actions={<Legend items={TIMELINE_KEY} label={S.timelineKey} />}>
      {body}
    </Section>
  );
  if (data === null) return section(<Empty text={S.readingTurns} />);
  if (board.members.length === 0) return section(<Empty text={S.noSession} source={H.openSeat} />);

  const sent = board.messages ?? [];
  const axis = dayAxis([...data.turns.map((turn) => turn.start), ...sent.map((message) => message.at)], data.now);
  const span = Math.max(axis.to - axis.from, 1);
  const x = (at: number): string => `${Math.max(0, Math.min(100, ((at - axis.from) / span) * 100))}%`;
  const grid = axis.ticks.map((tick) => <span key={tick.at} className="myx-tt-grid" style={{ left: x(tick.at) }} aria-hidden="true" />);

  return section(
    <div className="myx-tt">
      <div className="myx-tt-row myx-tt-axis" aria-hidden="true">
        <span />
        <span className="myx-tt-scale">
          {axis.ticks.map((tick) => <span key={tick.at} className="myx-tt-tick" style={{ left: x(tick.at) }}>{tick.label}</span>)}
        </span>
      </div>
      <div className="myx-tt-row">
        <span className="myx-tt-name myx-tt-quiet">{S.handoffs}</span>
        <span className="myx-tt-track" role="img" aria-label={`${S.handoffs}: ${board.messages === null ? S.absent : board.messages.length}`}>
          {grid}
          {/* Not read yet is the absence glyph, never an empty track that reads as no hand-offs. */}
          {board.messages === null ? <span className="myx-tt-unread" aria-hidden="true">{S.absent}</span> : null}
          {sent.map((message) => (
            <span
              key={`${message.at}-${message.from}-${message.to}`}
              className="myx-tt-msg myx-mark-series-2"
              style={{ left: x(message.at) }}
              title={`${message.time} ${slotName(board, message.from)} → ${slotName(board, message.to)}`}
            />
          ))}
        </span>
      </div>
      {lanesOf(board, data.turns).map(({ member, turns }) => (
        <div className="myx-tt-row" key={member.slot}>
          <span className="myx-tt-name"><HeadMark head={member.head}>{member.name}</HeadMark></span>
          <span
            className="myx-tt-track"
            role="img"
            aria-label={`${member.name}: ${turns.length} ${U.turns}, ${turns.filter((turn) => turn.live).length} ${U.running}`}
          >
            {grid}
            {turns.map((turn) => (
              <span
                key={turn.id}
                className={cx('myx-tt-turn', turn.live ? 'myx-mark-ok' : 'myx-mark-series-1')}
                style={{ left: x(turn.start), width: `${Math.min(100, (turn.ms / span) * 100)}%` }}
                title={`${clockText(turn.start)}, ${fmtMs(turn.ms)}`}
              />
            ))}
          </span>
        </div>
      ))}
      {data.turns.length === 0 ? <Empty text={S.noTurnsToday} source={H.noTurns} /> : null}
    </div>,
  );
}

// ---- the economics -------------------------------------------------------------------------

/** Turns, tokens and cost per role over the team's life, as the daemon tallies them, with the turns
 *  it could place in no role and how far back the oldest turn it holds reaches. A role on no head
 *  the daemon read prints its figures as unknown, with why: its zeros were never measured. */
export function CostPerRole({ board, data }: { board: TeamPayload; data: TeamViewData | null }) {
  const info = { text: H.economics, label: S.economicsWhy };
  if (data === null) return <Section title={S.costPerRole} info={info}><Empty text={S.readingCosts} /></Section>;
  if ('error' in data.economics) {
    return <Section title={S.costPerRole} info={info}><Empty text={S.costsUnreadable} source={data.economics.error} /></Section>;
  }
  const table = costTable(data.economics, board.team.slots);
  const seen = (row: RoleCost): boolean => !table.unseen.includes(row.role);
  const scale = Math.max(1, ...table.rows.filter(seen).map((row) => row.input + row.output));
  const columns: Column<RoleCost>[] = [
    {
      key: 'role',
      label: S.role,
      cell: (row) => (seen(row) ? row.role : <span className="myx-tb-unseen">{row.role}<InfoTip text={H.unseen} label={S.unseenWhy} /></span>),
    },
    { key: 'turns', label: S.turns, width: '10%', align: 'end', mono: true, cell: (row) => (seen(row) ? fmtInt(row.turns) : S.absent) },
    {
      key: 'tokens',
      label: S.tokens,
      width: '40%',
      cell: (row) => (seen(row) ? <TokenSplit input={row.input} output={row.output} scale={scale} /> : S.absent),
    },
    { key: 'cost', label: S.cost, basis: 'estimated', width: '14%', align: 'end', mono: true, cell: (row) => (seen(row) ? money(row.cost) : S.absent) },
  ];
  return (
    <Section title={S.costPerRole} info={info} actions={<Legend items={TOKEN_KEY} label={S.tokensKey} />}>
      {table.rows.length === 0
        ? <Empty text={S.noTurns} source={H.noTurns} />
        : <DataTable columns={columns} rows={table.rows} rowKey={(row) => row.role} label={S.costPerRole} />}
      {table.unattributed === 0 && table.oldest === null ? null : (
        <p className="myx-tb-note">
          {table.unattributed === 0 ? null : (
            <span className="myx-tb-note-part">
              {`${fmtInt(table.unattributed)} ${U.untagged}`}
              <InfoTip text={H.untagged} label={S.untaggedWhy} />
            </span>
          )}
          {table.oldest === null ? null : <span className="myx-tb-note-part">{`${U.since} ${stamp(table.oldest)}`}</span>}
        </p>
      )}
    </Section>
  );
}
