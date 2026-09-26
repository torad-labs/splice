// The live connection's two pure pieces: the SSE frame parser and the reconnect schedule.
//
// Nothing here opens anything. The transport lives in entities/events/api (the fetch wall allows
// fetch in entities/*/api and nowhere else), which feeds this parser the bytes it reads and obeys
// the schedule this file computes. Both are pure so the whole frame contract can be checked with
// hand-written frames, which is the only way to check it today: the daemon route (V4-126) is in
// flight, and the live proof is the m3 gate.
//
// THE FRAME CONTRACT (FEATURES.md section 6, decided 2026-09-18): SSE frames whose `id` is a
// monotonic integer per daemon lifetime, `event` the kind, `data` one JSON object, with a
// `: heartbeat` comment every 15 s. A heartbeat is NOT a frame: it carries no id, no event and no
// data, so it keeps the link demonstrably alive without pretending something happened.

/** The kinds the console subscribes to (FEATURES.md section 6). The daemon may grow a kind this
 *  build does not know; it is parsed and dispatched all the same, and simply has no subscriber. */
export const EVENT_KINDS = [
  'head.state',
  'turn.start',
  'turn.end',
  'session.change',
  'message.edge',
  'account.switch',
] as const;

export type EventKind = (typeof EVENT_KINDS)[number];

export interface EventFrame {
  /** The daemon's monotonic id, or null when the frame carried none. It is what a resume sends
   *  back as `Last-Event-ID`, so a frame without one must not clear it. */
  id: number | null;
  /** The `event:` name, verbatim. */
  kind: string;
  /** The `data:` payload: one JSON object, already parsed. */
  data: unknown;
}

export interface ParsedFrames {
  frames: EventFrame[];
  /** The tail of the buffer: a frame that has not finished arriving. It is carried to the next
   *  chunk rather than dropped, because a frame split across two reads is the normal case on a
   *  stream, not an error. */
  rest: string;
  /** Frames that could not be read: no event name, no data, or data that is not one JSON object.
   *  Counted rather than silently skipped, so the console can say its stream is losing frames. */
  dropped: number;
}

/** `id:` values are integers; anything else leaves the resume point where it was. */
function idOf(value: string): number | null {
  const parsed = Number(value.trim());
  return Number.isInteger(parsed) ? parsed : null;
}

/**
 * Parse `buffer + chunk`, keeping the unfinished tail.
 *
 * SSE's own rules, and only those: frames are separated by a blank line, a line starting with `:`
 * is a comment (the heartbeat), `field: value` with ONE optional leading space after the colon,
 * `data` repeats and its lines join with a newline, and a lone `field` with no colon has an empty
 * value. A frame with no event name or no data is dropped and counted - the daemon owns the
 * format, and the console does not invent an event from a partial one.
 */
export function parseFrames(buffer: string, chunk: string): ParsedFrames {
  // Line endings are normalised on the WHOLE buffer rather than per chunk: a chunk can end between
  // a carriage return and its newline, and splitting on "\n\n" would then miss the frame boundary.
  const blocks = (buffer + chunk).replace(/\r\n/g, '\n').split('\n\n');
  const rest = blocks.pop() ?? '';
  const frames: EventFrame[] = [];
  let dropped = 0;

  for (const block of blocks) {
    let id: number | null = null;
    let kind: string | null = null;
    let sawField = false;
    const data: string[] = [];

    for (const line of block.split('\n')) {
      if (line === '' || line.startsWith(':')) continue;
      sawField = true;
      const colon = line.indexOf(':');
      const field = colon === -1 ? line : line.slice(0, colon);
      const value = colon === -1 ? '' : line.slice(colon + 1).replace(/^ /, '');
      if (field === 'id') id = idOf(value);
      else if (field === 'event') kind = value;
      else if (field === 'data') data.push(value);
    }

    // A block with no fields at all is a comment: the heartbeat, which says the link is alive
    // without saying anything happened. It is not a frame, and it is not a dropped one either.
    if (!sawField) continue;
    if (kind === null || kind === '' || data.length === 0) {
      dropped += 1;
      continue;
    }
    let parsed: unknown;
    try {
      parsed = JSON.parse(data.join('\n'));
    } catch {
      dropped += 1;
      continue;
    }
    if (typeof parsed !== 'object' || parsed === null) {
      dropped += 1;
      continue;
    }
    frames.push({ id, kind, data: parsed });
  }

  return { frames, rest, dropped };
}

/**
 * How long to wait before reopening the stream, by attempt count: 1 s doubling to 30 s, the
 * schedule CONTRACTS.md section 6 fixes. Deterministic on purpose - a jitter would make the one
 * number the operator sees on a reconnect unreproducible, and this console has one client.
 *
 * The caller resets the count to 0 on a FRAME, not on a successful open: a stream that opens and
 * dies at once is a stream that is not working, and backing off is the honest answer to it.
 */
export function backoffMs(attempt: number, baseMs = 1_000, capMs = 30_000): number {
  const step = baseMs * 2 ** Math.max(0, Math.floor(attempt));
  return Math.min(step, capMs);
}
