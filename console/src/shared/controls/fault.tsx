// The world's error note: one strip with a red holder edge carrying the daemon's own words.
//
// The page renders store state, so a failure arrives as a message, never as a stack trace: the
// fault prints that message in the same box every other value is printed in. Red lives on the
// holder edge - the one place attention is a colour - and the edge prints its own word beside the
// mark, so the fault survives a grayscale screenshot and a colorblind reader.
//
// THE MESSAGE WRAPS, AND THE STRIP GROWS WITH IT. It used to be a `StripField w={60}`, which clips
// with an ellipsis and never wraps - the right trade inside a rack, whose row pitch has to hold, and
// the wrong one for the single field in the console whose only job is to be read (m1 design review
// D2). A SafeFailureText is usually longer than 60 characters, so most faults printed as a sentence
// with its end cut off. A fault is not in a rack: this field wraps at the floor's own measure and
// the strip is as tall as the sentence is.
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
import { timeAgo } from '@shared/lib';
import { Figure, Strip } from '@shared/ui';
import { Key } from './key';
import { S } from './strings';

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
    <div className="myx-fault" role="alert">
      <Strip edge="red" edgeLabel={S.fault} ariaLabel={held === null ? message : `${message}, ${S.lastRead} ${held}, stale`}>
        <span className="myx-fault-field" style={{ maxWidth: `${w}ch` } as CSSProperties}>
          <span className="myx-fault-message">{message}</span>
          {held === null ? null : (
            <span className="myx-fault-held">
              <span>{S.lastRead}</span>
              <Figure value={held} basis="stale" />
            </span>
          )}
        </span>
      </Strip>
      {onRetry === undefined ? null : (
        <Key onClick={onRetry}>{retryLabel ?? S.retry}</Key>
      )}
    </div>
  );
}
