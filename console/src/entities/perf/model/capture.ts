// The one place a capture read or write becomes the state the console holds, and the one place
// that state becomes what the drawer says. Pure, so the rule "the switch never claims a value the
// daemon is not running" is checked without a daemon.
import type { CaptureState, CaptureWire } from './types';

/** What one PUT came back with: the daemon's answer, or its refusal in its own words. */
export type CaptureWriteResult = { ok: true; answer: CaptureWire } | { ok: false; reason: string };

/**
 * A plain read. A write this console made for the SAME head is kept, because the read cannot
 * report it: GET answers the running value, and a written value runs only after a restart. A read
 * of another head starts clean, so one head's pending write never labels another head.
 */
export function afterRead(previous: CaptureState | null, read: CaptureWire): CaptureState {
  const kept = previous !== null && previous.running.head === read.head ? previous.written : null;
  return { running: read, written: kept, refused: null, writing: false };
}

/**
 * The state a write leaves: the re-read that follows it is what runs, and the write's own answer
 * is what was written. A refused write leaves splice.toml byte-identical (TopologyWriter refuses
 * before it writes), so the previous written value stands and the refusal is kept verbatim.
 */
export function afterWrite(previous: CaptureState | null, write: CaptureWriteResult, reread: CaptureWire): CaptureState {
  const kept = previous !== null && previous.running.head === reread.head ? previous.written : null;
  return write.ok
    ? { running: reread, written: write.answer, refused: null, writing: false }
    : { running: reread, written: kept, refused: write.reason, writing: false };
}

/** What the drawer prints, derived once so the switch, the running field and the restart sentence
 *  cannot disagree. */
export interface CaptureView {
  /** The switch's position: what this console last wrote for the head, else what runs. */
  asked: boolean;
  /** What the daemon records now, from the latest read. */
  running: boolean;
  /** A written value that is not running yet, and the daemon said a restart applies it. */
  awaitingRestart: boolean;
}

export function captureView(state: CaptureState): CaptureView {
  const { written, running } = state;
  return {
    asked: written === null ? running.enabled : written.enabled,
    running: running.enabled,
    awaitingRestart: written !== null && written.restart_required && written.enabled !== running.enabled,
  };
}
