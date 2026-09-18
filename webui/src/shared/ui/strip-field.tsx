// A fixed-width boxed field inside a strip. Width is a ch count so the field
// grid stays aligned column to column at every breakpoint (the strip scrolls,
// it never reflows). The value is clipped, never wrapped: a wrapped value makes
// one strip taller than its neighbours and the rack stops reading as a rack.
import type { CSSProperties } from 'react';
import { cx } from '../lib';
import type { Basis } from './types';

export function StripField({ w, label, value, basis, mono }: {
  w: number;
  label: string;
  value: string | number;
  basis?: Basis;
  mono?: boolean;
}) {
  // The figure face is the default (the title's "--font-figure with tabular
  // figures"); mono={false} is the escape hatch for a field whose value is
  // prose rather than a figure.
  const figure = mono !== false;
  return (
    <div className="myx-sfield" style={{ width: `${w}ch` } as CSSProperties}>
      <span className="myx-sfield-label">{label}</span>
      <span className={cx('myx-sfield-value', figure && 'myx-sfield-figure')}>
        <span className="myx-sfield-text">{value}</span>
        {basis && basis !== 'measured' ? <span className="myx-sfield-basis">{basis}</span> : null}
      </span>
    </div>
  );
}
