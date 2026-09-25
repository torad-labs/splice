// The daemon log as strips: one strip per line, a field grid for when, which tag and which
// severity, and the line itself.
//
// The list is virtualized (@tanstack/react-virtual): a 2000-line tail of monospace strips is tens
// of thousands of DOM nodes otherwise, and this page is meant to be left open. Only the rows in
// view exist, which is why the strips are uniform height and the line's text clips with an
// ellipsis instead of wrapping.
//
// Severity is a dot AND a printed word: an ERROR line carries a red dot with `error` beside it and a
// faint red tint, a WARN line amber, and a line the daemon left unmarked carries no mark at all.
// Colour never carries the state alone. There is no level column: it printed the word a second
// time, and `-` on the 996 of 1,000 live lines the daemon leaves unmarked.
//
// The stream wears its head (DESIGN.md section 5): the daemon's /api/logs/{head} answers with that
// head's lines only, so the head column prints only for a tail that carries several tags, and the
// head's colour rides on the stream's bar instead, the band a head's run takes on the sessions
// board.
//
// A perf line is drawn, not printed (perf-line.tsx): the turn's waterfall, its cache hit and its
// tokens, on one scale shared by every perf line in the tail, with the daemon's own line one click
// away. The rows are measured, so an opened line simply grows its row.
import { useEffect, useMemo, useRef, useState } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';
import { dateOf, headOf, levelOf, timeOf } from '@entities/logs';
import type { LogLevel, LogsPayload } from '@entities/logs';
import { HeadMark, hueClass, useHue } from '@entities/control-status';
import { Fault, Flag } from '@shared/controls';
import { cx, MONTHS } from '@shared/lib';
import { Badge, Empty, Legend } from '@shared/ui';
import type { Tone } from '@shared/ui';
import { LEGEND, PerfCells, perfOf, scaleOf } from './perf-line';
import type { PerfLine, PerfScale } from './perf-line';
import { H, S, U } from './strings';
import './log-tail.css';

export { cacheHitOf, perfOf, scaleOf, totalOf } from './perf-line';
export type { PerfLine, PerfScale } from './perf-line';

const ROW_H = 28;

/** One line's status: the daemon's own severity, or no mark at all on a line it left unmarked. */
export function toneOfLevel(level: LogLevel | null): Tone | null {
  if (level === 'error' || level === 'fatal') return 'danger';
  if (level === 'warn') return 'warn';
  return level === null ? null : 'neutral';
}

/** A message split into its `key=value` pairs and the prose between them, so a perf line reads as
 *  fields: the key in the quiet ink and the value in full ink (DESIGN.md section 7). */
export function partsOf(message: string): Array<{ text: string; key?: string }> {
  const parts: Array<{ text: string; key?: string }> = [];
  const pair = /([A-Za-z_][\w.-]*)=(\S+)/g;
  let at = 0;
  for (const match of message.matchAll(pair)) {
    const index = match.index ?? 0;
    if (index > at) parts.push({ text: message.slice(at, index) });
    parts.push({ key: match[1], text: match[2] });
    at = index + match[0].length;
  }
  if (at < message.length) parts.push({ text: message.slice(at) });
  return parts;
}

