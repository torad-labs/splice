// The team board: a team's sessions racked under the head they run on, the
// hand-off sliding between them, the chat and the activity feed beside them.
//
// Every region of this widget is bound to the box the approved comp measured
// (console/.impeccable/build/scaffold/layout.css, regions in spec.json). The
// boxes are percent of the comp frame; the page sits inside the shell's rail
// and rule, so board.css carries each one twice: the frame percentage it was
// measured at, and the page-relative percentage this sheet places it with.
//
// The bays are the rack; the strips are the rows. Nothing here is invented:
// a field the comp does not show does not exist, and a figure the daemon does
// not report prints its absence rather than a zero.
import { Bay, Empty, Strip, StripField } from '@shared/ui';
import type { TeamMemberRow, TeamPayload } from '@entities/team';
import { BoardFooter, BoardHeader, MSG_COLS, MessageStrip } from './parts';
import { S } from './strings';
import './board.css';
import { ABSENT } from '@shared/lib';

// The board's other two views, re-exported so a page reads every view of the team through the
// slice's one entry point (the boundaries rule in eslint.config.mjs).
export { TeamBoardByRole, TeamTimeline } from './views';
export {
  SESSION_TAG_CHARS, costTable, focusMember, groupByRole, roleName, roleRows, slotName, timeRule, timelineRows,
  tokensIn, turnsPerSlot,
} from './model';
export type {
  CostTable, RoleBay, RoleCost, RoleEvent, TeamHourPoint, TeamTurn, TeamViewData, TimelineRow,
} from './model';

/** The comp's two head bays, in the order it draws them. */
const HEAD_BAY = ['myx-board-bay-0', 'myx-board-bay-1'];

/* The field grid, measured off the comp rather than guessed: a column scan of
   each strip region finds its vertical rules, and every one of the six sets sums
   to exactly the region's 392px, which is how the grid was confirmed rather than
   assumed. The numbers are those px widths over one ch of the field box itself --
   the box declares its ch count on a 14px figure face, so a count is 8.4px --
   which is the unit StripField takes. They are ch, so they move with that size:
   this set was recomputed when the board's type moved to --text-2 on the wdth
   axis (see board.css), and the previous set was 0.8204 of these.
   The comp sizes each row's columns to its own content, so the two heads' lines
   carry different grids rather than sharing one table. */
const LEAD_COLS = [12.02, 4.29, 5.48, 6.07, 5.24, 5.36, 6.79];
const BUILDER_COLS = [10.00, 4.76, 8.22, 4.41, 3.93, 4.88, 9.05];
const LEAD_COLS_2 = [5.83, 7.02, 6.19, 5.48, 4.17, 5.48, 5.71, 5.36];
const BUILDER_COLS_2 = [9.17, 6.43, 5.24, 4.53, 4.17, 5.24, 5.36, 5.12];
const LEAD_COLS_3 = [5.83, 5.95, 12.50, 7.74, 3.33, 5.24, 4.64];
const BUILDER_COLS_3 = [5.24, 6.07, 12.86, 8.10, 3.10, 5.24, 4.64];
/* Each bay carries the names its columns can hold (M3-04): the builder's are narrower, and four
   of its names print their authored short form (strings.ts) rather than a machine ellipsis. */
const BAY_COLS = [
  { l1: LEAD_COLS, l2: LEAD_COLS_2, l3: LEAD_COLS_3, names: S },
  {
    l1: BUILDER_COLS, l2: BUILDER_COLS_2, l3: BUILDER_COLS_3,
    names: {
      ...S, account: S.accountShort, window: S.windowShort, tokensOut: S.tokensOutShort,
      contextLeft: S.contextLeftShort,
    },
  },
];
/** The activity rack's columns, from the same scan of its bay. The chat's live in parts.tsx,
   where the message strip that uses them does. */
const ACT_COLS = [5.00, 11.43, 16.79, 20.24];

/* The two racks below are pitched, not stacked: the comp spaces its chat strips
   and its activity strips down the full height of their regions instead of
   letting them pile up under the header. The three numbers are that spacing as
   a percentage of each region's own height, read off the comp's plate rows. */
