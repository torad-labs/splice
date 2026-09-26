// A head's live turns and the operator's stop (V4-319, LiveTurnsRoutes). A refusal is the control
// plane's `{"error": <sentence>}`, which request() raises as the thrown error's message.

/** One streaming turn, admitted and not yet ended. */
export interface LiveTurn {
  /** The daemon's id for the turn, the one the stop names. */
  id: string;
  /** The client's session id, or null when the client sent none. */
  session: string | null;
  /** The upstream model the turn runs on. */
  model: string;
  /** A compaction turn. */
  compact: boolean;
  age_ms: number;
  /** Stopped and still ending: the few milliseconds between the stop and the slot's release. */
  stopped: boolean;
}

/** GET /api/heads/{head}/turns/live: the head's live turns, oldest first. */
export interface LiveTurnsPayload {
  head: string;
  turns: LiveTurn[];
}

/** POST /api/heads/{head}/turns/{id}/stop: the stop landed on a live turn of [session]. */
export interface StopTurnResult {
  stopped: true;
  head: string;
  session: string | null;
}
