// The console's building blocks: a page head, a section, a data table, a status badge, a stat, a
// meter, a detail panel and a key/value list (docs/design/DESIGN.md section 9). Flat grounds and one
// hairline; the page carries its hierarchy in type size and weight, not in boxes. Every label prop
// takes a word from the caller's strings.ts and prints it as written, in sentence case. There is no
// description slot: an explanation is an `info` tip beside the title, shown on hover and focus.
import type { CSSProperties, ReactNode } from 'react';
import { cx } from '../lib';
import { InfoTip } from './charts';
import './kit.css';

/** The help a title carries on hover and focus: one plain sentence, and the name of its mark. */
export interface Info {
  text: string;
  label: string;
}

/** A status colour. It never travels alone: a badge always prints its word. */
export type Tone = 'ok' | 'warn' | 'danger' | 'neutral' | 'accent';

/** The head of a page: its title with its info tip, the actions on the right, and the view tabs
 *  (or any other control row) under it. */
export function PageHeader({ title, info, actions, children }: {
  title: string;
  info?: Info | undefined;
  actions?: ReactNode;
  children?: ReactNode;
}) {
  return (
    <header className="myx-ph">
      <div className="myx-ph-top">
        <div className="myx-ph-titles">
          <h1 className="myx-ph-title">{title}</h1>
          {info === undefined ? null : <InfoTip text={info.text} label={info.label} side="bottom" />}
        </div>
        {actions === undefined ? null : <div className="myx-ph-actions">{actions}</div>}
      </div>
      {children === undefined ? null : <div className="myx-ph-bar">{children}</div>}
    </header>
  );
}

/** A titled group of content. No box: a title row, then whatever it holds. `meta` is a quiet word
 *  beside the title (a group's kind, `head` or `project`); `count` prints after it; `info` is its
 *  tip. */
export function Section({ title, meta, count, info, actions, children, className }: {
  title: ReactNode;
  meta?: string;
  count?: number;
  info?: Info | undefined;
  actions?: ReactNode;
  children?: ReactNode;
  className?: string;
}) {
  return (
    <section className={cx('myx-sec', className)}>
      <div className="myx-sec-head">
        <div className="myx-sec-titles">
          <h2 className="myx-sec-title">
            {meta === undefined ? null : <span className="myx-sec-meta">{meta}</span>}
            <span className="myx-sec-name">{title}</span>
            {count === undefined ? null : <span className="myx-sec-count">{count}</span>}
          </h2>
          {info === undefined ? null : <InfoTip text={info.text} label={info.label} />}
        </div>
        {actions === undefined ? null : <div className="myx-sec-actions">{actions}</div>}
      </div>
      {children}
    </section>
  );
}

/** One column of a DataTable. `label` is the lowercase word from strings.ts. */
export interface Column<T> {
  key: string;
  label: string;
  cell: (row: T) => ReactNode;
  /** A CSS width for the column (`30%`, `calc(8 * var(--u))`); columns without one share what is left. */
  width?: string;
  align?: 'start' | 'end';
  /** Figures and identifiers: tabular numerals in the mono face. */
  mono?: boolean;
  /** A value that must be read whole, such as a path an operator opens: it breaks onto more lines
   *  rather than clipping to an ellipsis. */
  wrap?: boolean;
  /** The row's name cell: stronger ink, and when the table opens rows it carries the open control. */
  primary?: boolean;
}

/** The shown columns, each given its weight's share of 100%: a table whose columns depend on the
 *  view (a head column only when grouped by role) still fills its width exactly, and never mixes a
 *  rem width with a percent one, which overflowed a narrowed board and crushed its name column. */
export function weightedColumns<T>(columns: readonly Column<T>[], weights: Readonly<Record<string, number>>): Column<T>[] {
  const sum = columns.reduce((held, column) => held + (weights[column.key] ?? 0), 0);
  return columns.map((column) => ({ ...column, width: `${(((weights[column.key] ?? 0) / sum) * 100).toFixed(2)}%` }));
}

/** A titled run of rows in a DataTable: a board grouped by head, by project, or by hour. */
export interface RowGroup<T> {
  key: string;
  title: ReactNode;
  /** The class that binds the run's `--hue`, when the run is one head's: its title row takes the
   *  head's colour. */
  hue?: string;
  count?: number;
  /** A line under the title that says something about the whole group. */
  note?: ReactNode;
  actions?: ReactNode;
  rows: readonly T[];
}

/**
 * A table: one header row of column names, then one row per item, or one titled run of rows per
 * group. When `onOpen` is given, the primary cell holds a button that opens the row and stretches
 * over the whole row, so a pointer can press anywhere on it and a keyboard reaches it with Tab, and
 * the table keeps its table semantics (a row is never a button).
 */
