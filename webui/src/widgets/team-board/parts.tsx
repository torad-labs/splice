// The pieces every view of the board prints: the team's identity strip at its head, its identity
// line at the foot, and one message strip. They live here rather than in index.tsx so the two other
// views (views.tsx) can compose them without importing the slice's own entry point back into
// itself.
import { Strip, StripField } from '@shared/ui';
import type { CSSProperties } from 'react';
import type { TeamMessage, TeamPayload } from '@entities/team';
import { S } from './strings';

/** The header's five boxes, from the comp's own column scan of the header strip. */
export const HEAD_COLS = [27.38, 39.41, 42.26, 21.31, 30.00];
/** The chat's columns, from the same scan of its bay. */
export const MSG_COLS = [5.48, 10.12, 2.26, 12.14, 4.41, 19.05];

/** The stamp the comp prints: YYYY-MM-DD HH:MM:SS, in UTC so a capture reads the same on every
 *  machine. */
export function stamp(epochMs: number): string {
  const at = new Date(epochMs);
  const pad = (n: number): string => String(n).padStart(2, '0');
  const date = `${at.getUTCFullYear()}-${pad(at.getUTCMonth() + 1)}-${pad(at.getUTCDate())}`;
  return `${date} ${pad(at.getUTCHours())}:${pad(at.getUTCMinutes())}:${pad(at.getUTCSeconds())}`;
}

/** The team's own identity strip, printed at the head of every view of the board. */
export function BoardHeader({ board }: { board: TeamPayload }) {
  const leadSlot = board.team.slots.find((slot) => slot.lead);
  return (
    <Strip className="myx-board-header" edge="green" edgeLabel="" ariaLabel={board.team.name}>
      <StripField w={HEAD_COLS[0]} label={S.team} value={board.team.name} mono={false} />
      <StripField w={HEAD_COLS[1]} label={S.goal} value={board.team.goal} mono={false} />
      <StripField w={HEAD_COLS[2]} label={S.repo} value={board.team.repo} mono={false} />
      <StripField w={HEAD_COLS[3]} label={S.slots} value={`${board.team.slots.length} slots, ${board.team.slots.filter((s) => s.session !== null).length} bound`} mono={false} />
      <StripField w={HEAD_COLS[4]} label={S.leadDriving} value={leadSlot?.session ?? 'none'} mono={false} />
    </Strip>
  );
}

/** The team's identity at the foot of the console, across the rail's edge. */
export function BoardFooter({ board }: { board: TeamPayload }) {
  return (
    <div className="myx-board-footer">
      <span className="myx-board-footer-cell">{S.teamId} {board.team.id}</span>
      <span className="myx-board-footer-cell">{S.teamCreated} {stamp(board.team.created_epoch_millis)}</span>
      <span className="myx-board-footer-cell">{S.teamUpdated} {stamp(board.team.updated_epoch_millis)}</span>
    </div>
  );
}

/** One message strip: the six fields the comp prints, with the sender's edge. */
export function MessageStrip({ message, className = 'myx-board-msg', style = {}, cols = MSG_COLS }: {
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

