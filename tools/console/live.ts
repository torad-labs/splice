// A read of what a console page draws, on a page that redraws. Locator.evaluate takes the element's handle and then runs
// the read in a second trip, and a page that replaces its markup in between leaves that handle on a detached element,
// whose rects, computed style and scroll sizes all read as empty. Main's CI went red on it twice on Nov 4, 2026: the
// Usage Results door's qualifier read 0 line boxes, and Settings' peak rows all read top 0.
import type { Locator } from "@playwright/test";

/** [read] run on [loc]'s element while the page has it, read again when a redraw replaced it first. A replaced node never
 *  comes back, so a handle still attached after the read was attached during it. */
export async function live<T>(loc: Locator, read: (el: Element) => T): Promise<T> {
  for (let i = 0; i < TRIES; i++) {
    const handle = await loc.elementHandle();
    try {
      const value = await handle.evaluate(read);
      if (await handle.evaluate((el) => el.isConnected)) return value as T;
    } finally {
      await handle.dispose();
    }
  }
  throw new Error(`${loc} was replaced by a redraw during each of ${TRIES} reads`);
}

// why: a redraw lands between two trips now and then, never on every read; this many in a row means the page never stops
const TRIES = 50;
