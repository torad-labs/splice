// THE KIT'S CHARTS (docs/design/DESIGN.md section 9, "show, then say"): a number that has a shape
// gets its shape, and the words that remain are names. Three rules hold for every component here:
//
//   - COLOUR FOLLOWS PRINCIPLE 1. A mark takes a grey (`series-1..3`) for a kind of thing, a status
//     colour for a state, and a head's hue (`hue`, the `--hue` a HeadMark class binds) only when the
//     series IS a head. Every one of those tokens clears 3:1 on the grounds (tests/contrast.test.ts),
//     which is WCAG 1.4.11's floor for a mark that carries data.
//   - EVERY CHART SAYS ITSELF. A screen reader gets the numbers the shape stands for, through the
//     chart's accessible name; the shape is never the only carrier.
//   - NOTHING MOVES UNDER REDUCED MOTION. The one animation, the braid's pulse, is dropped by the
//     sheet's reduced-motion block, and every transition reads `--dur-N`, which the token sheet sets
//     to 0ms there.
import { useId, useRef } from 'react';
import type { CSSProperties, ReactNode } from 'react';
import { InfoIcon } from '@phosphor-icons/react/dist/csr/Info';
import { cx } from '../lib';
import './charts.css';

/** What colours a mark: a chart grey, a status, or the head hue bound by the nearest `myx-hue-N`. */
export type Mark = 'series-1' | 'series-2' | 'series-3' | 'ok' | 'warn' | 'danger' | 'hue';

const markClass = (mark: Mark): string => `myx-mark-${mark}`;

/** A share of a whole as a CSS percentage, clamped to the box. */
const pct = (part: number, whole: number): string => `${whole <= 0 ? 0 : Math.max(0, Math.min(100, (part / whole) * 100))}%`;

// ---------------------------------------------------------------------------------------- tip

/** A short text shown on hover and on keyboard focus of what it wraps: the help slot of the voice
 *  (one sentence, twelve words or fewer). The wrapped element is the tab stop; the text is its
 *  description, so a screen reader reads it after the element's own name. */
export function Tip({ text, side = 'top', children }: { text: string; side?: 'top' | 'bottom'; children: ReactNode }) {
  const id = useId();
  return (
    <span className={cx('myx-tip', `myx-tip-${side}`)} tabIndex={0} aria-describedby={id}>
      {children}
      <span className="myx-tip-body" role="tooltip" id={id}>{text}</span>
    </span>
  );
}

/** An info mark that explains the thing beside it on hover or focus, in place of a sentence printed
 *  on the page. `label` is its accessible name ("About plan limits"). */
export function InfoTip({ text, label, side = 'top' }: { text: string; label: string; side?: 'top' | 'bottom' }) {
  const id = useId();
  return (
    <span className={cx('myx-tip', 'myx-info', `myx-tip-${side}`)}>
      <button type="button" className="myx-info-btn" aria-label={label} aria-describedby={id}>
        <InfoIcon aria-hidden="true" />
      </button>
      <span className="myx-tip-body" role="tooltip" id={id}>{text}</span>
    </span>
  );
}

// ---------------------------------------------------------------------------------- sparkline

/** A series over time as a line: an hour per point, a gap where nothing was reported (null is not
 *  zero). `label` names the series; the accessible name adds the latest value and the peak. */
export function Sparkline({ values, label, mark = 'series-2', format = String }: {
  values: ReadonlyArray<number | null>;
  label: string;
  mark?: Mark;
  format?: (value: number) => string;
}) {
  const present = values.filter((value): value is number => value !== null);
  if (present.length === 0) {
    return <span className={cx('myx-spark', 'myx-spark-empty', markClass(mark))} role="img" aria-label={`${label}: none`} />;
  }
  const max = Math.max(...present);
  const min = Math.min(0, ...present);
  const span = max - min || 1;
  const last = values.length - 1;
  const x = (at: number): number => (last === 0 ? 50 : (at / last) * 100);
  const y = (value: number): number => 19 - ((value - min) / span) * 18;
  // one run per stretch of reported points: a null breaks the line rather than drawing it to zero
  const runs: string[] = [];
  let run: string[] = [];
  values.forEach((value, at) => {
    if (value === null) {
      if (run.length > 0) runs.push(run.join(' '));
      run = [];
      return;
    }
    run.push(`${run.length === 0 ? 'M' : 'L'}${x(at).toFixed(2)} ${y(value).toFixed(2)}`);
  });
  if (run.length > 0) runs.push(run.join(' '));
  const lastValue = [...values].reverse().find((value): value is number => value !== null) ?? 0;
  return (
    <svg
      className={cx('myx-spark', markClass(mark))}
      viewBox="0 0 100 20"
      preserveAspectRatio="none"
      role="img"
      aria-label={`${label}: ${format(lastValue)} last, ${format(max)} peak`}
    >
      {runs.map((path) => <path key={path} className="myx-spark-line" d={path} vectorEffect="non-scaling-stroke" />)}
    </svg>
  );
}

// -------------------------------------------------------------------------------- stacked bar

