// The team board: a team's sessions racked under the head they run on, the
// hand-off sliding between them, the chat and the activity feed beside them.
//
// Every region of this widget is bound to the box the approved comp measured
// (webui/.impeccable/build/scaffold/layout.css, regions in spec.json). The
// boxes are percent of the comp frame; the page sits inside the shell's rail
// and rule, so board.css carries each one twice: the frame percentage it was
// measured at, and the page-relative percentage this sheet places it with.
//
// The bays are the rack; the strips are the rows. Nothing here is invented:
// a field the comp does not show does not exist, and a figure the daemon does
// not report prints its absence rather than a zero.
import { Bay, Strip, StripField } from '@shared/ui';
import type { CSSProperties } from 'react';
import type { TeamMemberRow, TeamMessage, TeamPayload } from '@entities/team';
import { S } from './strings';
import './board.css';

/** The comp's two head bays, in the order it draws them. */
const HEAD_BAY = ['myx-board-bay-0', 'myx-board-bay-1'];

/* The field grid, measured off the comp rather than guessed: a column scan of
   each strip region finds its vertical rules, and every one of the six sets sums
   to exactly the region's 392px, which is how the grid was confirmed rather than
   assumed. The numbers are those px widths over the field's own advance width at
   --text-1, the size board.css sets (0.6em on the figure face, the label face's
   own advance otherwise), which is the unit StripField takes. Recompute them if
   that size ever moves: they are ch, and ch moves with it.
   The comp sizes each row's columns to its own content, so the two heads' lines
   carry different grids rather than sharing one table. */
const LEAD_COLS = [14.66, 5.22, 6.68, 7.4, 6.11, 6.25, 8.27];
const BUILDER_COLS = [12.19, 5.8, 10.01, 5.37, 4.58, 5.69, 11.03];
const LEAD_COLS_2 = [7.11, 8.19, 7.22, 6.39, 4.86, 6.39, 6.66, 6.25];
const BUILDER_COLS_2 = [11.17, 7.5, 6.11, 5.28, 4.86, 6.11, 6.25, 5.97];
const LEAD_COLS_3 = [6.8, 6.94, 15.24, 9.43, 4.06, 6.11, 5.66];
const BUILDER_COLS_3 = [6.11, 7.08, 15.67, 9.87, 3.77, 6.11, 5.66];
/** The header's five boxes, from the same scan of the header strip. */
const HEAD_COLS = [33.38, 48.03, 51.52, 25.98, 36.57];
const BAY_COLS = [
  { l1: LEAD_COLS, l2: LEAD_COLS_2, l3: LEAD_COLS_3 },
  { l1: BUILDER_COLS, l2: BUILDER_COLS_2, l3: BUILDER_COLS_3 },
];
/** The chat's columns and the activity's, from the same scan of their bays. */
const MSG_COLS = [6.39, 12.34, 2.76, 14.8, 5.37, 23.22];
const ACT_COLS = [5.83, 13.93, 20.46, 24.67];

/* The two racks below are pitched, not stacked: the comp spaces its chat strips
   and its activity strips down the full height of their regions instead of
   letting them pile up under the header. The three numbers are that spacing as
   a percentage of each region's own height, read off the comp's plate rows. */
const CHAT_PITCH = 34.8;
/** The hand-off's strips are the same strip lifted into a 618px box, so their
 *  columns are the chat's scaled by that wider box rather than re-measured. */
const HANDOFF_COLS = MSG_COLS.map((w) => Number((w * 1.34).toFixed(2)));
const ACT_TOP = 9.8;
const ACT_PITCH = 16.9;

const money = (value: number | null): string => (value === null ? 'n/r' : `$${value.toFixed(3)}`);
const thousand = (value: number): string => value.toLocaleString('en-US');
const pct = (value: number | null): string => (value === null ? 'n/r' : `${value}%`);
const kb = (value: number | null): string => (value === null ? 'n/r' : `${value} k`);

/** The stamp the comp prints: YYYY-MM-DD HH:MM:SS, in UTC so a capture reads
 *  the same on every machine. */