export function DataTable<T>({ columns, rows, groups, rowKey, label, onOpen, openLabel, selectedKey = null, rowTone, rowHue, className }: {
  columns: readonly Column<T>[];
  /** The rows, when the table is one run. */
  rows?: readonly T[];
  /** The rows in titled runs, when it is grouped; `rows` is then ignored. */
  groups?: readonly RowGroup<T>[];
  rowKey: (row: T) => string;
  /** The table's accessible name. */
  label: string;
  onOpen?: (row: T) => void;
  /** The open button's accessible name for a row. */
  openLabel?: (row: T) => string;
  selectedKey?: string | null;
  /** A row that needs attention takes a tint; its status cell still says why. */
  rowTone?: (row: T) => Tone | null;
  /** The class that binds a row's `--hue` (a head's colour, from the caller's entity); the row then
   *  carries that colour as a bar on its leading edge. */
  rowHue?: (row: T) => string | null;
  className?: string;
}) {
  const hasWidths = columns.some((column) => column.width !== undefined);
  const runs: readonly RowGroup<T>[] = groups ?? [{ key: '', title: null, rows: rows ?? [] }];
  const grouped = groups !== undefined;

  const row = (item: T) => {
    const key = rowKey(item);
    const selected = key === selectedKey;
    const tone = rowTone?.(item) ?? null;
    const hue = rowHue?.(item) ?? null;
    return (
      <tr key={key} className={cx(selected && 'myx-dt-selected', tone !== null && `myx-dt-tone-${tone}`, hue !== null && `myx-dt-hued ${hue}`)}>
        {columns.map((column) => {
          const content = column.cell(item);
          const cellClass = cx(
            column.align === 'end' && 'myx-dt-end',
            column.mono === true && 'myx-dt-mono',
            column.wrap === true && 'myx-dt-break',
            column.primary === true && 'myx-dt-primary',
          );
          if (column.primary === true && onOpen !== undefined) {
            return (
              <td key={column.key} className={cellClass}>
                <button
                  type="button"
                  className="myx-dt-opener"
                  aria-expanded={selected}
                  {...(openLabel === undefined ? {} : { 'aria-label': openLabel(item) })}
                  onClick={() => onOpen(item)}
                >
                  {content}
                </button>
              </td>
            );
          }
          return <td key={column.key} className={cellClass}>{content}</td>;
        })}
      </tr>
    );
  };

  return (
    <div className={cx('myx-dt-wrap', className)}>
      <table className={cx('myx-dt', onOpen !== undefined && 'myx-dt-open', hasWidths && 'myx-dt-fixed')} aria-label={label}>
        <colgroup>
          {columns.map((column) => (
            <col key={column.key} {...(column.width === undefined ? {} : { style: { width: column.width } as CSSProperties })} />
          ))}
        </colgroup>
        <thead>
          <tr>
            {columns.map((column) => (
              <th key={column.key} scope="col" className={cx(column.align === 'end' && 'myx-dt-end')}>
                {column.label}
              </th>
            ))}
          </tr>
        </thead>
        {runs.map((run) => (
          <tbody key={run.key} className={cx(grouped && 'myx-dt-run', run.hue !== undefined && `myx-dt-hued ${run.hue}`)}>
            {grouped ? (
              <tr className="myx-dt-group">
                <th scope="rowgroup" colSpan={columns.length}>
                  <span className="myx-dt-group-head">
                    <span className="myx-dt-group-title">{run.title}</span>
                    {run.count === undefined ? null : <span className="myx-dt-group-count">{run.count}</span>}
                    {run.actions === undefined ? null : <span className="myx-dt-group-actions">{run.actions}</span>}
                  </span>
                  {run.note === undefined ? null : <span className="myx-dt-group-note">{run.note}</span>}
                </th>
              </tr>
            ) : null}
            {run.rows.map(row)}
          </tbody>
        ))}
      </table>
    </div>
  );
}

/** A line of large figures, each over its word: the counts a page is about (live, stale, gone). */
export function Tally({ items }: { items: readonly { label: string; value: ReactNode; tone?: Tone }[] }) {
  return (
    <dl className="myx-tally">
      {items.map((item) => (
        <div key={item.label} className={cx('myx-tally-item', item.tone !== undefined && `myx-tally-${item.tone}`)}>
          <dd className="myx-tally-value">{item.value}</dd>
          <dt className="myx-tally-label">{item.label}</dt>
        </div>
      ))}
    </dl>
  );
}

/** A few mutually exclusive choices in one row (a time window, a tail length): the chosen one takes
 *  the active ground, and each is a toggle button a reader hears as pressed or not. */