export interface BarPart {
  key: string;
  label: string;
  value: number;
  mark: Mark;
}

/** Parts of a whole side by side: in and out tokens, live and stale sessions. `total` leaves the
 *  rest of the bar as track when the parts do not fill it (a budget); `legend` prints each part's
 *  swatch, figure and name under the bar. */
export function StackedBar({ parts, label, total, legend = false, format = String, said }: {
  parts: readonly BarPart[];
  label: string;
  total?: number;
  legend?: boolean;
  format?: (value: number) => string;
  /** The bar's whole accessible name, where its parts' own figures would mislead: a percentile
   *  spread whose second part is a difference, not a figure. */
  said?: string;
}) {
  const sum = parts.reduce((held, part) => held + Math.max(0, part.value), 0);
  const whole = Math.max(total ?? sum, sum);
  const name = said ?? `${label}: ${parts.map((part) => `${part.label} ${format(part.value)}`).join(', ')}`;
  return (
    <span className="myx-stack-wrap">
      <span className="myx-stack" role="img" aria-label={name}>
        {parts.filter((part) => part.value > 0).map((part) => (
          <span key={part.key} className={cx('myx-stack-part', markClass(part.mark))} style={{ width: pct(part.value, whole) }} />
        ))}
      </span>
      {legend ? (
        <span className="myx-stack-legend" aria-hidden="true">
          {parts.map((part) => (
            <span key={part.key} className={cx('myx-stack-key', markClass(part.mark), part.value === 0 && 'myx-stack-key-none')}>
              <span className="myx-stack-swatch" />
              <span className="myx-stack-figure">{format(part.value)}</span>
              <span className="myx-stack-name">{part.label}</span>
            </span>
          ))}
        </span>
      ) : null}
    </span>
  );
}

// --------------------------------------------------------------------------------------- pips

/** A small count against its capacity, one pip per unit, so it can be counted at a glance: a
 *  head's slots in use of its limit. The pips past `used` are track. */
export function Pips({ used, total, label, mark = 'series-1' }: { used: number; total: number; label: string; mark?: Mark }) {
  const units = Math.max(0, Math.floor(total));
  return (
    <span className={cx('myx-pips', markClass(mark))} role="img" aria-label={`${label}: ${used} of ${units}`}>
      {Array.from({ length: units }, (_, at) => (
        <span key={at} className={cx('myx-pip', at < used && 'myx-pip-on')} />
      ))}
    </span>
  );
}

// ------------------------------------------------------------------------------------- legend

/** The marks a set of charts is drawn in, named once for all of them: a table of bars, a column of
 *  waterfalls. A chart that carries its own figures prints its own legend (StackedBar `legend`). */
export function Legend({ items, label }: { items: readonly { mark: Mark; label: string }[]; label: string }) {
  return (
    <span className="myx-legend" role="list" aria-label={label}>
      {items.map((item) => (
        <span key={item.label} className={cx('myx-legend-key', markClass(item.mark))} role="listitem">
          <span className="myx-legend-swatch" aria-hidden="true" />
          {item.label}
        </span>
      ))}
    </span>
  );
}

// --------------------------------------------------------------------------------------- ring

/** A share as a ring, with what it stands for printed in the middle: a cache hit rate, a window
 *  used. `value` is 0..1. */
export function Ring({ value, label, mark = 'series-1', children }: {
  value: number;
  label: string;
  mark?: Mark;
  children?: ReactNode;
}) {
  const share = Math.max(0, Math.min(1, value));
  const said = `${Math.round(share * 100)}%`;
  return (
    <span className={cx('myx-ring', markClass(mark))} role="img" aria-label={`${label} ${said}`}>
      <svg viewBox="0 0 36 36" aria-hidden="true">
        {/* r = 100 / 2π, so the circumference is 100 and the dash is the share itself */}
        <circle className="myx-ring-track" cx="18" cy="18" r="15.9155" />
        <circle className="myx-ring-arc" cx="18" cy="18" r="15.9155" strokeDasharray={`${share * 100} 100`} />
      </svg>
      <span className="myx-ring-value" aria-hidden="true">{children ?? said}</span>
    </span>
  );
}

// ------------------------------------------------------------------------------- lifetime bar

/** A life on a shared time axis: born at `start`, reporting until `seen`, silent since, up to
 *  `now`. Every row on a board shares `from..to`, so the bars line up as a timeline. */
export function LifetimeBar({ start, seen, now, from, to, label, mark = 'hue' }: {
  start: number;
  seen: number;
  now: number;
  from: number;
  to: number;
  label: string;
  mark?: Mark;
}) {
  const span = Math.max(1, to - from);
  const born = Math.max(from, Math.min(start, to));
  const last = Math.max(born, Math.min(seen, to));
  const end = Math.max(last, Math.min(now, to));
  const at = (time: number): number => ((time - from) / span) * 100;
  const place = (left: number, width: number): CSSProperties => ({ left: `${left}%`, width: `${Math.max(0, width)}%` });
  return (
    <span className={cx('myx-life', markClass(mark))} role="img" aria-label={label}>
      <span className="myx-life-run" style={place(at(born), at(last) - at(born))} />
      <span className="myx-life-quiet" style={place(at(last), at(end) - at(last))} />
      <span className="myx-life-seen" style={{ left: `${at(last)}%` }} />
    </span>
  );
}

