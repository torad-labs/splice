// The world's error note: one strip with a red holder edge carrying the daemon's own words.
//
// The page renders store state, so a failure arrives as a message, never as a stack trace: the
// fault prints that message as its single field, in the same box every other value is printed in.
// Red lives on the holder edge - the one place attention is a colour - and the edge prints its own
// word beside the mark, so the fault survives a grayscale screenshot and a colorblind reader.
//
// The retry key is optional because most failures in this console are not retryable by a click
// (the route is pending, the daemon is down); a fault that cannot be retried does not pretend it
// can by offering a dead key.
import type { ReactNode } from 'react';
import { Strip, StripField } from '@shared/ui';
import { Key } from './key';
import { S } from './strings';

export function Fault({ message, onRetry, retryLabel, w = 60 }: {
  /** The daemon's own words (SafeFailureText renders them), never a raw exception. */
  message: string;
  onRetry?: () => void;
  retryLabel?: ReactNode;
  /** The message field's width in `ch`; a long message clips rather than wrapping the strip. */
  w?: number;
}) {
  return (
    <div className="myx-fault" role="alert">
      <Strip edge="red" edgeLabel={S.fault} ariaLabel={message}>
        <StripField w={w} label={S.message} value={message} mono={false} />
      </Strip>
      {onRetry === undefined ? null : (
        <Key onClick={onRetry}>{retryLabel ?? S.retry}</Key>
      )}
    </div>
  );
}
