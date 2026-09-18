// A printed strip: the one row module the whole console composes from. The
// holder edge carries attention, the field grid carries the data, and every
// state keeps a printed label so color is never the only signal.
import type { CSSProperties, KeyboardEvent, ReactNode } from 'react';
import { cx } from '../lib';
import { HolderEdge } from './holder-edge';
import type { Edge } from './types';

export function Strip({ edge, edgeLabel, cocked, struck, selected, onOpen, ariaLabel, className, style, children }: {
  edge: Edge;
  edgeLabel: string;
  cocked?: boolean;
  struck?: boolean;
  selected?: boolean;
  onOpen?: () => void;
  ariaLabel: string;
  className?: string;
  style?: CSSProperties;
  children: ReactNode;
}) {
  // struck wins over cocked: a disabled strip must never read as "needs me".
  // cocked only ever lifts the edge INTO attention (amber), never out of it, so
  // a red edge stays red.
  const state: Edge = struck ? 'grey' : cocked ? (edge === 'red' ? 'red' : 'amber') : edge;
  // A struck strip is disabled: it stays focusable so the label is still
  // readable, but it does not open.
  const open = struck ? undefined : onOpen;
  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (!open) return;
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      open();
    }
  };
  return (
    <div
      className={cx(
        'myx-strip',
        cocked && 'myx-strip-cocked',
        struck && 'myx-strip-struck',
        selected && 'myx-strip-selected',
        className,
      )}
      style={style}
      tabIndex={0}
      role="button"
      aria-label={ariaLabel}
      aria-disabled={struck}
      onClick={open}
      onKeyDown={onKeyDown}
    >
      <HolderEdge state={state} label={edgeLabel} />
      <div className="myx-strip-fields">{children}</div>
      {struck ? <span className="myx-strip-strike" aria-hidden="true" /> : null}
    </div>
  );
}
