// The world's error line (DESIGN.md section 10: an error is one line): a danger mark, then the
// daemon's own words.
//
// The page renders store state, so a failure arrives as a message, never as a stack trace, and the
// fault prints it whole. The mark is an icon as well as a colour, so the fault survives a grayscale
// screenshot and a colorblind reader.
//
// THE MESSAGE WRAPS, AND THE LINE GROWS WITH IT. A SafeFailureText is often longer than the box, and
// the single field in the console whose only job is to be read is never cut off with an ellipsis
// (m1 design review D2): it wraps at the floor's own measure.
//
// The retry key is optional because most failures in this console are not retryable by a click
// (the route is pending, the daemon is down); a fault that cannot be retried does not pretend it
// can by offering a dead key.
//
// A FAILED READ OFTEN LEAVES ROWS ON SCREEN. A page keeps what its last good read returned (a
// blank page tells the operator less than old rows do), and those rows kept every edge and figure
// they were read with, so with the daemon down the fleet still printed its heads `ok` (console
// walkthrough, 2026-09-24). `lastRead` is the time of that read, the store's `lastUpdated`: when
// it is given, the fault says how old the rows under it are, as a figure on the `stale` basis.
import type { CSSProperties, ReactNode } from 'react';
import { WarningCircleIcon } from '@phosphor-icons/react/dist/csr/WarningCircle';
import { timeAgo } from '@shared/lib';
import { Figure } from '@shared/ui';
import { Key } from './key';
import { S, U } from './strings';

export function Fault({ message, lastRead = null, onRetry, retryLabel, w = 72 }: {
  /** The daemon's own words (SafeFailureText renders them), never a raw exception. */
  message: string;
  /** When the rows still on screen were read (epoch ms), or null when the failed read left none. */
  lastRead?: number | null;
  onRetry?: () => void;
  retryLabel?: ReactNode;
  /** The message's measure in `ch`, on the message's own face: the craft floor's 65-75ch band. */
  w?: number;
}) {
  const held = lastRead === null ? null : timeAgo(lastRead);
  return (
    <div className="myx-fault" role="alert" aria-label={held === null ? message : `${message}, ${S.lastRead} ${held}, ${U.stale}`}>
      <WarningCircleIcon className="myx-fault-icon" aria-hidden="true" />
      <span className="myx-fault-text">
        <span className="myx-fault-message" style={{ maxWidth: `${w}ch` } as CSSProperties}>{message}</span>
        {held === null ? null : (
          <span className="myx-fault-held">
            <span>{S.lastRead}</span>
            <Figure value={held} basis="stale" />
          </span>
        )}
      </span>
      {onRetry === undefined ? null : (
        <Key onClick={onRetry}>{retryLabel ?? S.retry}</Key>
      )}
    </div>
  );
}
