// A chart frame: its own ground and grid, the title, and the basis in the corner whenever it is not
// the default, so a chart can never be read as measured when it was estimated or is stale. children
// is the chart itself (svg or canvas drawn from data at runtime) and is never drawn by this file.
import type { ReactNode } from 'react';
import { BASIS } from './strings';
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
        {basis === 'measured' ? null : <span className="myx-scope-basis">{BASIS[basis]}</span>}
      </figcaption>
      <div className="myx-scope-body">{children}</div>
    </figure>
  );
}