export function Segmented<V extends string>({ label, options, value, onChange }: {
  /** The group's accessible name. */
  label: string;
  options: readonly { value: V; label: string }[];
  value: V;
  onChange: (value: V) => void;
}) {
  return (
    <div className="myx-seg" role="group" aria-label={label}>
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          className={cx('myx-seg-item', option.value === value && 'myx-seg-item-on')}
          aria-pressed={option.value === value}
          onClick={() => onChange(option.value)}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}

/** A status word with its colour dot. The word is the signal; the colour repeats it. */
export function Badge({ tone, children, quiet = false }: { tone: Tone; children: ReactNode; quiet?: boolean }) {
  return (
    <span className={cx('myx-badge', `myx-badge-${tone}`, quiet && 'myx-badge-quiet')}>
      <span className="myx-badge-dot" aria-hidden="true" />
      <span className="myx-badge-word">{children}</span>
    </span>
  );
}

/** A number that is the point of its tile: a small label over a large figure, and one line under. */
export function Stat({ label, value, unit, sub, tone, chart, figure, trend }: {
  label: string;
  value: ReactNode;
  unit?: string;
  sub?: ReactNode;
  tone?: Tone;
  /** The figure's shape under it, full width: a split bar, a sparkline. */
  chart?: ReactNode;
  /** A shape beside the figure, such as a ring. */
  figure?: ReactNode;
  /** A small shape at the end of the label's line, such as the last day's trend: it takes no
   *  width from the figure, which a sparkline beside it cut to `175....` in a narrow tile. */
  trend?: ReactNode;
}) {
  const name = <p className="myx-stat-label">{label}</p>;
  return (
    <div className={cx('myx-stat', tone !== undefined && `myx-stat-${tone}`, figure !== undefined && 'myx-stat-figured')}>
      {trend === undefined ? name : <div className="myx-stat-head">{name}<div className="myx-stat-trend">{trend}</div></div>}
      <p className="myx-stat-value">
        {value}
        {unit === undefined ? null : <span className="myx-stat-unit">{unit}</span>}
      </p>
      {figure === undefined ? null : <div className="myx-stat-figure">{figure}</div>}
      {chart === undefined ? null : <div className="myx-stat-chart">{chart}</div>}
      {sub === undefined ? null : <p className="myx-stat-sub">{sub}</p>}
    </div>
  );
}

/** A row of stats that share the width. */
export function StatRow({ children }: { children: ReactNode }) {
  return <div className="myx-stats">{children}</div>;
}

/** How full something is, 0 to 1. The figure is printed beside it by the caller. Null is a value
 *  nobody measured: the empty track draws and announces nothing, since a meter at 0 claims a zero. */
export function Meter({ value, tone = 'accent', label, figure }: {
  value: number | null;
  tone?: Tone;
  label: string;
  /** The figure the bar stands for, printed after it in tabular numerals, end-aligned so a column
   *  of meters lines its figures up. */
  figure?: ReactNode;
}) {
  const share = value === null ? 0 : Math.max(0, Math.min(1, value));
  const reading = value === null
    ? { 'aria-hidden': true }
    : { role: 'meter', 'aria-label': label, 'aria-valuemin': 0, 'aria-valuemax': 100, 'aria-valuenow': Math.round(share * 100) };
  const bar = (
    <span className={cx('myx-meter', `myx-meter-${tone}`)} {...reading}>
      <span className="myx-meter-fill" style={{ width: `${share * 100}%` }} />
    </span>
  );
  if (figure === undefined) return bar;
  return (
    <span className="myx-meter-row">
      {bar}
      <span className="myx-meter-figure">{figure}</span>
    </span>
  );
}

/** The detail of an opened row, beside the list it came from. */
export function DetailPanel({ title, label, status, onClose, closeLabel, children }: {
  title: ReactNode;
  /** The panel's accessible name (a lowercase word, `session detail`). */
  label: string;
  status?: ReactNode;
  onClose: () => void;
  closeLabel: string;
  children: ReactNode;
}) {
  return (
    <aside className="myx-panel" aria-label={label}>
      <div className="myx-panel-head">
        <div className="myx-panel-titles">
          <h2 className="myx-panel-title">{title}</h2>
          {status === undefined ? null : <div className="myx-panel-status">{status}</div>}
        </div>
        <button type="button" className="myx-panel-close" onClick={onClose}>{closeLabel}</button>
      </div>
      <div className="myx-panel-body">{children}</div>
    </aside>
  );
}

/** Label and value pairs, one per line. */
export function KeyValue({ rows }: { rows: readonly (readonly [string, ReactNode])[] }) {
  return (
    <dl className="myx-kv">
      {rows.map(([key, value]) => (
        <div className="myx-kv-row" key={key}>
          <dt>{key}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </dl>
  );
}
