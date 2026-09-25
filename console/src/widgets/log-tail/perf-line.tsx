// A perf line drawn as the turn it records (docs/design/DESIGN.md section 9, "show, then say"): the
// daemon writes one per turn, `perf outcome=ok model=... recv=2 ... total=869 | ... in_tokens=...`,
// and as text it was forty key=value pairs a reader had to add up. Drawn, it is where the time went
// (the waterfall), how much of the prompt the cache served, and the tokens in and out, each against
// the rest of the tail so a slow or heavy turn stands out without reading a number.
//
// The marks are the daemon's own (PerfKeys, ms since arrival) and go through the same waterfall()
// as the turns page, so the two surfaces cannot draw one turn two ways. A mark the line did not carry
// is absent, never zero: waterfall() gives its time to the next mark stamped.
//
// The colours are greys, because a stage is a kind of thing and not a head (DESIGN.md section 9):
// the proxy's own work is the lightest, a wait (for a slot, for the provider) the middle, the reply
// streaming the strongest. Neighbouring stages never share a grey, and the legend on the tail's bar
// names the three.
import type { ReactNode } from 'react';
import { CaretRightIcon } from '@phosphor-icons/react/dist/csr/CaretRight';
import { MARK_KEYS, waterfall } from '@entities/perf';
import type { MarkKey, StageGroup, TurnRow } from '@entities/perf';
import { ABSENT, cx, fmtMs, fmtShare, fmtTokens } from '@shared/lib';
import { Badge, Meter, Waterfall } from '@shared/ui';
import type { Mark, WaterfallStage } from '@shared/ui';
import { S, U } from './strings';

/** One perf line's facts: the turn's marks, and the counters the row draws. A counter the line did
 *  not carry is null. */
export interface PerfLine {
  outcome: string;
  model: string | null;
  compact: boolean;
  marks: Pick<TurnRow, MarkKey>;
  inTokens: number | null;
  cachedTokens: number | null;
  outTokens: number | null;
}

/** What the row's bars are drawn against: the largest of each over the lines in the tail, so every
 *  row shares one scale. */
export interface PerfScale {
  ms: number;
  inTokens: number;
  outTokens: number;
}

/** The grey of each part of a turn: work light, waits middle, the reply strongest. */
const MARK_OF: Record<StageGroup, Mark> = {
  ingest: 'series-3',
  queue: 'series-2',
  upstream: 'series-2',
  stream: 'series-1',
  finish: 'series-3',
};

/** The legend's three greys, in the order a turn meets them. */
export const LEGEND: ReadonlyArray<{ mark: Mark; label: string }> = [
  { mark: 'series-3', label: S.work },
  { mark: 'series-2', label: S.waiting },
  { mark: 'series-1', label: S.streaming },
];

const count = (text: string | undefined): number | null => {
  if (text === undefined) return null;
  const value = Number(text);
  return Number.isFinite(value) ? value : null;
};

/** A log message read as a perf line, or null for any other line. `pairs` is the message's
 *  key=value split, which the tail already makes for the raw view. */
export function perfOf(message: string, pairs: ReadonlyArray<{ text: string; key?: string }>): PerfLine | null {
  if (!message.startsWith('perf ')) return null;
  const fields = new Map<string, string>();
  for (const pair of pairs) if (pair.key !== undefined && !fields.has(pair.key)) fields.set(pair.key, pair.text);
  const marks: Pick<TurnRow, MarkKey> = {};
  for (const key of MARK_KEYS) {
    const at = count(fields.get(key));
    if (at !== null) marks[key] = at;
  }
  return {
    outcome: fields.get('outcome') ?? '?',
    model: fields.get('model') ?? null,
    compact: fields.get('compact') === 'true',
    marks,
    inTokens: count(fields.get('in_tokens')),
    cachedTokens: count(fields.get('cached_tokens')),
    outTokens: count(fields.get('out_tokens')),
  };
}

/** The turn's length: the daemon's closing tally, else the last mark it stamped. */
export function totalOf(perf: PerfLine): number {
  return perf.marks.total ?? Math.max(0, ...waterfall(perf.marks).map((stage) => stage.end));
}