function stamp(epochMs: number): string {
  const at = new Date(epochMs);
  const pad = (n: number): string => String(n).padStart(2, '0');
  const date = `${at.getUTCFullYear()}-${pad(at.getUTCMonth() + 1)}-${pad(at.getUTCDate())}`;
  return `${date} ${pad(at.getUTCHours())}:${pad(at.getUTCMinutes())}:${pad(at.getUTCSeconds())}`;
}

/** The session strips of one member: three printed lines, as the comp racks them.
 *  The edge follows the comp: the slot flagged lead prints green, every other
 *  slot grey. */
function MemberStrips({ member, line, cols }: {
  member: TeamMemberRow;
  line: number;
  cols: { l1: number[]; l2: number[]; l3: number[] };
}) {
  /* The comp marks a slot once, on its identity line: the holder bar of the
     lead's first line prints green and every other bar on the board prints
     grey, including the lead's own second and third lines. */
  const edge = line === 0 && member.role === 'lead' ? 'green' : 'grey';
  const cls = `myx-board-strip myx-board-strip-${line}`;

  if (line === 0) {
    return (
      <Strip className={cls} edge={edge} edgeLabel="" ariaLabel={`${member.name} first line`}>
        <StripField w={cols.l1[0]} label={S.name} value={member.name} mono={false} />
        <StripField w={cols.l1[1]} label={S.role} value={member.role} mono={false} />
        <StripField w={cols.l1[2]} label={S.model} value={member.model ?? 'n/r'} mono={false} />
        <StripField w={cols.l1[3]} label={S.account} value={member.account ?? 'n/r'} mono={false} />
        <StripField w={cols.l1[4]} label={S.window} value={member.window ?? 'n/r'} />
        <StripField w={cols.l1[5]} label={S.lastTurn} value={member.lastTurn ?? 'n/r'} />
        <StripField w={cols.l1[6]} label={S.state} value={member.state} mono={false} />
      </Strip>
    );
  }
  if (line === 1) {
    return (
      <Strip className={cls} edge={edge} edgeLabel="" ariaLabel={`${member.name} second line`}>
        <StripField w={cols.l2[0]} label={S.head} value={member.head} mono={false} />
        <StripField w={cols.l2[1]} label={S.sessionId} value={member.sessionId} />
        <StripField w={cols.l2[2]} label={S.created} value={member.created} />
        <StripField w={cols.l2[3]} label={S.uptime} value={member.uptime} />
        <StripField w={cols.l2[4]} label={S.turns} value={member.turns} />
        <StripField w={cols.l2[5]} label={S.tokensIn} value={thousand(member.tokensIn)} />
        <StripField w={cols.l2[6]} label={S.tokensOut} value={thousand(member.tokensOut)} />
        <StripField w={cols.l2[7]} label={S.costEst} value={money(member.costEst)} />
      </Strip>
    );
  }
  return (
    <Strip className={cls} edge={edge} edgeLabel="" ariaLabel={`${member.name} third line`}>
      <StripField w={cols.l3[0]} label={S.contextLeft} value={pct(member.contextLeftPct)} />
      <StripField w={cols.l3[1]} label={S.scratchpad} value={kb(member.scratchpadKb)} />
      <StripField w={cols.l3[2]} label={S.workspace} value={member.workspace} mono={false} />
      <StripField w={cols.l3[3]} label={S.branch} value={member.branch} mono={false} />
      <StripField w={cols.l3[4]} label={S.base} value={member.base} mono={false} />
      <StripField w={cols.l3[5]} label={S.diff} value={member.diff} />
      <StripField w={cols.l3[6]} label={S.checks} value={member.checks} mono={false} />
    </Strip>
  );
}