// ---------------------------------------------------------------------------------- waterfall

export interface WaterfallStage {
  key: string;
  label: string;
  /** ms since the turn arrived, at the stage's start and end. */
  start: number;
  end: number;
  mark: Mark;
}

/** Where a turn's time went, as segments on one axis. `scale` is the ms the full width stands for,
 *  shared by every row it sits beside so turns compare; a stage the row did not report is a gap. */
export function Waterfall({ stages, scale, label }: { stages: readonly WaterfallStage[]; scale: number; label: string }) {
  const said = stages.filter((stage) => stage.end > stage.start)
    .map((stage) => `${stage.label} ${Math.round(stage.end - stage.start)} ms`).join(', ');
  return (
    <span className="myx-wf" role="img" aria-label={said === '' ? label : `${label}: ${said}`}>
      {/* A stage that took no time has no width to draw: the segment's floor would paint it. */}
      {stages.filter((stage) => stage.end > stage.start).map((stage) => (
        <span
          key={stage.key}
          className={cx('myx-wf-seg', markClass(stage.mark))}
          style={{ left: pct(stage.start, scale), width: pct(stage.end - stage.start, scale) }}
        />
      ))}
    </span>
  );
}

// ---------------------------------------------------------------------------------- layer chip

/** Where a value comes from, as a stack of layers with the winning one lit: the layers under it
 *  are overridden (outlined), the ones over it are unset (track). `names` are the layers in order,
 *  lowest first; `active` is the winner's index. The winner's name is the chip's text equivalent
 *  and shows on hover and focus. */
export function LayerChip({ names, active, label }: { names: readonly string[]; active: number; label: string }) {
  const winner = names[active] ?? '';
  return (
    <Tip text={`${label}: ${winner}`}>
      <span className="myx-layers" role="img" aria-label={`${label}: ${winner}`}>
        {names.map((name, at) => (
          <span
            // by position: two layers may share a name (a console save writes two of them)
            key={`${at}:${name}`}
            className={cx('myx-layer', at < active && 'myx-layer-under', at === active && 'myx-layer-on')}
          />
        ))}
      </span>
    </Tip>
  );
}

// -------------------------------------------------------------------------------------- braid

export interface Strand {
  key: string;
  /** The head's name, printed on hover and focus and read by a screen reader. */
  name: string;
  /** The class that binds the head's `--hue` (a `myx-hue-N` from @entities/control-status). */
  hue: string;
  /** Turns in flight on this head right now. */
  count: number;
  /** A counter that grows when a turn lands (the gate's `released`): a change replays the pulse. */
  landed: number;
}

/** The most turns a strand draws as dots; past it the total beside the braid still counts them all. */
const STRAND_DOTS = 8;

/** One head's strand: a lane in miniature, a line in the head's hue with a dot per turn in flight.
 *  The pulse plays when `landed` moves past what this strand first saw, never on the first paint,
 *  so opening the console does not flash every head at once. */
function StrandView({ strand, unit }: { strand: Strand; unit: string }) {
  const first = useRef(strand.landed);
  return (
    <Tip text={`${strand.name} ${strand.count} ${unit}`} side="bottom">
      <span
        className={cx('myx-strand', strand.hue, strand.count === 0 && 'myx-strand-idle')}
        role="img"
        aria-label={`${strand.name}: ${strand.count} ${unit}`}
      >
        {/* keyed on the landed count: a new key remounts the line, and its pulse plays again */}
        <span key={strand.landed} className={cx('myx-strand-line', strand.landed !== first.current && 'myx-strand-pulse')} />
        {Array.from({ length: Math.min(strand.count, STRAND_DOTS) }, (_, at) => (
          <span key={at} className="myx-strand-dot" style={{ '--at': at } as CSSProperties} />
        ))}
      </span>
    </Tip>
  );
}

/** The lanes in miniature (operator ruling 4, item 5): one strand per head, stacked in registry
 *  order as the sessions page stacks its lanes, each carrying a dot per turn in flight on that
 *  head. An idle head keeps its strand, so a head that is up and quiet still shows. A strand pulses
 *  once when one of its turns lands. The total prints beside the braid, so the strip reads in
 *  greyscale. */
export function Braid({ strands, label, unit }: { strands: readonly Strand[]; label: string; unit: string }) {
  const total = strands.reduce((held, strand) => held + strand.count, 0);
  return (
    <span className="myx-braid" role="group" aria-label={`${label}: ${total} ${unit}`}>
      <span className="myx-braid-strands" style={{ '--strands': Math.max(strands.length, 1) } as CSSProperties}>
        {strands.map((strand) => <StrandView key={strand.key} strand={strand} unit={unit} />)}
      </span>
      <span className="myx-braid-total" aria-hidden="true">{total}</span>
    </span>
  );
}
