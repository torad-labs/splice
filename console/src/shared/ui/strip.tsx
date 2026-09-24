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
  // A strip is a button only when it has something to open. A printed readout
  // with no onOpen is a named group: no tab stop, no key handler, nothing a
  // reader would be told to press (S9: 6 of the 8 tab stops on turns did nothing).
  // A group and not a bare div because ARIA prohibits naming a generic element,
  // and ariaLabel is the strip's name either way.
  const pressable = onOpen !== undefined;
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
      tabIndex={pressable ? 0 : undefined}
      role={pressable ? 'button' : 'group'}
      aria-label={ariaLabel}
      aria-disabled={struck}
      onClick={open}
      onKeyDown={pressable ? onKeyDown : undefined}
    >
      <HolderEdge state={state} label={edgeLabel} />
      <div className="myx-strip-fields">{children}</div>
      {struck ? <span className="myx-strip-strike" aria-hidden="true" /> : null}
    </div>
  );
}
