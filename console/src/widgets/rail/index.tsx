// The rail: every address as a bay, in the order the console reads them. Each
// tab is a holder edge with the address word printed on it, green on the bay
// you are standing in and grey on the rest, so "where am I" survives a
// grayscale screenshot. Arrow keys walk the rail; Enter opens, because every
// tab is a real link.
//
// The addresses arrive as a prop rather than an import: the table lives in the
// app layer, and a widget may not reach upward for it.
import { useRef } from 'react';
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

export function Rail({ active, addresses }: { active: string; addresses: readonly string[] }) {
  const tabs = useRef<Array<HTMLAnchorElement | null>>([]);

  const step = (event: KeyboardEvent<HTMLAnchorElement>, index: number) => {
    const delta = STEP[event.key];
    if (delta === undefined) return;
    event.preventDefault();
    const next = (index + delta + addresses.length) % addresses.length;
    tabs.current[next]?.focus();
  };

  return (
    <nav className="myx-rail" aria-label={S.nav}>
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
