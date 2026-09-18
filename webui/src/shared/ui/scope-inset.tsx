// A dark chart frame. The inset is a recessed scope: its own ground, its own
// grid, and the basis printed in the frame corner so a chart can never be read
// as measured when it was estimated or is stale. children is the chart itself
// (svg or canvas drawn from data at runtime) and is never drawn by this file.
import type { ReactNode } from 'react';
import type { Basis } from './types';

export function ScopeInset({ title, basis, children }: {
  title: string;
  basis: Basis;
  children: ReactNode;
}) {
  return (
    <figure className="myx-scope">
      <figcaption className="myx-scope-head">
        <span className="myx-scope-title">{title}</span>
        <span className="myx-scope-basis">{basis}</span>
      </figcaption>
      <div className="myx-scope-body">{children}</div>
    </figure>
  );
}