/* The comp's three chat plates are not on one pitch: it draws them at 0, 106
   and 200px inside a 287px region, standing 82, 69 and 72px tall. Each row
   carries its own, as the rack's empty slots carry theirs. */
const CHAT_ROWS = [
  { top: 0, height: 28.6 },
  { top: 36.9, height: 24 },
  { top: 69.7, height: 25.1 },
];
/** A fourth message would take the last row's pitch rather than fall off. */
const chatRow = (index: number) => CHAT_ROWS[Math.min(index, CHAT_ROWS.length - 1)];
/** The hand-off's strips are the same strip lifted into a 618px box, so their
 *  columns are the chat's scaled by that wider box rather than re-measured. */
const HANDOFF_COLS = MSG_COLS.map((w) => Number((w * 1.34).toFixed(2)));
/* The activity plates are not on one pitch either (M3-04): the first carries the rack's column
   names, so the comp draws it 58px tall from 619, and the four under it at 682, 721, 758 and 795,
   32-34px tall -- as percentages of the 225px region from 624. The first plate starts above the
   region, which is where the comp puts it. A sixth sample takes the last row's pitch. */
const ACT_ROWS = [
  { top: -2.35, height: 25.74 },
  { top: 25.43, height: 15.09 },
  { top: 42.74, height: 14.2 },
  { top: 59.16, height: 14.2 },
  { top: 75.59, height: 14.65 },
];
const actRow = (index: number) => {
  const last = ACT_ROWS[ACT_ROWS.length - 1];
  return ACT_ROWS[index] ?? { top: last.top + (index - ACT_ROWS.length + 1) * 16.43, height: last.height };
};

const money = (value: number | null): string => (value === null ? ABSENT : `$${value.toFixed(3)}`);
const thousand = (value: number | null): string => (value === null ? ABSENT : value.toLocaleString('en-US'));
/** A figure no route reports prints its absence, never a zero. */
const text = (value: string | number | null): string | number => value ?? ABSENT;
const pct = (value: number | null): string => (value === null ? ABSENT : `${value}%`);
const kb = (value: number | null): string => (value === null ? ABSENT : `${value} k`);

/** The session strips of one member: three printed lines, as the comp racks them.
 *  The edge follows the comp: the slot flagged lead prints green, every other
 *  slot grey. */
function MemberStrips({ member, line, cols }: {
  member: TeamMemberRow;
  line: number;
  cols: { l1: number[]; l2: number[]; l3: number[]; names: Record<keyof typeof S, string> };
}) {
  /* The comp marks a slot once, on its identity line: the holder bar of the
     lead's first line prints green and every other bar on the board prints
     grey, including the lead's own second and third lines. */
  const edge = line === 0 && member.role === 'lead' ? 'green' : 'grey';
  const N = cols.names;
  const cls = `myx-board-strip myx-board-strip-${line}`;

  if (line === 0) {
    return (
      <Strip className={cls} edge={edge} edgeLabel="" ariaLabel={`${member.name} first line`}>
        <StripField w={cols.l1[0]} label={N.name} value={member.name} mono={false} />
        <StripField w={cols.l1[1]} label={N.role} value={member.role} mono={false} />
        <StripField w={cols.l1[2]} label={N.model} value={member.model ?? ABSENT} mono={false} />
        <StripField w={cols.l1[3]} label={N.account} value={member.account ?? ABSENT} mono={false} />
        <StripField w={cols.l1[4]} label={N.window} value={member.window ?? ABSENT} />
        <StripField w={cols.l1[5]} label={N.lastTurn} value={member.lastTurn ?? ABSENT} />
        <StripField w={cols.l1[6]} label={N.state} value={member.state} mono={false} />
      </Strip>
    );
  }
  if (line === 1) {
    return (
      <Strip className={cls} edge={edge} edgeLabel="" ariaLabel={`${member.name} second line`}>
        <StripField w={cols.l2[0]} label={N.head} value={member.head} mono={false} />
        <StripField w={cols.l2[1]} label={N.sessionId} value={member.sessionId} />
        <StripField w={cols.l2[2]} label={N.created} value={text(member.created)} />
        <StripField w={cols.l2[3]} label={N.uptime} value={text(member.uptime)} />
        <StripField w={cols.l2[4]} label={N.turns} value={text(member.turns)} />
        <StripField w={cols.l2[5]} label={N.tokensIn} value={thousand(member.tokensIn)} />
        <StripField w={cols.l2[6]} label={N.tokensOut} value={thousand(member.tokensOut)} />
        <StripField w={cols.l2[7]} label={N.costEst} value={money(member.costEst)} />
      </Strip>
    );
  }
  return (
    <Strip className={cls} edge={edge} edgeLabel="" ariaLabel={`${member.name} third line`}>
      <StripField w={cols.l3[0]} label={N.contextLeft} value={pct(member.contextLeftPct)} />
      <StripField w={cols.l3[1]} label={N.scratchpad} value={kb(member.scratchpadKb)} />
      <StripField w={cols.l3[2]} label={N.workspace} value={text(member.workspace)} mono={false} />
      <StripField w={cols.l3[3]} label={N.branch} value={text(member.branch)} mono={false} />
      <StripField w={cols.l3[4]} label={N.base} value={text(member.base)} mono={false} />
      <StripField w={cols.l3[5]} label={N.diff} value={text(member.diff)} />
      <StripField w={cols.l3[6]} label={N.checks} value={text(member.checks)} mono={false} />
    </Strip>
  );
}

