// The sidebar (docs/design/DESIGN.md section 6): the wordmark, a button that opens the palette, the
// pages in four groups named for splice's own objects, and the theme switch at the foot. The
// current page takes the active ground and full ink, and says so to a reader with
// aria-current="page", so "where am I" survives a greyscale screenshot. Arrow keys walk the pages
// across the groups; Enter opens, because every item is a real link.
//
// The groups, their icons and the theme arrive as props: the address table lives in the app layer,
// and a widget may not reach upward for it.
import { useEffect, useRef } from 'react';
import type { KeyboardEvent, ReactNode } from 'react';
import { MagnifyingGlassIcon } from '@phosphor-icons/react/dist/csr/MagnifyingGlass';
import { MoonIcon } from '@phosphor-icons/react/dist/csr/Moon';
import { SunIcon } from '@phosphor-icons/react/dist/csr/Sun';
import { cx } from '@shared/lib';
import { S } from './strings';

/** The product's name as its mark: a brand, set lowercase on purpose, so it is not copy. */
const WORDMARK = 'splice';
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

export interface RailGroup {
  label: string;
  items: ReadonlyArray<{ address: string; label: string; icon: ReactNode }>;
}

export function Rail({ active, groups, theme, onTheme, onJump }: {
  active: string;
  groups: readonly RailGroup[];
  theme: 'dark' | 'light';
  onTheme: (theme: 'dark' | 'light') => void;
  onJump: () => void;
}) {
  const addresses = groups.flatMap((group) => group.items.map((item) => item.address));
  const links = useRef<Array<HTMLAnchorElement | null>>([]);
  const nav = useRef<HTMLElement>(null);

  // On a phone the pages are one sideways row, and the current one can open past the right edge:
  // only the row's own scroll moves, never the page, and an item already in view is left alone.
  useEffect(() => {
    const box = nav.current;
    const link = links.current[addresses.indexOf(active)];
    if (box === null || link === undefined || link === null) return;
    const b = box.getBoundingClientRect();
    const t = link.getBoundingClientRect();
    box.scrollLeft += revealBy({ start: b.left + box.clientLeft, size: box.clientWidth }, { start: t.left, size: t.width });
  }, [active, addresses]);

  const step = (event: KeyboardEvent<HTMLAnchorElement>, index: number) => {
    const delta = STEP[event.key];
    if (delta === undefined) return;
    event.preventDefault();
    links.current[(index + delta + addresses.length) % addresses.length]?.focus();
  };

  const next = theme === 'dark' ? 'light' : 'dark';
  let index = -1;

  return (
    <aside className="myx-side">
      <div className="myx-side-top">
        <p className="myx-side-wordmark">{WORDMARK}</p>
        <button type="button" className="myx-side-jump" onClick={onJump}>
          <MagnifyingGlassIcon className="myx-side-icon" aria-hidden="true" />
          <span className="myx-side-jump-word">{S.jump}</span>
          <kbd className="myx-side-kbd">⌘K</kbd>
        </button>
      </div>

      <nav className="myx-side-nav" aria-label={S.nav} ref={nav}>
        {groups.map((group) => (
          <div className="myx-side-group" key={group.label}>
            <p className="myx-side-group-label">{group.label}</p>
            <ul className="myx-side-list">
              {group.items.map((item) => {
                index += 1;
                const at = index;
                const current = item.address === active;
                return (
                  <li key={item.address}>
                    <a
                      ref={(el) => {
                        links.current[at] = el;
                      }}
                      className={cx('myx-side-link', current && 'myx-side-link-current')}
                      href={`#/${item.address}`}
                      aria-current={current ? 'page' : undefined}
                      onKeyDown={(event) => step(event, at)}
                    >
                      <span className="myx-side-icon" aria-hidden="true">{item.icon}</span>
                      <span className="myx-side-word">{item.label}</span>
                    </a>
                  </li>
                );
              })}
            </ul>
          </div>
        ))}
      </nav>

      <div className="myx-side-foot">
        <button type="button" className="myx-side-theme" onClick={() => onTheme(next)}>
          {theme === 'dark' ? <SunIcon className="myx-side-icon" aria-hidden="true" /> : <MoonIcon className="myx-side-icon" aria-hidden="true" />}
          <span>{next === 'dark' ? S.toDark : S.toLight}</span>
        </button>
      </div>
    </aside>
  );
}
