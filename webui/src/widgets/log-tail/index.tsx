// The daemon log as strips: one strip per line, a field grid for when, which tag and which
// severity, and the line itself.
//
// The list is virtualized (@tanstack/react-virtual): a 2000-line tail of monospace strips is tens
// of thousands of DOM nodes otherwise, and this page is meant to be left open. Only the rows in
// view exist, which is why the strips are uniform height and the line's text clips with an
// ellipsis instead of wrapping.
//
// Severity is the holder edge AND a printed word (the world's rule): an ERROR line carries a red
// edge with `error` printed on it, a WARN line amber, everything the daemon left unmarked grey.
// Color never carries the state alone.
import { useEffect, useRef } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';
import { headOf, levelOf, timeOf } from '@entities/logs';
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

/** One log line as a printed strip. Exported because a virtualized list renders nothing without a
 *  viewport, so this is the part a test can hold. */
export function LogLine({ line }: { line: string }) {
  const level = levelOf(line);
  return (
    <Strip edge={edgeOfLevel(level)} edgeLabel={level ?? S.line} ariaLabel={line.slice(0, 120)}>
      <StripField w={11} label={S.time} value={timeOf(line) ?? '-'} />
      <StripField w={18} label={S.head} value={headOf(line) ?? '-'} mono={false} />
      <StripField w={8} label={S.level} value={level ?? '-'} mono={false} />
      <StripField w={160} label={S.text} value={line} />
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
  error?: string | null;
  /** `| undefined` on the optional callbacks: this tree runs `exactOptionalPropertyTypes`, so a
   *  caller that forwards its own optional prop must be able to pass the undefined through. */
  onFilter?: ((filter: LogFilter) => void) | undefined;
  onFollow?: ((follow: boolean) => void) | undefined;
}

export function LogTail({ payload, filter, appended, reset, follow, error = null, onFilter, onFollow }: LogTailProps) {
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
        <StripField w={44} label={S.path} value={payload?.path ?? '-'} />
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
        <Figure value={appended} unit={S.newLines} basis="measured" />
      </header>

      {filtered.length === 0 ? (
        <Empty text="no lines in this tail" source={payload?.path ?? '/api/logs/{head}'} />
      ) : (
        <div className="myx-lt-scroll" ref={scrollRef}>
          <div className="myx-lt-inner" style={{ height: virtualizer.getTotalSize() }}>
            {virtualizer.getVirtualItems().map((item) => (
              <div
                key={item.key}
                className="myx-lt-row"
                data-index={item.index}
                ref={virtualizer.measureElement}
                style={{ transform: `translateY(${item.start}px)` }}
              >
                <LogLine line={filtered[item.index]} />
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
