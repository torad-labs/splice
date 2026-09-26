// The hand-off registry: the one place a strip leaving one bay and appearing in another becomes a
// single gesture instead of two.
//
// THE PROBLEM IT SOLVES. A strip that moves between bays is, to React, a deletion in one list and
// an insertion in another — two independent events in two components. Rendered naively it prints in
// its new bay while the old one strikes: the same strip visibly dying and being born, which is the
// opposite of a hand-off. Pairing them needs a place both bays can see, and a time window that says
// "these two events are the same event".
//
// FLIP, and why the transform is computed here rather than in CSS. First, Last, Invert, Play: the
// element is already at its LAST position when we learn where it came from, so it is moved back to
// the FIRST one and released. That needs numbers, and the numbers are the two rects. The registry
// computes the transform; the caller applies it and clears it on the next frame, and the CSS
// transition (`--dur-3`) does the rest.
//
// THE FRAME IS THE WINDOW. A leave and an enter pair only if they happen inside one frame; a leave
// that nobody claims is struck and gone, and an enter that pairs with nothing prints. The registry
// is ORDER-TOLERANT: whichever of the two arrives first waits for its partner for the rest of the
// frame, so a tree where the entering bay commits first still hands off.
//
// REDUCED MOTION IS CHECKED HERE TOO, not only in the CSS. The `--dur-N` tokens are `0ms` under
// `prefers-reduced-motion`, which makes a transition instant; it does not stop the transform from
// being applied, and an instant jump to a moved position is exactly the motion the setting is
// asking us not to make. So the registry refuses to pair at all.

/** A viewport rect, or the subset of one FLIP needs. */
export interface Rect {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** A paired hand-off: where the strip was, where it landed, and the transform that connects them. */
export interface Handoff {
  id: string;
  from: Rect;
  to: Rect;
  /** translate the element back to `from`, then let it go. */
  dx: number;
  dy: number;
  /** scale, because a strip can land in a bay of a different width. */
  sx: number;
  sy: number;
}

export interface HandoffRegistry {
  /** A strip was in this bay and is leaving it. */
  leave(id: string, rect: Rect): void;
  /**
   * A strip has appeared in this bay. `apply` is called with the hand-off when one is found — now,
   * if the leave arrived first, or later in the same frame if it has not yet.
   */
  enter(id: string, rect: Rect, apply: (handoff: Handoff) => void): void;
  /** Close the frame: anything still unpaired is not a hand-off and is dropped. */
  endFrame(): void;
  /** How many pairs have been made, for a test and for anyone debugging a gesture. */
  pairedCount(): number;
}

export function handoffTransform(from: Rect, to: Rect): Pick<Handoff, 'dx' | 'dy' | 'sx' | 'sy'> {
  // A zero-sized rect is a measurement that did not happen (a hidden or not-yet-laid-out node).
  // Scaling by it would divide by zero and translate by a nonsense amount, so it is refused.
  const sx = to.width > 0 ? from.width / to.width : 1;
  const sy = to.height > 0 ? from.height / to.height : 1;
  return { dx: from.x - to.x, dy: from.y - to.y, sx, sy };
}

/** The media query the registry refuses to animate under. */
export function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

export function createHandoffRegistry(reducedMotion: () => boolean = prefersReducedMotion): HandoffRegistry {
  const leavers = new Map<string, Rect>();
  const enterers = new Map<string, { rect: Rect; apply: (handoff: Handoff) => void }>();
  let paired = 0;
  let scheduled = false;

  const pair = (id: string, from: Rect, to: Rect, apply: (handoff: Handoff) => void): void => {
    paired += 1;
    apply({ id, from, to, ...handoffTransform(from, to) });
  };

  const schedule = (): void => {
    if (scheduled) return;
    scheduled = true;
    // A microtask is too early: both bays must have committed. A macrotask is the frame boundary,
    // which is exactly the window the hand-off is defined in.
    setTimeout(() => {
      scheduled = false;
      registry.endFrame();
    }, 0);
  };

  const registry: HandoffRegistry = {
    leave(id, rect) {
      if (reducedMotion()) return;
      const waiting = enterers.get(id);
      if (waiting !== undefined) {
        enterers.delete(id);
        pair(id, rect, waiting.rect, waiting.apply);
        return;
      }
      leavers.set(id, rect);
      schedule();
    },

    enter(id, rect, apply) {
      if (reducedMotion()) return;
      const from = leavers.get(id);
      if (from !== undefined) {
        leavers.delete(id);
        pair(id, from, rect, apply);
        return;
      }
      enterers.set(id, { rect, apply });
      schedule();
    },

    endFrame() {
      leavers.clear();
      enterers.clear();
    },

    pairedCount() {
      return paired;
    },
  };

  return registry;
}

/** One registry for the whole console: a strip can only hand off between two bays if both of them
 *  are talking to the same one. */
export const handoffs = createHandoffRegistry();
