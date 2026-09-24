// The daemon log as strips: one strip per line, a field grid for when, which tag and which
// severity, and the line itself.
//
// The list is virtualized (@tanstack/react-virtual): a 2000-line tail of monospace strips is tens
// of thousands of DOM nodes otherwise, and this page is meant to be left open. Only the rows in
// view exist, which is why the strips are uniform height and the line's text clips with an
// ellipsis instead of wrapping.
//
// Severity is the holder edge AND a printed word (the world's rule): an ERROR line carries a red
// edge with `error` printed on it, a WARN line amber, everything the daemon left unmarked grey and
// wordless. Color never carries the state alone. There is no level column: it printed the edge's
// word a second time, and `-` on the 996 of 1,000 live lines the daemon leaves unmarked.
import { useEffect, useRef } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';
import { dateOf, headOf, levelOf, timeOf } from '@entities/logs';
import type { LogFilter, LogLevel, LogsPayload } from '@entities/logs';
import { Fault, Flag } from '@shared/controls';
import { Empty, Figure, Strip, StripField } from '@shared/ui';
import type { Edge } from '@shared/ui';
import { S } from './strings';
import './log-tail.css';

const ROW_H = 30;

/** One line's edge: the daemon's own severity, or the quiet grey of a line it left unmarked. */
export function edgeOfLevel(level: LogLevel | null): Edge {
  if (level === 'error' || level === 'fatal') return 'red';
  if (level === 'warn') return 'amber';
  return 'grey';
}

/** The line with the two brackets its own row already prints removed (M2-30).
 *
 *  A row was reading `01:14:02 | claude-deepseek | - | [2026-09-18 01:14:02] [claude-deepseek]
 *  turn compact=false ...` -- the timestamp twice and the head twice, on every line in the tail,
 *  costing 277px of the one column whose whole job is to be read. Measured rack-wide: the prefix is
 *  32 to 40 characters of every line.
 *
 *  NEITHER BRACKET CARRIES ANYTHING THE ROW LOSES. `headOf` returns the second bracket verbatim, so
 *  the head cell IS that string. The first bracket's time is the time cell, and its DATE is not an
 *  omission to repair here: `timeOf`'s own comment states the console prints the time and never the
 *  date, because "a log tail is read as when in the session did this happen". This removes what is
 *  duplicated and what the page has already decided not to print, and nothing else.
 *
 *  It mirrors entities/logs' TAG and TIME anchors rather than re-parsing: strip a leading bracket
 *  only when that parser found one there, so a continuation line with no timestamp is untouched. */
export function messageOf(line: string): string {
  if (headOf(line) !== null) return line.replace(/^\[[^\]]*\]\s*\[[^\]]*\]\s*/, '');
  if (timeOf(line) !== null) return line.replace(/^\[[^\]]*\]\s*/, '');
  return line;
}

/** One log line as a printed strip. Exported because a virtualized list renders nothing without a
 *  viewport, so this is the part a test can hold. */
/* THE RACK PRINTS ITS COLUMNS ONCE (M3-04, the finish review's item 5; m1 design review B9). Every
   one of the tail's lines carried its own `time head level text` row, fifteen times down a capture,
   and the rack is homogeneous -- one row shape -- which is the case StripField's own `label` doc
   names for omitting it. The names print once, on the strip below, above the scroll.
   AND THE LINE WRAPS INSIDE ITS CELL. The text cell was 160ch in a rack that scrolled sideways, so
   at 1536 every long line was cut at the bay's edge (`first_byt…`) and read only by scrolling. The
   virtualizer measures each row, so a wrapped line takes its own height; the cell declares a modest
   ch count and takes the rack's slack, and the other three keep the grid. */
const COLS = { time: 15, head: 18, text: 60 } as const;

const MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec'];

