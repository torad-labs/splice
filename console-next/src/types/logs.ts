// The logs payload and the tail cursor's shapes. GET /api/logs/{head} returns the tail of a head's
// log as an array of complete lines, oldest first.

/** GET /api/logs/{head}: the daemon's own answer. */
export interface LogsPayload {
  key: string;
  path: string;
  lines: string[];
  note?: string;
}

/** One head's retained tail: the cursor the view carries from one poll to the next. */
export interface LogTail {
  /** The head key the payload reported (LogsPayload.key). */
  key: string;
  /** The log file the daemon read (LogsPayload.path). Shown to the operator, and the second half
   *  of "is this still the same stream". */
  path: string;
  /** The lines as received, oldest first. */
  lines: readonly string[];
}

/** The result of moving a cursor onto a fresh payload. */
export interface TailAdvance {
  tail: LogTail;
  /** Lines in [tail] that were not in the previous tail, oldest first. */
  appended: readonly string[];
  /** True when [tail] does NOT continue the previous one: the view switched head, the log lane
   *  rotated, or the reader fell further behind than the daemon's window. The view re-renders from
   *  scratch and says the window restarted, instead of presenting [appended] as the whole story. */
  reset: boolean;
}

/** The severities the daemon can mark a line with. Ordered most severe first. */
export const LEVELS = ['fatal', 'error', 'warn', 'info', 'debug', 'trace'] as const;

export type LogLevel = (typeof LEVELS)[number];

/** The filter the logs page applies to a tail. Every dimension is optional; the empty filter is
 *  every line, which is what the page opens with. */
export interface LogFilter {
  /** The daemon's bracketed tag: a head key on head lines, a subsystem name on subsystem lines.
   *  null means every tag. */
  head: string | null;
  /** The severity the daemon MARKED on the line. null means every level, unmarked lines included,
   *  so the default view never hides a line the daemon chose not to classify. */
  level: LogLevel | null;
  /** Case-insensitive substring over the whole line. Empty means every line. */
  substring: string;
}
