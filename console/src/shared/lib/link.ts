// A link to one item on its page (V4-219: Needs you opens the item itself, not only the page that
// holds it). ONE parameter for every page, `#/<page>?open=<id>`, so a link is built and read by
// the same functions and no page invents its own.
//
// The id is the page's own: a head's key on Fleet, a check id on Doctor, an account's key on
// Accounts, a session's key on Sessions, a team's id on Teams. A page whose open key wraps that id
// wraps it itself, so the link carries the id and never the page's wrapping.
import { useEffect, useState } from 'react';
import type { Dispatch, SetStateAction } from 'react';
import { useLocation } from 'react-router';

export const OPEN_PARAM = 'open';

/** A link to [id]'s detail on [page]. */
export function itemHref(page: string, id: string): string {
  return `#/${page}?${OPEN_PARAM}=${encodeURIComponent(id)}`;
}

/** The id a link asks the page to open, or null. */
export function linkedId(search: string): string | null {
  return new URLSearchParams(search).get(OPEN_PARAM);
}

/** The id the current route's link asks to open. Pages read it; boards take it as a prop, so a
 *  board renders without a router. */
export function useLinkedId(): string | null {
  return linkedId(useLocation().search);
}

/**
 * The open item: seeded from the link that brought the page up, and seeded again when a link names
 * another item while the page is up (the route stays mounted, so its first state would keep the
 * old one). Closing or opening from the page is the page's own state, as before.
 */
export function useOpen(linked: string | null): [string | null, Dispatch<SetStateAction<string | null>>] {
  const [open, setOpen] = useState<string | null>(linked);
  useEffect(() => {
    if (linked !== null) setOpen(linked);
  }, [linked]);
  return [open, setOpen];
}