/** The reader's own day as the daemon stamps it (local, `YYYY-MM-DD`). */
function localDay(now: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

/** When a line was written: the time for a line from today, `sep 22 10:47:14` for any other day.
 *  A tail spans days (the daemon's log rotates by size), and a time alone read 21:48 above 01:16
 *  with nothing to say a midnight fell between them. */
export function whenOf(line: string, now = new Date()): string {
  const time = timeOf(line);
  if (time === null) return '';
  const day = dateOf(line);
  if (day === null || day === localDay(now)) return time;
  return `${MONTHS[Number(day.slice(5, 7)) - 1]} ${Number(day.slice(8, 10))} ${time}`;
}

/** The rack's column names, once, on a strip of the same grid as the lines under it. The head
 *  column prints only when the tail carries more than one head's lines: a head's own log is all
 *  its own tag, and the column repeated the head picked above it on every row. */
export function LogColumns({ tagged = true }: { tagged?: boolean }) {
  return (
    <Strip className="myx-lt-cols" edge="grey" edgeLabel="" ariaLabel="log columns">
      <StripField w={COLS.time} label={S.time} value="" />
      {tagged ? <StripField w={COLS.head} label={S.head} value="" /> : null}
      <StripField w={COLS.text} label={S.text} value="" />
    </Strip>
  );
}

export function LogLine({ line, tagged = true }: { line: string; tagged?: boolean }) {
  const level = levelOf(line);
  // ariaLabel keeps the WHOLE line: the cell drops what the row prints beside it, and a screen
  // reader reading the row aloud should still get the daemon's line as the daemon wrote it.
  return (
    <Strip edge={edgeOfLevel(level)} edgeLabel={level ?? ''} ariaLabel={line.slice(0, 120)}>
      <StripField w={COLS.time} value={whenOf(line)} />
      {tagged ? <StripField w={COLS.head} value={headOf(line) ?? ''} mono={false} /> : null}
      <StripField w={COLS.text} value={messageOf(line)} />
    </Strip>
  );
}

export interface LogTailProps {
  payload: LogsPayload | null;
  filter: LogFilter;
  /** Lines that arrived since the last poll: what follow mode is reacting to. */
  appended: number;
  reset: boolean;
  follow: boolean;
  /** Whether the tail carries more than one head's lines, so the head column says something. */
  tagged?: boolean;
  error?: string | null;
  /** `| undefined` on the optional callbacks: this tree runs `exactOptionalPropertyTypes`, so a
   *  caller that forwards its own optional prop must be able to pass the undefined through. */
  onFilter?: ((filter: LogFilter) => void) | undefined;
  onFollow?: ((follow: boolean) => void) | undefined;
}

export function LogTail({ payload, filter, appended, reset, follow, tagged = true, error = null, onFilter, onFollow }: LogTailProps) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const filtered = payload === null ? [] : payload.lines;

  const virtualizer = useVirtualizer({
    count: filtered.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => ROW_H,
    getItemKey: (index) => `${index}:${filtered[index].slice(0, 40)}`,
    overscan: 8,
  });

  // Follow mode: keep the last line in view when new ones land. Off, the reader keeps their place
  // and the header prints how many lines arrived while they were not looking.
  useEffect(() => {
    if (!follow || filtered.length === 0) return;
    virtualizer.scrollToIndex(filtered.length - 1, { align: 'end' });
  }, [follow, filtered.length, virtualizer, reset]);

  if (error !== null) return <Fault message={error} />;

  return (
    <div className="myx-lt">
      <header className="myx-lt-head">
        <StripField w={44} label={S.path} value={payload?.path ?? ''} />
        {onFilter === undefined ? null : (
          <div className="myx-lt-controls">
            <label className="myx-lt-field">
              <span className="myx-lt-field-label">{S.search}</span>
              <input
                value={filter.substring}
                onChange={(event) => onFilter({ ...filter, substring: event.target.value })}
                autoComplete="off"
                spellCheck={false}
              />
            </label>
          </div>
        )}
        {/* THE FOLLOW TOGGLE IS THE WORLD'S FLAG (M1-103). It was an `<input type="checkbox">` --
            system-blue browser chrome inside the console, the defect Flag was written for and names
            in its own header ("the log tail still rendered a system-blue checkbox"). The PLACEMENT
            was not made because the widget predates the primitive, which is D7's prediction come
            due. Flag renders a button with role="switch" and aria-checked, so it keeps the native
            checkbox's contract -- Enter and Space toggle it, a reader hears a switch -- while the
            state prints as the paper trap: a HolderEdge, green while it follows and grey while it
            does not, with the word beside it. Neither signal is colour alone. */}
        {onFollow === undefined ? null : (
          <Flag on={follow} onLabel={S.follow} offLabel={S.paused} onChange={onFollow} />
        )}
        {/* Only while paused: following, every line is already in view, and the count read `200 new
            lines` on the first read of a tail the reader had just opened. */}
        {follow ? null : <Figure value={appended} unit={S.newLines} basis="measured" />}
      </header>

      {filtered.length === 0 ? (
        <Empty
          text={payload === null ? 'reading the log' : 'no lines to show'}
          source={payload === null ? "the last lines of this head's log show here" : `${payload.path} is empty, or no line matches the filters`}
        />
      ) : (
        <>
        <LogColumns tagged={tagged} />
        <div className="myx-lt-scroll" ref={scrollRef}>
          <div className="myx-lt-inner" style={{ height: virtualizer.getTotalSize() }}>
            {virtualizer.getVirtualItems().map((item) => (
              <div
                key={item.key}
                className="myx-lt-row"
                data-index={item.index}
                ref={virtualizer.measureElement}
                // `top`, not a translate (M3-04): a row's time, head and level stick while any of the
                // row is in view (log-tail.css), and sticky resolves in layout space, where a
                // translated row still sits at 0 and every value was pushed to its cell's floor
                style={{ top: item.start }}
              >
                <LogLine line={filtered[item.index]} tagged={tagged} />
              </div>
            ))}
          </div>
        </div>
        </>
      )}
    </div>
  );
}
