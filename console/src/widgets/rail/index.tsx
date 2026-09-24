// The rail: every address as a bay, in the order the console reads them. Each
// tab is a holder edge with the address word printed on it, green on the bay
// you are standing in and grey on the rest, so "where am I" survives a
// grayscale screenshot. Arrow keys walk the rail; Enter opens, because every
// tab is a real link.
//
// The addresses arrive as a prop rather than an import: the table lives in the
// app layer, and a widget may not reach upward for it.
import { useEffect, useRef } from 'react';
import type { KeyboardEvent } from 'react';
import { cx } from '@shared/lib';
import { HolderEdge } from '@shared/ui';
import { S } from './strings';
import './rail.css';

const STEP: Record<string, number> = {
  ArrowDown: 1,
  ArrowRight: 1,
  ArrowUp: -1,
  ArrowLeft: -1,
};

/** How far a scroll box has to move along one axis to show an item it holds: nothing when the item
 *  is already whole inside the box, else the distance that centres it. Both spans are screen
 *  coordinates, so the box's own positioning does not enter into it. */
export function revealBy(box: { start: number; size: number }, item: { start: number; size: number }): number {
  if (item.start >= box.start && item.start + item.size <= box.start + box.size) return 0;
  return item.start + item.size / 2 - (box.start + box.size / 2);
}

export function Rail({ active, addresses }: { active: string; addresses: readonly string[] }) {
  const tabs = useRef<Array<HTMLAnchorElement | null>>([]);
  const nav = useRef<HTMLElement>(null);

  // The bay you are standing in is on the rail's screen (S12). On a phone the rail is one sideways
  // line of thirteen plates, and from accounts on the current one opened past the right edge: 0% of
  // it visible on 8 of 13 addresses at 390. Only the rail's own scroll moves, never the page, and a
  // tab already in view is left where it is, so a tap does not jump the strip under the thumb.
  useEffect(() => {
    const box = nav.current;
    const tab = tabs.current[addresses.indexOf(active)];
    if (box === null || tab === undefined || tab === null) return;
    const b = box.getBoundingClientRect();
    const t = tab.getBoundingClientRect();
    box.scrollLeft += revealBy({ start: b.left + box.clientLeft, size: box.clientWidth }, { start: t.left, size: t.width });
    box.scrollTop += revealBy({ start: b.top + box.clientTop, size: box.clientHeight }, { start: t.top, size: t.height });
  }, [active, addresses]);

  const step = (event: KeyboardEvent<HTMLAnchorElement>, index: number) => {
    const delta = STEP[event.key];
    if (delta === undefined) return;
    event.preventDefault();
    const next = (index + delta + addresses.length) % addresses.length;
    tabs.current[next]?.focus();
  };

  return (
    <nav className="myx-rail" aria-label={S.nav} ref={nav}>
      <div className="myx-rail-tabs">
        {addresses.map((address, index) => (
          <a
            key={address}
            ref={(el) => {
              tabs.current[index] = el;
            }}
            className={cx('myx-rail-tab', address === active && 'myx-rail-tab-active')}
            href={`#/${address}`}
            aria-current={address === active ? 'page' : undefined}
            onKeyDown={(event) => step(event, index)}
          >
            <HolderEdge state={address === active ? 'green' : 'grey'} label={address} />
          </a>
        ))}
      </div>
    </nav>
  );
}
