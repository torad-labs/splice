// The console's building blocks: a page head, a section, a data table, a status badge, a stat, a
// meter, a detail panel and a key/value list. Flat grounds and one hairline; the page carries its
// hierarchy in type size and weight, not in boxes. Every label prop takes a word from the caller's
// strings.ts (lowercase) and prints it through `sentence`, so the chrome reads in sentence case.
import type { CSSProperties, ReactNode } from 'react';
import { cx, sentence } from '../lib';
import './kit.css';

/** A status colour. It never travels alone: a badge always prints its word. */
export type Tone = 'ok' | 'warn' | 'danger' | 'neutral' | 'accent';

/** The head of a page: its title, one line about it, the actions on the right, and the view tabs
 *  (or any other control row) under it. */
export function PageHeader({ title, description, actions, children }: {
  title: string;
  description?: ReactNode;
  actions?: ReactNode;
  children?: ReactNode;
}) {
  return (
    <header className="myx-ph">
      <div className="myx-ph-top">
        <div className="myx-ph-titles">
          <h1 className="myx-ph-title">{sentence(title)}</h1>
          {description === undefined ? null : <p className="myx-ph-desc">{description}</p>}
        </div>
        {actions === undefined ? null : <div className="myx-ph-actions">{actions}</div>}
      </div>
      {children === undefined ? null : <div className="myx-ph-bar">{children}</div>}
    </header>
  );
}

/** A titled group of content. No box: a title row, then whatever it holds. `meta` is a quiet word
 *  beside the title (a group's kind, `head` or `project`); `count` prints after it. */
export function Section({ title, meta, count, description, actions, children, className }: {
  title: ReactNode;
  meta?: string;
  count?: number;
  description?: ReactNode;
  actions?: ReactNode;
  children?: ReactNode;
  className?: string;
}) {
  return (
    <section className={cx('myx-sec', className)}>
      <div className="myx-sec-head">
        <h2 className="myx-sec-title">
          {meta === undefined ? null : <span className="myx-sec-meta">{sentence(meta)}</span>}
          <span className="myx-sec-name">{title}</span>
          {count === undefined ? null : <span className="myx-sec-count">{count}</span>}
        </h2>
        {actions === undefined ? null : <div className="myx-sec-actions">{actions}</div>}
      </div>
      {description === undefined ? null : <p className="myx-sec-desc">{description}</p>}
      {children}
    </section>
  );
}

/** One column of a DataTable. `label` is the lowercase word from strings.ts. */
export interface Column<T> {
  key: string;
  label: string;
  cell: (row: T) => ReactNode;
  /** A CSS width for the column (`30%`, `8rem`); columns without one share what is left. */
  width?: string;
  align?: 'start' | 'end';
  /** Figures and identifiers: tabular numerals in the mono face. */
  mono?: boolean;
  /** The row's name cell: stronger ink, and when the table opens rows it carries the open control. */
  primary?: boolean;
}

/**
 * A table: one header row of column names, then one row per item. When `onOpen` is given, the
 * primary cell holds a button that opens the row and stretches over the whole row, so a pointer
 * can press anywhere on it and a keyboard reaches it with Tab, and the table keeps its table
 * semantics (a row is never a button).
 */
export function DataTable<T>({ columns, rows, rowKey, label, onOpen, openLabel, selectedKey = null, rowTone }: {
  columns: readonly Column<T>[];
  rows: readonly T[];
  rowKey: (row: T) => string;
  /** The table's accessible name. */
  label: string;
  onOpen?: (row: T) => void;
  /** The open button's accessible name for a row. */
  openLabel?: (row: T) => string;
  selectedKey?: string | null;
  /** A row that needs attention takes a tinted ground; its status cell still says why. */
  rowTone?: (row: T) => Tone | null;
}) {
  const hasWidths = columns.some((column) => column.width !== undefined);
  return (
    <div className="myx-dt-wrap">
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
                {sentence(column.label)}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const key = rowKey(row);
            const selected = key === selectedKey;
            const tone = rowTone?.(row) ?? null;
            return (
              <tr key={key} className={cx(selected && 'myx-dt-selected', tone !== null && `myx-dt-tone-${tone}`)}>
                {columns.map((column) => {
                  const content = column.cell(row);
                  const className = cx(
                    column.align === 'end' && 'myx-dt-end',
                    column.mono === true && 'myx-dt-mono',
                    column.primary === true && 'myx-dt-primary',
                  );
                  if (column.primary === true && onOpen !== undefined) {
                    return (
                      <td key={column.key} className={className}>
                        <button
                          type="button"
                          className="myx-dt-opener"
                          aria-expanded={selected}
                          {...(openLabel === undefined ? {} : { 'aria-label': openLabel(row) })}
                          onClick={() => onOpen(row)}
                        >
                          {content}
                        </button>
                      </td>
                    );
                  }
                  return <td key={column.key} className={className}>{content}</td>;
                })}
              </tr>
            );
          })}
        </tbody>
      </table>
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
export function Stat({ label, value, unit, sub, tone }: {
  label: string;
  value: ReactNode;
  unit?: string;
  sub?: ReactNode;
  tone?: Tone;
}) {
  return (
    <div className={cx('myx-stat', tone !== undefined && `myx-stat-${tone}`)}>
      <p className="myx-stat-label">{sentence(label)}</p>
      <p className="myx-stat-value">
        {value}
        {unit === undefined ? null : <span className="myx-stat-unit">{unit}</span>}
      </p>
      {sub === undefined ? null : <p className="myx-stat-sub">{sub}</p>}
    </div>
  );
}

/** A row of stats that share the width. */
export function StatRow({ children }: { children: ReactNode }) {
  return <div className="myx-stats">{children}</div>;
}

/** How full something is, 0 to 1. The figure is printed beside it by the caller. */
export function Meter({ value, tone = 'accent', label }: { value: number; tone?: Tone; label: string }) {
  const share = Math.max(0, Math.min(1, value));
  return (
    <span
      className={cx('myx-meter', `myx-meter-${tone}`)}
      role="meter"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={Math.round(share * 100)}
    >
      <span className="myx-meter-fill" style={{ width: `${share * 100}%` }} />
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
        <button type="button" className="myx-panel-close" onClick={onClose}>{sentence(closeLabel)}</button>
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
          <dt>{sentence(key)}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </dl>
  );
}
