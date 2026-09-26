// THE LANES (operator ruling 4, item 5, 2026-09-25): splice is strands joined by messages, so the
// board draws exactly that. One full-width strand per head in its hue, with the head's name on the
// lane; each session a card on its strand, oldest first; each hand-off an arc from the sender's card
// to the receiver's, in the sender's hue. When a message crosses, a dot travels its arc once: the
// one motion on the board, and it means something. A reader who asks for less motion gets none.
//
// The kit knows no heads and no sessions: a page hands it lanes (a title, a hue class, cards) and the
// messages between cards, so Sessions and Teams draw one component. The arithmetic (which arcs, which
// crossed, the curve) is lanes-geometry.ts; this file measures the cards and paints.
import { useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { CSSProperties, ReactNode } from 'react';
import { cx } from '../lib';
import { Badge } from './kit';
import type { Tone } from './kit';
import { arcPath, columnsOf, newestArcs, readOf, sideOf, trackTemplate } from './lanes-geometry';
import type { Box, LaneArc, LaneMessage } from './lanes-geometry';
import { S } from './strings';
import './lanes.css';

export type { LaneMessage } from './lanes-geometry';

/** One card on a strand: a session, or a team's seat. */
export interface LaneCard {
  key: string;
  title: string;
  /** The second line (the project, the role); null prints nothing. */
  meta: string | null;
  tone: Tone;
  /** The state word its badge prints. */
  word: string;
  /** Epoch ms that orders the card on its strand, oldest first; null stands after the dated ones. */
  start: number | null;
}

/** One head's strand. */
export interface Lane {
  key: string;
  /** The lane's name as a reader hears it. */
  name: string;
  /** What the lane prints as its name: the head's mark and name. */
  title: ReactNode;
  /** The class that binds the lane's `--hue`, which its strand, cards and outgoing arcs take. */
  hue: string;
  cards: LaneCard[];
}

/** How long a dot takes to cross its arc. */
const FLIGHT_MS = 900;

const reducedMotion = (): boolean =>
  typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true;

interface Drawn {
  arc: LaneArc;
  d: string;
  hue: string;
}

/** One message crossing: a dot along its arc, once, then gone. SMIL rather than a CSS motion path,
 *  because an SVG animation is placed in the drawing's own coordinates; begun by hand, because an
 *  animation inserted into a running document would otherwise start at the document's time zero and
 *  be over before it painted. */
function Flight({ d, hue, onDone }: { d: string; hue: string; onDone: () => void }) {
  const motion = useRef<SVGAnimateMotionElement>(null);
  // Held, not depended on: the board re-renders while a dot flies, and each render hands a new
  // callback that must not restart the flight.
  const done = useRef(onDone);
  done.current = onDone;
  useEffect(() => {
    motion.current?.beginElement();
    const landed = setTimeout(() => done.current(), FLIGHT_MS);
    return () => clearTimeout(landed);
  }, []);
  return (
    <circle className={cx('myx-lanes-dot', hue)}>
      <animateMotion ref={motion} begin="indefinite" dur={`${FLIGHT_MS}ms`} path={d} fill="remove" calcMode="spline" keyPoints="0;1" keyTimes="0;1" keySplines=".4 0 .2 1" />
    </circle>
  );
}

export function Lanes({ lanes, messages, label, open, selected = null, cardLabel }: {
  lanes: readonly Lane[];
  /** Every hand-off between cards, by card key; each direction of a pair is drawn once, newest.
   *  Null while the hand-offs have not been read: no arcs, and no read taken. */
  messages: readonly LaneMessage[] | null;
  label: string;
  /** Opens a card; without it the cards are not controls. */
  open?: (key: string) => void;
  selected?: string | null;
  /** A card's name for a screen reader, which the lane's own name precedes. */
  cardLabel: (card: LaneCard) => string;
}) {
  const board = useRef<HTMLDivElement>(null);
  const cards = useRef(new Map<string, HTMLElement>());
  const marker = useId();
  const [drawn, setDrawn] = useState<{ width: number; height: number; arcs: Drawn[] }>({ width: 0, height: 0, arcs: [] });
  const [flights, setFlights] = useState<{ id: number; key: string }[]>([]);
  const seen = useRef<ReadonlyMap<string, number> | null>(null);
  const nextFlight = useRef(0);

  const columns = useMemo(() => columnsOf(lanes.map((lane) => lane.cards)), [lanes]);
  const hueOfCard = useMemo(() => new Map(lanes.flatMap((lane) => lane.cards.map((card) => [card.key, lane.hue] as const))), [lanes]);
  const arcs = useMemo(() => newestArcs(messages ?? [], new Set(hueOfCard.keys())), [messages, hueOfCard]);
  // What moves the drawing: the arcs, and where each card sits (its lane and its column).
  const shape = `${lanes.map((lane) => `${lane.key}:${lane.cards.map((card) => `${card.key}@${columns.at.get(card.key) ?? 0}`).join(',')}`).join('|')}#${arcs.map((arc) => `${arc.key}@${arc.at}`).join('|')}`;

  useLayoutEffect(() => {
    const root = board.current;
    if (root === null) return undefined;
    const measure = (): void => {
      const origin = root.getBoundingClientRect();
      const boxOf = (element: HTMLElement): Box => {
        const rect = element.getBoundingClientRect();
        return { left: rect.left - origin.left, top: rect.top - origin.top, width: rect.width, height: rect.height };
      };
      setDrawn({
        width: origin.width,
        height: origin.height,
        arcs: arcs.flatMap((arc) => {
          const from = cards.current.get(arc.from);
          const to = cards.current.get(arc.to);
          if (from === undefined || to === undefined) return [];
          return [{ arc, d: arcPath(boxOf(from), boxOf(to), sideOf(arc, arcs)), hue: hueOfCard.get(arc.from) ?? '' }];
        }),
      });
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(root);
    return () => observer.disconnect();
    // `shape` names everything the measure reads: the arcs and where each card sits.
  }, [shape]);

  // A dot for each arc a message crossed since the last read (lanes-geometry readOf).
  useEffect(() => {
    const read = readOf(seen.current, messages, arcs);
    seen.current = read.seen;
    const { crossed } = read;
    if (crossed.length === 0 || reducedMotion()) return;
    setFlights((flying) => [...flying, ...crossed.map((key) => ({ id: (nextFlight.current += 1), key }))]);
  }, [messages, arcs]);

  return (
    <div className="myx-lanes" role="group" aria-label={label}>
      <div className="myx-lanes-board" ref={board} style={{ '--lane-template': trackTemplate(columns) } as CSSProperties}>
      {columns.undated === 0 ? null : (
        // The undated cards' place says nothing about when they started, and this caption says so.
        <div className="myx-lane myx-lanes-axis">
          <span />
          <div className="myx-lane-track">
            <span className="myx-lanes-unknown" style={{ gridColumn: `${columns.dated + (columns.dated > 0 ? 2 : 1)} / span ${columns.undated}` }}>{S.startUnknown}</span>
          </div>
        </div>
      )}
      {lanes.map((lane) => (
        <div key={lane.key} className={cx('myx-lane', lane.hue)} role="group" aria-label={lane.name}>
          <div className="myx-lane-head">
            {lane.title}
            <span className="myx-lane-count">{lane.cards.length}</span>
          </div>
          <ol className="myx-lane-track">
            {lane.cards.map((card) => {
              const register = (element: HTMLElement | null): void => {
                if (element === null) cards.current.delete(card.key);
                else cards.current.set(card.key, element);
              };
              const body = (
                <>
                  <span className="myx-lane-card-title">{card.title}</span>
                  <span className="myx-lane-card-meta">{card.meta ?? ''}</span>
                  <Badge tone={card.tone} quiet>{card.word}</Badge>
                </>
              );
              const className = cx('myx-lane-card', card.tone === 'warn' && 'myx-lane-card-warn', selected === card.key && 'myx-lane-card-open');
              return (
                <li key={card.key} className="myx-lane-slot" style={{ gridColumn: (columns.at.get(card.key) ?? 0) + 1 }}>
                  {open === undefined ? (
                    <span ref={register} className={className} aria-label={`${lane.name} ${cardLabel(card)}`}>{body}</span>
                  ) : (
                    <button
                      ref={register}
                      type="button"
                      className={className}
                      aria-label={`${lane.name} ${cardLabel(card)}`}
                      aria-pressed={selected === card.key}
                      onClick={() => open(card.key)}
                    >
                      {body}
                    </button>
                  )}
                </li>
              );
            })}
          </ol>
        </div>
      ))}
      <svg className="myx-lanes-arcs" width={drawn.width} height={drawn.height} aria-hidden="true">
        <defs>
          <marker id={marker} viewBox="0 0 10 10" refX="8" refY="5" markerWidth="4" markerHeight="4" orient="auto-start-reverse" markerUnits="strokeWidth">
            <path d="M0 0L10 5L0 10z" fill="context-stroke" />
          </marker>
        </defs>
        {drawn.arcs.map(({ arc, d, hue }) => (
          <path key={arc.key} className={cx('myx-lanes-arc', hue)} d={d} markerEnd={`url(#${marker})`} data-arc={arc.key} />
        ))}
      </svg>
      {/* The dots fly OVER the cards: under them, a dot sank into a card between its two ends and
          came out of it, reading as that card's hand-off (Hitstop, 2026-09-25). */}
      <svg className="myx-lanes-flights" width={drawn.width} height={drawn.height} aria-hidden="true">
        {flights.map((flight) => {
          const path = drawn.arcs.find((entry) => entry.arc.key === flight.key);
          if (path === undefined) return null;
          return (
            <Flight
              key={flight.id}
              d={path.d}
              hue={path.hue}
              onDone={() => setFlights((flying) => flying.filter((entry) => entry.id !== flight.id))}
            />
          );
        })}
      </svg>
      </div>
    </div>
  );
}