/** The shared scale over a tail's perf lines. A scale with nothing to draw against is 0, and a bar
 *  on a 0 scale draws empty rather than full. */
export function scaleOf(lines: readonly PerfLine[]): PerfScale {
  let ms = 0;
  let inTokens = 0;
  let outTokens = 0;
  for (const perf of lines) {
    ms = Math.max(ms, totalOf(perf));
    inTokens = Math.max(inTokens, perf.inTokens ?? 0);
    outTokens = Math.max(outTokens, perf.outTokens ?? 0);
  }
  return { ms, inTokens, outTokens };
}

/** The share of the prompt the cache served: `in_tokens` holds the cached part (PerfKeys), so the
 *  ratio is cached over in. Null without both, and without any input: 0 would be a claim. */
export function cacheHitOf(perf: PerfLine): number | null {
  if (perf.inTokens === null || perf.cachedTokens === null || perf.inTokens <= 0) return null;
  return perf.cachedTokens / perf.inTokens;
}

const share = (value: number, whole: number): number => (whole <= 0 ? 0 : value / whole);
const tokens = (value: number | null): string => (value === null ? ABSENT : fmtTokens(value));

/** A bar with its figure and the figure's unit after it. The bar's name carries the figure, so a
 *  screen reader hears the number and not only the bar's share of the tail. */
function Figure({ value, label, figure, unit, cell }: { value: number; label: string; figure: string; unit: string; cell: string }) {
  return (
    <span className={cell}>
      <Meter
        tone="neutral"
        value={value}
        label={`${label} ${figure}`}
        figure={<>{figure} <span className="myx-lt-unit">{unit}</span></>}
      />
    </span>
  );
}

/** The drawn cells of one perf line, and the raw line under them while it is open. */
export function PerfCells({ perf, scale, open, onToggle, raw }: {
  perf: PerfLine;
  scale: PerfScale;
  open: boolean;
  onToggle?: (() => void) | undefined;
  raw: ReactNode;
}) {
  const stages: WaterfallStage[] = waterfall(perf.marks).map((stage) => ({
    key: stage.key,
    label: stage.label,
    start: stage.start,
    end: stage.end,
    mark: MARK_OF[stage.group],
  }));
  const total = totalOf(perf);
  const hit = cacheHitOf(perf);
  return (
    <span className={cx('myx-lt-text', 'myx-lt-perf', open && 'myx-lt-open')}>
      <span className="myx-lt-perf-row">
        <button
          type="button"
          className="myx-lt-toggle"
          aria-label={S.rawLine}
          aria-expanded={open}
          onClick={onToggle}
        >
          <CaretRightIcon aria-hidden="true" />
        </button>
        <span className="myx-lt-model">
          {perf.outcome === 'ok' ? null : <Badge tone="danger" quiet>{perf.outcome}</Badge>}
          {perf.compact ? <Badge tone="neutral" quiet>{S.compact}</Badge> : null}
          <span className="myx-lt-model-name">{perf.model ?? ABSENT}</span>
        </span>
        <span className="myx-lt-wf"><Waterfall stages={stages} scale={scale.ms} label={`${S.timing} ${fmtMs(total)}`} /></span>
        <span className="myx-lt-figure">{fmtMs(total)}</span>
        <Figure value={hit ?? 0} label={S.cacheHit} figure={hit === null ? ABSENT : fmtShare(hit)} unit={U.cached} cell="myx-lt-hit" />
        <Figure value={share(perf.inTokens ?? 0, scale.inTokens)} label={S.tokensIn} figure={tokens(perf.inTokens)} unit={U.in} cell="myx-lt-in" />
        <Figure value={share(perf.outTokens ?? 0, scale.outTokens)} label={S.tokensOut} figure={tokens(perf.outTokens)} unit={U.out} cell="myx-lt-out" />
      </span>
      {open ? <span className="myx-lt-raw">{raw}</span> : null}
    </span>
  );
}
