// A labeled rack of strips. The bay owns the rails, the label and the count;
// it never owns the page's data, so an empty bay is a real state it renders
// rather than a condition its caller has to remember.
import { Children } from 'react';
import type { CSSProperties, ReactNode } from 'react';
import { cx } from '../lib';
import { Empty } from './empty';

export function Bay({ label, count, empty, actions, className, style, children }: {
  label: string;
  count?: number;
  empty?: { text: string; source: string };
  actions?: ReactNode;
  className?: string;
  style?: CSSProperties;
  children?: ReactNode;
}) {
  const hasRows = Children.count(children) > 0;
  return (
    <section className={cx('myx-bay', className)} style={style}>
      <header className="myx-bay-head">
        <span className="myx-bay-label">{label}</span>
        {typeof count === 'number' ? <span className="myx-bay-count">{count}</span> : null}
        {actions ? <span className="myx-bay-actions">{actions}</span> : null}
      </header>
      <div className="myx-bay-rows">
        {hasRows ? children : empty ? <Empty text={empty.text} source={empty.source} /> : null}
      </div>
    </section>
  );
}