/** One message strip: the six fields the comp prints, with the sender's edge. */
function MessageStrip({ message, className = 'myx-board-msg', style = {}, cols = MSG_COLS }: {
  message: TeamMessage;
  className?: string;
  style?: CSSProperties;
  cols?: number[];
}) {
  return (
    <Strip
      className={className}
      style={style}
      edge={message.fromHead === 'claude' ? 'green' : 'grey'}
      edgeLabel=""
      ariaLabel={`${message.from} to ${message.to}`}
    >
      <StripField w={cols[0]} label={S.time} value={message.time} />
      <StripField w={cols[1]} label={S.from} value={message.from} mono={false} />
      <StripField w={cols[2]} label={S.arrow} value="→" mono={false} />
      <StripField w={cols[3]} label={S.to} value={message.to} mono={false} />
      <StripField w={cols[4]} label={S.packet} value={message.packet} mono={false} />
      <StripField w={cols[5]} label={S.message} value={message.text} mono={false} />
    </Strip>
  );
}

export function TeamBoard({ board }: { board: TeamPayload }) {
  const heads: string[] = [];
  for (const member of board.members) if (!heads.includes(member.head)) heads.push(member.head);

  const leadSlot = board.team.slots.find((slot) => slot.lead);
  const handoff = board.messages[board.messages.length - 1] ?? null;

  return (
    <section className="myx-board" aria-label={S.board}>
      {/* the team header strip: five boxed fields, the team's own identity */}
      <Strip className="myx-board-header" edge="green" edgeLabel="" ariaLabel={board.team.name}>
        <StripField w={HEAD_COLS[0]} label={S.team} value={board.team.name} mono={false} />
        <StripField w={HEAD_COLS[1]} label={S.goal} value={board.team.goal} mono={false} />
        <StripField w={HEAD_COLS[2]} label={S.repo} value={board.team.repo} mono={false} />
        <StripField w={HEAD_COLS[3]} label={S.slots} value={`${board.team.slots.length} slots, ${board.team.slots.filter((s) => s.session !== null).length} bound`} mono={false} />
        <StripField w={HEAD_COLS[4]} label={S.leadDriving} value={leadSlot?.session ?? 'none'} mono={false} />
      </Strip>

      {/* one bay per head, in the order the members run */}
      {heads.slice(0, HEAD_BAY.length).map((head, index) => (
        <Bay
          key={head}
          className={`myx-board-bay ${HEAD_BAY[index]}`}
          label={`${S.headLabel} ${head}`}
        >
          {board.members
            .filter((member) => member.head === head)
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
          <div className="myx-board-ghost myx-board-ghost-2" aria-hidden="true">
            <MessageStrip message={handoff} className="myx-board-msg myx-board-handoff-msg" cols={HANDOFF_COLS} />
          </div>
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
          {board.messages.map((message, index) => (
            <MessageStrip
              key={`${message.time}-${message.from}`}
              message={message}
              className="myx-board-msg"
              style={{ top: `${index * CHAT_PITCH}%` }}
            />
          ))}
        </div>
      </Bay>

      {/* the activity sample: a label every 30 seconds, and it says so */}
      <Bay className="myx-board-bay myx-board-bay-activity" label={`${S.activityLabel}, sampled every 30 s`}>
        <div className="myx-board-acts">
          {board.activity.map((entry, index) => (
            <Strip
              key={`${entry.time}-${entry.member}-${entry.activity}`}
              className="myx-board-act"
              edge="grey"
              edgeLabel=""
              style={{ top: `${ACT_TOP + index * ACT_PITCH}%` }}
              ariaLabel={`${entry.member} ${entry.activity}`}
            >
              <StripField w={ACT_COLS[0]} label={S.time} value={entry.time} />
              <StripField w={ACT_COLS[1]} label={S.member} value={entry.member} mono={false} />
              <StripField w={ACT_COLS[2]} label={S.activity} value={entry.activity} mono={false} />
              <StripField w={ACT_COLS[3]} label={S.detail} value={entry.detail} mono={false} />
            </Strip>
          ))}
        </div>
      </Bay>

      {/* the team's identity at the foot of the console, across the rail's edge */}
      <div className="myx-board-footer">
        <span className="myx-board-footer-cell">{S.teamId} {board.team.id}</span>
        <span className="myx-board-footer-cell">{S.teamCreated} {stamp(board.team.created_epoch_millis)}</span>
        <span className="myx-board-footer-cell">{S.teamUpdated} {stamp(board.team.updated_epoch_millis)}</span>
      </div>
    </section>
  );
}