/** The heads the members run on, in the order the members run, folded into the comp's bays: the
 *  poster measured two, so every head after the first shares the second bay rather than a session
 *  being left off the board. */
export function headBays(members: readonly TeamMemberRow[]): string[][] {
  const heads: string[] = [];
  for (const member of members) if (!heads.includes(member.head)) heads.push(member.head);
  return heads.length <= HEAD_BAY.length ? heads.map((head) => [head]) : [[heads[0]], heads.slice(1)];
}

/** `unread` names why the chat or the activity could not be read, so an empty bay says which of
 *  the two silences it is: nothing today, or a read that failed. */
export function TeamBoard({ board, unread = {} }: { board: TeamPayload; unread?: { chat?: string; activity?: string } }) {
  const bays = headBays(board.members);

  const handoff = board.messages[board.messages.length - 1] ?? null;

  // THE FRAME IS THE PHONE'S SCROLLER AND NOTHING ELSE (M3-03). On a desktop it is
  // `display: contents` and draws no box, so the board lays out exactly as before; below 720 it
  // is the one box that scrolls sideways, so the poster can be wider than the screen (one bay per
  // screen) while the view tabs, the list and the composer under it stay where the thumb left them.
  return (
    <div className="myx-board-frame">
      <section className="myx-board" aria-label={S.board}>
        {/* the team header strip: five boxed fields, the team's own identity */}
        <BoardHeader board={board} />

        {/* one bay per head, in the order the members run */}
        {bays.map((heads, index) => (
          <Bay
            key={heads.join(' ')}
            className={`myx-board-bay ${HEAD_BAY[index]}`}
            label={`${S.headLabel} ${heads.join(', ')}`}
          >
            {board.members
              .filter((member) => heads.includes(member.head))
              .flatMap((member) => [0, 1, 2].map((line) => (
                <MemberStrips key={`${member.name}-${line}`} member={member} line={line} cols={BAY_COLS[index]} />
              )))}
            {/* the rack's empty slots: the bay holds room for sessions not here yet */}
            {Array.from({ length: 9 }, (_, i) => (
              <span key={i} className="myx-board-slot" style={{ top: `${42.5 + i * 6.1}%` }} aria-hidden="true" />
            ))}
          </Bay>
        ))}

        {/* the signature gesture: the newest edge caught between the bays */}
        {handoff !== null ? (
          <div className="myx-board-handoff" aria-label={`hand off ${handoff.from} to ${handoff.to}`}>
            {/* ONE afterimage, and the comp is why (M1-58). It draws a lifted strip and a single
                ghost beneath it, both inside the bay; the build drew TWO ghosts, and the second had
                walked far enough down-left to leave the bay entirely - it hung across the bay's left
                upright and over the rail column, a bare `message` label and a clipped packet line in
                a box with no strip around it. It was the only element in the console that crossed a
                bay wall, and no instrument owned it: not a contrast pair, not a coverage plane, not a
                type rung. A gesture that reaches another bay's ground reads as a mistake rather than
                as motion. `.myx-board-ghost-2` is now a rule with no element: board.css is not this
                row's fence and its owner should take the rule out. */}
            <div className="myx-board-ghost myx-board-ghost-1" aria-hidden="true">
              <MessageStrip message={handoff} className="myx-board-msg myx-board-handoff-msg" cols={HANDOFF_COLS} />
            </div>
            <div className="myx-board-handoff-live">
              <MessageStrip message={handoff} className="myx-board-msg myx-board-handoff-msg" cols={HANDOFF_COLS} />
            </div>
          </div>
        ) : null}

        {/* the team's group chat, newest at the bottom */}
        <Bay className="myx-board-bay myx-board-bay-chat" label={`${S.chatLabel} (newest at bottom)`}>
          <div className="myx-board-msgs">
            {board.messages.length === 0 ? (
              <Empty
                text={unread.chat === undefined ? 'no messages today' : 'chat unreadable'}
                source={unread.chat ?? 'GET /api/teams/{id}/chat'}
              />
            ) : null}
            {board.messages.map((message, index) => (
              <MessageStrip
                key={`${message.time}-${message.from}`}
                message={message}
                className="myx-board-msg"
                style={{
                  top: `${chatRow(index).top}%`,
                  height: `${chatRow(index).height}%`,
                }}
              />
            ))}
          </div>
        </Bay>

        {/* the activity sample: a label every 30 seconds, and it says so.
            L-7: THE COLUMN NAMES PRINT ONCE, ON THE RACK, NOT ON EVERY SLIP. The light-room review
            filed it and the 3840 pass made it the most repetitive object on the page -- six data
            rows each carrying its own `time member activity detail` row, alternating down the bay,
            which reads as a rendering loop rather than a table. The comp's activity bay is a table:
            the region crop shows one header row under the plate and five data rows beneath it, and
            StripField's own contract names this case ("omit inside a bay whose head prints the
            column names once ... which is also what a compact rack (the activity feed) needs").
            The lead, builder, chat and hand-off bays keep their per-strip labels: those rows carry
            DIFFERENT label sets, so there the label is a property of the row (M1-34). */}
        <Bay
          className="myx-board-bay myx-board-bay-activity"
          label={`${S.activityLabel}, sampled every 30 s`}
        >
          {/* ONCE, BUT IN THE FIRST SLIP (M3-04). The names printed as a band of their own pressed
              against the plate, outside every cell rule; the comp prints them as the FIRST strip's
              label row, under that strip's own rules and clear of the plate, and the rows below
              carry none. The bisect put -0.071 on activity-label at the commit that added the band
              (c173d7b9, M1-38). */}
          <div className="myx-board-acts">
            {board.activity.length === 0 ? (
              <Empty
                text={unread.activity === undefined ? 'nothing sampled today' : 'activity unreadable'}
                source={unread.activity ?? 'GET /api/teams/{id}/activity'}
              />
            ) : null}
            {board.activity.map((entry, index) => (
              <Strip
                key={`${entry.time}-${entry.member}-${entry.activity}`}
                className="myx-board-act"
                edge="grey"
                edgeLabel=""
                style={{ top: `${actRow(index).top}%`, height: `${actRow(index).height}%` }}
                ariaLabel={`${entry.member} ${entry.activity}`}
              >
                <StripField w={ACT_COLS[0]} {...(index === 0 ? { label: S.time } : {})} value={entry.time} />
                <StripField w={ACT_COLS[1]} {...(index === 0 ? { label: S.member } : {})} value={entry.member} mono={false} />
                <StripField w={ACT_COLS[2]} {...(index === 0 ? { label: S.activity } : {})} value={entry.activity} mono={false} />
                <StripField w={ACT_COLS[3]} {...(index === 0 ? { label: S.detail } : {})} value={entry.detail} mono={false} />
              </Strip>
            ))}
          </div>
        </Bay>

        {/* the team's identity at the foot of the console, across the rail's edge */}
        <BoardFooter board={board} />
      </section>
    </div>
  );
}
