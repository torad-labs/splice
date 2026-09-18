// A labeled rack of strips. The bay owns the rails, the label and the count;
// it never owns the page's data, so an empty bay is a real state it renders
// rather than a condition its caller has to remember.
//
// It also owns the two gestures that belong to a RACK rather than to a row: a strip that
// appears prints in, and a strip that goes is struck and then leaves. Both are fired by a diff of
// the children's keys, never by the page — a page that had to announce "this row is new" would be
// announcing it from three different places and getting it wrong in one of them.
import { Children, Fragment } from 'react';
import type { CSSProperties, ReactNode } from 'react';
import { cx } from '../lib';
import { useBayGestures } from '../motion';
import { Empty } from './empty';

/** A child's key, or its position when it carries none: an unkeyed row has no identity to hand off
 *  or to print, which is what an unkeyed list means everywhere else in React too. */
function keyedRows(children: ReactNode): { key: string; node: ReactNode }[] {
  return Children.toArray(children).map((child, index) => {
    const key = typeof child === 'object' && child !== null && 'key' in child && child.key !== null
      ? String(child.key)
      : `#${index}`;
    return { key, node: child };
  });
}

export function Bay({ label, count, fields, empty, actions, className, style, children }: {
  label: string;
  count?: number;
  /** The rack's column names, printed once for the whole bay instead of on every strip (m1 design
   *  review B9). Each child is a box as wide as the cell it names and in the same order - pass the
   *  same `w` values the rows use - and it starts where the cells start, past the edge column. The
   *  rows then pass their `StripField`s no label. Rendered only when given, so a bay that prints
   *  its labels per strip is unchanged. */
  fields?: ReactNode;
  empty?: { text: string; source: string };
  actions?: ReactNode;
  className?: string;
  style?: CSSProperties;
  children?: ReactNode;
}) {
  const items = keyedRows(children);
  const gestures = useBayGestures(items);
  const hasRows = items.length > 0;

  // A departing row is drawn one last time, in the place it held, wrapped so the bay can tell it
  // apart from the live rows it is measuring. The wrapper is `display: contents`, so it draws
  // nothing and the row inside lays out exactly where it did.
  const ordered = [
    ...items.map((row, at) => ({ key: row.key, node: row.node, at, leaving: false })),
    ...gestures.departing.map((row) => ({ key: row.key, node: row.node, at: row.at, leaving: true })),
  ].sort((left, right) => left.at - right.at);

  return (
    <section className={cx('myx-bay', className)} style={style}>
      <header className="myx-bay-head">
        <span className="myx-bay-label">{label}</span>
        {typeof count === 'number' ? <span className="myx-bay-count">{count}</span> : null}
        {actions ? <span className="myx-bay-actions">{actions}</span> : null}
      </header>
      {fields ? <div className="myx-bay-fields">{fields}</div> : null}
      <div className="myx-bay-rows" ref={gestures.rowsRef}>
        {hasRows || gestures.departing.length > 0 ? (
          ordered.map(({ key, node, leaving }) =>
            leaving
              ? <div className="myx-bay-leaving" key={key}>{node}</div>
              : <Fragment key={key}>{node}</Fragment>,
          )
        ) : empty ? (
          <Empty text={empty.text} source={empty.source} />
        ) : null}
      </div>
    </section>
  );
}
