// The two pure models behind the logs page: the TAIL CURSOR and the FILTER. No rendering, no
// store, no clock — the view (M2-03) carries the cursor and subscribes to the store.
//
// WHY A CURSOR. /api/logs/{head} returns the tail of a head's log as an ARRAY of complete lines,
// already head-filtered, oldest first (HeadRoutes.kt logsJson: `tail(tail).split("\n").filter {
// it.isNotEmpty() }`). Every poll therefore re-sends a window that mostly repeats itself, and a
// follow-mode view that re-rendered all of it would fight the operator's scroll position on every
// tick. The cursor answers the only question the view actually has: which lines are NEW, and does
// this window still continue the one I was showing?
//
// LINE SHAPE, read off the daemon's own log on this machine (2026-09-18):
// ~/.claude-codex/logs/daemon.log (2.3 MB) and the rotated daemon.log.1 (67 MB), both
// `[<YYYY-MM-DD HH:MM:SS>] [<tag>] <message>`, e.g.
//   [2026-09-18 01:14:01] [claude-deepseek] cache: input=182346 cached=181248 hit=99% output=252
//   [2026-09-18 00:27:10] [claude-deepseek] turn ERROR cancelled compact=false latency=900031ms
// The bracketed tag is the head key on a head's turn lines and a SUBSYSTEM on subsystem lines
// (`shadow-compact`, `mcp-host`), which is why one tag dimension serves both halves of FEATURES.md
// 4.9's "filter by head tag and by subsystem tag such as [code-mode]".
//
// SEVERITY, and why the level filter is not a lie. The daemon writes no structured level field.
// Across both files above the only severity TOKEN that appears at all is `ERROR` (1961 occurrences
// in the rotated file, 4 in the live one); words like "failed" and "retry" occur only inside prose
// and counter fields (`turn ERROR conn-reset ...`, `retries=3`), never as a marker. So levelOf
// recognizes an explicit uppercase severity token and returns null for everything else, and
// levelsPresent lets the filter offer only the levels the tail actually holds. A line the daemon
// never marked is reported as unmarked rather than guessed at from its prose.
import type { LogsPayload } from '@shared/api';

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

export function tailOf(payload: LogsPayload): LogTail {
  return { key: payload.key, path: payload.path, lines: payload.lines };
}

/**
 * How many leading lines of [next] are the trailing lines of [prev]. The tail is a suffix of the
 * log file, so the next window normally starts somewhere inside the previous one and runs on past
 * it; this overlap is that shared run.
 *
 * The LARGEST match wins. Duplicate lines make the answer ambiguous in one direction only: a
 * repeated line can make a genuinely new line look already seen, while a line whose bytes differ is
 * never claimed as seen. Claiming too few new lines is the quiet failure, so the ambiguity is
 * resolved away from it where the two readings differ at equal length.
 */
export function overlap(prev: readonly string[], next: readonly string[]): number {
  const most = Math.min(prev.length, next.length);
  for (let k = most; k > 0; k--) {
    let same = true;
    for (let i = 0; i < k; i++) {
      if (prev[prev.length - k + i] !== next[i]) {
        same = false;
        break;
      }
    }
    if (same) return k;
  }
  return 0;
}

/**
 * Move [prev] onto [payload]. A null [prev] is the first read: every line is new to this view, and
 * nothing was showing before, so it is not a reset.
 *
 * A non-empty previous tail that shares nothing with the new one is the reset case, whatever caused
 * it (rotation, truncation, a switch of head, a path change): the honest reading is "this window
 * does not continue what you were looking at", never "here are 200 brand new lines".
 */
export function advance(prev: LogTail | null, payload: LogsPayload): TailAdvance {
  const tail = tailOf(payload);
  if (prev === null) return { tail, appended: tail.lines, reset: false };
  const sameStream = prev.key === tail.key && prev.path === tail.path;
  const shared = sameStream ? overlap(prev.lines, tail.lines) : 0;
  if (!sameStream || (shared === 0 && prev.lines.length > 0)) {
    return { tail, appended: tail.lines, reset: true };
  }
  return { tail, appended: tail.lines.slice(shared), reset: false };
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

export const NO_FILTER: LogFilter = { head: null, level: null, substring: '' };

/** The timestamp bracket, then the tag bracket. A line without the timestamp prefix (the daemon's
 *  own `[logs unavailable: ...]` band, a wrapped continuation) has no tag and reads as null. */
const TAG = /^\[[^\]]*\]\s*\[([^\]]*)\]/;

/** An explicit severity marker. Anchored to a whole uppercase word so `failed`, `retrying` and
 *  `failure_share` in prose and in counter fields stay unmarked. */
const SEVERITY = /\b(ERROR|WARN|WARNING|FATAL|INFO|DEBUG|TRACE)\b/;

/** The line's tag, or null when it carries none. Compared exactly: the daemon's tags have no case
 *  or whitespace variants, and a case-insensitive match would make two tags look like one. */
export function headOf(line: string): string | null {
  const match = TAG.exec(line);
  return match === null ? null : match[1];
}

/** The severity the daemon marked on the line, or null when it marked none. */
export function levelOf(line: string): LogLevel | null {
  const match = SEVERITY.exec(line);
  if (match === null) return null;
  const token = match[1];
  return token === 'WARNING' ? 'warn' : (token.toLowerCase() as LogLevel);
}

export function matches(line: string, filter: LogFilter): boolean {
  if (filter.head !== null && headOf(line) !== filter.head) return false;
  if (filter.level !== null && levelOf(line) !== filter.level) return false;
  const needle = filter.substring.trim().toLowerCase();
  return needle === '' || line.toLowerCase().includes(needle);
}

/** The lines [filter] keeps, in the order they arrived. */
export function applyFilter(lines: readonly string[], filter: LogFilter): string[] {
  return lines.filter((line) => matches(line, filter));
}

/** The tags present in [lines], sorted, so the tag control offers only what the tail holds. */
export function headsPresent(lines: readonly string[]): string[] {
  const seen = new Set<string>();
  for (const line of lines) {
    const head = headOf(line);
    if (head !== null) seen.add(head);
  }
  return [...seen].sort();
}

/** The severities present in [lines], most severe first. A tail where the daemon marked nothing
 *  returns empty, and the page says so instead of offering levels nothing can match. */
export function levelsPresent(lines: readonly string[]): LogLevel[] {
  const seen = new Set<LogLevel>();
  for (const line of lines) {
    const level = levelOf(line);
    if (level !== null) seen.add(level);
  }
  return LEVELS.filter((level) => seen.has(level));
}
