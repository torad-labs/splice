// The bay side of the gestures: one hook that turns a change in a bay's key list into a print, a
// strike or a hand-off.
//
// WHY THIS DRIVES THE DOM ITSELF. Every bay's rows are page components — SessionStrip, HeadStrip,
// AccountStrip, the settings field boxes — not bare `Strip`s. A gesture injected as a prop would
// have to be forwarded by each of them, and a gesture that only works where a page remembered to
// forward it is a gesture that silently does nothing on most pages:
//
//   measured the first way, `data-motion-id` landed on ZERO of the five strips on the sessions page
//
// So the bay applies the gesture to the row ELEMENTS it can see: it holds the rows element, reads
// its children in order, and pairs them with the keys it was handed. React owns the tree; this
// layer owns three transient things React never sets — a class, a transform, and one appended
// strike line — and takes each of them back on a timer. That also means a row can be anything:
// the gestures work on a wrapper, a form row, or a strip, without a single page changing.
//
// WHY useLayoutEffect. A print applied after paint shows the strip at its final position first and
// then jumps it up to animate down. Layout effects run before the browser paints, so the strip's
// first painted frame is the top of its travel. The same timing is what lets two bays pair a
// hand-off: both commit together, so both layout effects run in the same tick.
import { useLayoutEffect, useRef, useState } from 'react';
import type { ReactNode, RefObject } from 'react';
import { diffKeys } from './diff';
import { handoffs, prefersReducedMotion } from './registry';
import type { Rect } from './registry';
import './motion.css';

/**
 * The gesture durations, mirroring the `--dur-N` tokens (CONTRACTS.md section 1: 120, 240, 400 ms).
 *
 * Mirrored rather than read, because the timers have to end when the animation ends and a CSS
 * variable cannot be read synchronously at the moment it is needed. Under `prefers-reduced-motion`
 * the tokens are `0ms`; this hook returns early there, so no class is applied and no timer is
 * scheduled, and the two halves can never disagree about whether a gesture happened.
 */
export const MOTION_MS = { print: 240, strike: 240, handoff: 400 } as const;

export interface MotionRow {
  key: string;
  node: ReactNode;
}

/** A row that has left, with the index it held, so the rack closes around it instead of the row
 *  appearing to jump to the end on its way out. */
export interface DepartingRow extends MotionRow {
  at: number;
}

export interface BayGestures {
  /** Attach to the element holding the rows, so they can be measured. */
  rowsRef: RefObject<HTMLDivElement | null>;
  /** Rows that have left and are held for one strike before they go, each in its old place. */
  departing: readonly DepartingRow[];
}

/** The rows the bay is drawing, in order, skipping the ones held back for a strike. */
function rowElements(container: HTMLElement | null): HTMLElement[] {
  if (container === null) return [];
  return [...container.children].flatMap((child) => {
    if (!(child instanceof HTMLElement)) return [];
    if (child.classList.contains('myx-bay-leaving')) return [];
    return [child];
  });
}

function rectOf(element: HTMLElement | undefined): Rect | null {
  if (element === undefined) return null;
  const box = element.getBoundingClientRect();
  if (box.width === 0 && box.height === 0) return null;
  return { x: box.x, y: box.y, width: box.width, height: box.height };
}

/** The line a struck row draws across itself, appended and taken back by this layer. */
function drawStrike(element: HTMLElement): void {
  // The line is absolutely positioned, so the row has to be its containing block. A Strip already
  // is; a page's own row wrapper usually is not, and without this the line would stretch across
  // whatever ancestor happens to be positioned instead.
  if (getComputedStyle(element).position === 'static') element.style.position = 'relative';
  const line = document.createElement('span');
  line.className = 'myx-strip-strike myx-strike-drawn myx-strip-struck';
  line.setAttribute('aria-hidden', 'true');
  element.append(line);
}

export function useBayGestures(rows: readonly MotionRow[]): BayGestures {
  const rowsRef = useRef<HTMLDivElement>(null);
  const latest = useRef(rows);
  latest.current = rows;
  const previous = useRef<{ keys: string[]; rows: readonly MotionRow[]; rects: Map<string, Rect> } | null>(null);
  const [departing, setDeparting] = useState<readonly DepartingRow[]>([]);
  const signature = rows.map((row) => row.key).join(' ');

  useLayoutEffect(() => {
    const container = rowsRef.current;
    const current = latest.current;
    const keys = current.map((row) => row.key);
    const elements = rowElements(container);
    const rects = new Map<string, Rect>();
    elements.forEach((element, index) => {
      const key = keys[index];
      const rect = rectOf(element);
      if (key !== undefined && rect !== null) rects.set(key, rect);
    });

    const before = previous.current;
    previous.current = { keys, rows: current, rects };

    // NO GESTURE ON FIRST PAINT. On the first commit every row is new, so a diff would print a
    // whole rack in; and a console that animates its own arrival reads as unstable rather than as
    // alive. The first commit is the page, the ones after it are the machine.
    if (before === null) return;
    if (prefersReducedMotion()) return;

    const { entered, left } = diffKeys(before.keys, keys);
    const timers: number[] = [];
    const frames: number[] = [];

    // STRIKE, THEN LEAVE. The row stays mounted (the bay holds it) so the line has somewhere to
    // draw; the bay drops it when this timer ends.
    const leaving = left.flatMap((key) => {
      const at = before.keys.indexOf(key);
      const row = before.rows[at];
      return row === undefined ? [] : [{ key, node: row.node, at }];
    });
    if (leaving.length > 0) {
      setDeparting((held) => [...held, ...leaving]);
      // The strike is drawn on the NEXT layout pass, when the held rows are in the DOM.
      frames.push(requestAnimationFrame(() => {
        const held = rowElements(container);
        const offset = elements.length;
        held.slice(offset).forEach((element) => {
          element.classList.add('myx-strip-struck');
          drawStrike(element);
        });
      }));
      timers.push(window.setTimeout(
        () => setDeparting((held) => held.filter((row) => !left.includes(row.key))),
        MOTION_MS.strike,
      ));
    }

    // PRINT OR HAND OFF. A hand-off is decided by the registry, which pairs this arrival with a
    // departure from another bay; when it pairs, the print is never applied — the strip did not
    // arrive, it moved.
    for (const key of entered) {
      const element = elements[keys.indexOf(key)];
      const rect = rects.get(key);
      if (element === undefined || rect === undefined) continue;
      let paired = false;
      handoffs.enter(key, rect, (handoff) => {
        paired = true;
        // FLIP: move it back to where it came from with no transition, then release it on the next
        // frame so the CSS transition carries it the rest of the way.
        // NOT AN ABSENCE PHRASE (M1-69): this is the CSS keyword `none`, on a style assignment.
        // The absence census counts it because its regex cannot tell a CSS value from a display
        // word, which is one of the ways its denominator over-counts.
        element.style.transition = 'none';
        element.style.transform =
          `translate(${handoff.dx}px, ${handoff.dy}px) scale(${handoff.sx}, ${handoff.sy})`;
        frames.push(requestAnimationFrame(() => {
          element.style.transition = '';
          element.style.transform = '';
        }));
        timers.push(window.setTimeout(() => {
          element.style.transition = '';
          element.style.transform = '';
        }, MOTION_MS.handoff));
      });
      if (paired) continue;

      element.classList.add('myx-strip-print');
      timers.push(window.setTimeout(() => element.classList.remove('myx-strip-print'), MOTION_MS.print));
    }

    return () => {
      for (const id of timers) clearTimeout(id);
      for (const id of frames) cancelAnimationFrame(id);
    };
  }, [signature]);

  return { rowsRef, departing };
}
