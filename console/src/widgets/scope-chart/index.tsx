// The scope charts: one dark inset per question, each drawn as stacked bars from the daemon's
// hourly sums, each with a legend that PRINTS its numbers.
//
// WHY THE SERIES ARE ONE COLOUR. A hue in this console means a head (docs/design/DESIGN.md section
// 5), and status colours mean attention, so neither is free to name a kind of token: a stack in
// four hues would claim four heads. A stack steps through ONE colour at four opacities instead,
// the head's own colour when a head's section binds `--hue` around the chart, and the legend beside
// it names each step and prints its total, which keeps the shape readable without colour.
//
// WHY RECTS ONLY. The SVG stretches to its frame with preserveAspectRatio="none", which distorts
// anything that is not an axis-aligned rectangle. Bars survive it; a stroke or a circle would not.
import type { EconomicsBucket } from '@shared/api';
import { costOf, sum } from '@entities/economics';
import type { CostRates } from '@entities/economics';
import { fmtBytes, fmtInt, fmtTokens } from '@shared/lib';
import { ScopeInset } from '@shared/ui';
import { limitedRows, peakMax, peakOf, tokenRows, byteRows, toolRows, totalOf, windowHours } from './model';
import type { ChartWindow, HourRow } from './model';
import { S } from './strings';
import './scope-chart.css';

// The mapping is public: a page that wants a number the charts do not draw asks the same functions,
// so the number and the bars beside it can never disagree about which hours they cover.
export { limitedRows, peakMax, peakOf, tokenRows, byteRows, toolRows, totalOf, windowHours, WINDOWS } from './model';
export type { ChartWindow, HourRow } from './model';

/** One unit per column of the viewBox; the bar takes three of the four and the gap the rest. */
const COLUMN_UNITS = 4;
const SVG_HEIGHT = 100;

interface Series {
  key: string;
  label: string;
}

/** `stack` for series that are parts of a whole; `group` for series that are two readings of the
 *  same thing, which sit side by side on their own baseline and never claim to sum. */
type BarMode = 'stack' | 'group';

function StackedBars({ rows, series, ariaLabel, mode = 'stack' }: {
  rows: readonly HourRow[];
  series: readonly Series[];
  ariaLabel: string;
  mode?: BarMode;
}) {
  const keys = series.map((entry) => entry.key);
  const peak = mode === 'group' ? peakMax(rows, keys) : peakOf(rows, keys);
  const slotWidth = mode === 'group' ? (COLUMN_UNITS - 1) / series.length : COLUMN_UNITS - 1;

  return (
    <svg
      className="myx-schart"
      viewBox={`0 0 ${Math.max(rows.length, 1) * COLUMN_UNITS} ${SVG_HEIGHT}`}
      preserveAspectRatio="none"
      role="img"
      aria-label={ariaLabel}
    >
      {rows.map((row, index) => {
        let stacked = 0;
        return series.map((entry, slot) => {
          const value = row.values[entry.key] ?? 0;
          const height = (value / peak) * SVG_HEIGHT;
          const rect = (
            <rect
              key={`${row.at}-${entry.key}`}
              className={`myx-schart-seg myx-schart-${entry.key}`}
              x={index * COLUMN_UNITS + (mode === 'group' ? slot * slotWidth : 0)}
              y={mode === 'group' ? SVG_HEIGHT - height : SVG_HEIGHT - stacked - height}
              width={slotWidth}
              height={height}
            />
          );
          stacked += height;
          return rect;
        });
      })}
    </svg>
  );
}

/** The legend: a swatch, the series name, and the number. The number is the point. */
function Legend({ rows, series, format = fmtTokens }: {
  rows: readonly HourRow[];
  series: readonly Series[];
  format?: (value: number) => string;
}) {
  return (
    <div className="myx-schart-legend">
      {series.map((entry) => (
        <div className="myx-schart-key" key={entry.key}>
          <span className={`myx-swatch myx-swatch-${entry.key}`} aria-hidden="true" />
          <span>{entry.label}</span>
          <span className="myx-schart-value">{format(totalOf(rows, entry.key))}</span>
        </div>
      ))}
    </div>
  );
}