function Message({ parts }: { parts: ReadonlyArray<{ text: string; key?: string }> }) {
  return (
    <>
      {parts.map((part, at) => (part.key === undefined
        ? <span key={at}>{part.text}</span>
        : (
          <span key={at} className="myx-lt-pair">
            <span className="myx-lt-key">{part.key}=</span>
            <span className="myx-lt-value">{part.text}</span>
          </span>
        )))}
    </>
  );
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

/** The stream's column names, once, above the lines. The head column prints only when the tail
 *  carries more than one head's lines: a head's own log is all its own tag. */
export function LogColumns({ tagged = true }: { tagged?: boolean }) {
  return (
    <div className={cx('myx-lt-line', 'myx-lt-cols', tagged && 'myx-lt-tagged')} aria-hidden="true">
      <span className="myx-lt-time">{S.time}</span>
      {tagged ? <span className="myx-lt-head">{S.head}</span> : null}
      <span className="myx-lt-text">{S.text}</span>
    </div>
  );
}

/** A perf line's facts, or null for any other line: what the tail reads once per line to draw it
 *  and to size the shared scale. */
export function perfOfLine(line: string): PerfLine | null {
  const message = messageOf(line);
  return perfOf(message, partsOf(message));
}

/** One log line. Exported because a virtualized list renders nothing without a viewport, so this
 *  is the part a test can hold. A perf line draws its turn against `scale` (its own when the caller
 *  has none) and shows the daemon's line under it while `open`. */
export function LogLine({ line, tagged = true, scale, open = false, onToggle }: {
  line: string;
  tagged?: boolean;
  scale?: PerfScale;
  open?: boolean;
  onToggle?: (() => void) | undefined;
}) {
  const level = levelOf(line);
  const tone = toneOfLevel(level);
  const head = headOf(line);
  const message = messageOf(line);
  const parts = partsOf(message);
  const perf = perfOf(message, parts);
  return (
    <div className={cx('myx-lt-line', tagged && 'myx-lt-tagged', tone !== null && `myx-lt-${tone}`)} aria-label={line.slice(0, 120)}>
      <span className="myx-lt-time">{whenOf(line)}</span>
      {tagged ? <span className="myx-lt-head">{head === null ? null : <HeadMark head={head} />}</span> : null}
      {perf === null ? (
        <span className="myx-lt-text">
          {level === null || tone === null ? null : <Badge tone={tone} quiet>{level}</Badge>}
          <Message parts={parts} />
        </span>
      ) : (
        <PerfCells perf={perf} scale={scale ?? scaleOf([perf])} open={open} onToggle={onToggle} raw={<Message parts={parts} />} />
      )}
    </div>
  );
}



export interface LogTailProps {
  payload: LogsPayload | null;
  /** Lines that arrived since the last poll: what follow mode is reacting to. */
  appended: number;
  reset: boolean;
  follow: boolean;
  /** Whether the tail carries more than one head's lines, so the head column says something. */
  tagged?: boolean;
  /** The head this tail is read from: its mark and colour head the stream. */
  head?: string | null;
  error?: string | null;
  /** `| undefined` on the optional callback: this tree runs `exactOptionalPropertyTypes`, so a
   *  caller that forwards its own optional prop must be able to pass the undefined through. */
  onFollow?: ((follow: boolean) => void) | undefined;
}

export function LogTail({ payload, appended, reset, follow, tagged = true, head = null, error = null, onFollow }: LogTailProps) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const hue = useHue(head ?? '');
  const filtered = payload === null ? [] : payload.lines;
  // One scale for every perf line in view of the filter, so the rows compare; the lines a reader
  // opened, by their own text, since a virtualized row forgets its state when it scrolls away.
  const scale = useMemo(
    () => scaleOf(filtered.flatMap((line) => perfOfLine(line) ?? [])),
    [filtered],
  );
  const [opened, setOpened] = useState<ReadonlySet<string>>(() => new Set());
  const toggle = (line: string) => setOpened((previous) => {
    const next = new Set(previous);
    if (!next.delete(line)) next.add(line);
    return next;
  });

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
    <div className={cx('myx-lt', head !== null && 'myx-lt-hued', head !== null && hueClass(hue))}>
      <header className="myx-lt-bar">
        {head === null ? null : <HeadMark head={head} />}
        <span className="myx-lt-path">{payload?.path ?? ''}</span>
        {scale.ms > 0 ? <Legend items={LEGEND} label={S.legend} /> : null}
        {/* Only while paused: following, every line is already in view. */}
        {follow ? null : <Badge tone="neutral">{`${appended} ${U.newLines}`}</Badge>}
        {onFollow === undefined ? null : (
          <Flag on={follow} onLabel={S.follow} offLabel={S.paused} onChange={onFollow} />
        )}
      </header>

      {filtered.length === 0 ? (
        <Empty
          text={payload === null ? S.reading : S.noLines}
          source={payload === null ? H.reading : H.noLines}
        />
      ) : (
        <>
        <LogColumns tagged={tagged} />
        {/* Scrolled off the top, the first rows fade under the column names instead of being sliced
            by them: a cut row reads as more above, not as a broken one. */}
        <div className={cx('myx-lt-scroll', (virtualizer.scrollOffset ?? 0) > 0 && 'myx-lt-scrolled')} ref={scrollRef}>
          <div className="myx-lt-inner" style={{ height: virtualizer.getTotalSize() }}>
            {virtualizer.getVirtualItems().map((item) => (
              <div
                key={item.key}
                className="myx-lt-row"
                data-index={item.index}
                ref={virtualizer.measureElement}
                style={{ top: item.start }}
              >
                <LogLine
                  line={filtered[item.index]}
                  tagged={tagged}
                  scale={scale}
                  open={opened.has(filtered[item.index])}
                  onToggle={() => toggle(filtered[item.index])}
                />
              </div>
            ))}
          </div>
        </div>
        </>
      )}
    </div>
  );
}