const TOKENS: readonly Series[] = [
  { key: 'fresh', label: S.fresh },
  { key: 'cached', label: S.cached },
  { key: 'write', label: S.write },
  { key: 'out', label: S.out },
];

const BYTES: readonly Series[] = [
  { key: 'upstream', label: S.upstream },
  { key: 'request', label: S.request },
];

const TOOLS: readonly Series[] = [
  { key: 'eager', label: S.eager },
  { key: 'deferred', label: S.deferred },
];

const LIMITED: readonly Series[] = [{ key: 'limited', label: S.limitedCount }];

/** The window's buckets: exactly the hours the bars draw, taken from the same walk. */
function slice(buckets: readonly EconomicsBucket[], window: ChartWindow, now: number): EconomicsBucket[] {
  const wanted = new Set(windowHours(window.hours, now));
  return buckets.filter((bucket) => wanted.has(bucket.hour));
}

export function TokenChart({ buckets, window, now }: {
  buckets: readonly EconomicsBucket[];
  window: ChartWindow;
  now: number;
}) {
  const rows = tokenRows(buckets, window.hours, now);
  return (
    <ScopeInset title={`${S.tokens} ${window.label}`} basis="measured">
      <StackedBars rows={rows} series={TOKENS} ariaLabel={S.tokens} />
      <Legend rows={rows} series={TOKENS} />
    </ScopeInset>
  );
}

/**
 * Cost, from the declared rates. Basis `estimated`, never `measured`: the tokens are the daemon's and
 * exact, but the dollars are this console multiplying them by a card the operator wrote in the
 * topology — and a vendor's real invoice can differ. With no rates declared there is no dollar
 * figure at all, which is what the honest empty says rather than a confident zero.
 */
export function CostChart({ buckets, window, now, rates }: {
  buckets: readonly EconomicsBucket[];
  window: ChartWindow;
  now: number;
  rates: CostRates | null;
}) {
  if (rates === null) {
    return (
      <ScopeInset title={`${S.cost} ${window.label}`} basis="unavailable">
        <p className="myx-schart-empty">{S.noRates}</p>
      </ScopeInset>
    );
  }
  const inWindow = slice(buckets, window, now);
  const totals = sum(inWindow);
  const usd = costOf(totals, rates);
  return (
    <ScopeInset title={`${S.cost} ${window.label}`} basis="estimated">
      <p className="myx-schart-figure">{`$${usd.toFixed(4)}`}</p>
      <p className="myx-schart-note">{`${fmtTokens(totals.inTokens)} in, ${fmtTokens(totals.outTokens)} out`}</p>
    </ScopeInset>
  );
}

export function ByteChart({ buckets, window, now }: {
  buckets: readonly EconomicsBucket[];
  window: ChartWindow;
  now: number;
}) {
  const rows = byteRows(buckets, window.hours, now);
  return (
    <ScopeInset title={`${S.bytes} ${window.label}`} basis="measured">
      <StackedBars rows={rows} series={BYTES} ariaLabel={S.bytes} mode="group" />
      <Legend rows={rows} series={BYTES} format={fmtBytes} />
    </ScopeInset>
  );
}

export function ToolChart({ buckets, window, now }: {
  buckets: readonly EconomicsBucket[];
  window: ChartWindow;
  now: number;
}) {
  const rows = toolRows(buckets, window.hours, now);
  return (
    <ScopeInset title={`${S.tools} ${window.label}`} basis="measured">
      <StackedBars rows={rows} series={TOOLS} ariaLabel={S.tools} />
      <Legend rows={rows} series={TOOLS} format={(value) => fmtInt(value)} />
    </ScopeInset>
  );
}

export function LimitedChart({ buckets, window, now }: {
  buckets: readonly EconomicsBucket[];
  window: ChartWindow;
  now: number;
}) {
  const rows = limitedRows(buckets, window.hours, now);
  return (
    <ScopeInset title={`${S.limited} ${window.label}`} basis="measured">
      <StackedBars rows={rows} series={LIMITED} ariaLabel={S.limited} />
      <Legend rows={rows} series={LIMITED} format={(value) => fmtInt(value)} />
    </ScopeInset>
  );
}
