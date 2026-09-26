// The lanes' arithmetic, with no DOM and no clock: which hand-offs are drawn, which of them just
// crossed, and the curve between two cards. Split from lanes.tsx so a test holds each rule against
// plain numbers; the component only measures the cards and hands their boxes in.

/** One hand-off drawn between two cards: the newest message from one to the other. */
export interface LaneArc {
  /** The pair, `from>to`: one arc per direction, however many messages crossed it. */
  key: string;
  from: string;
  to: string;
  /** Epoch ms of the pair's newest message. */
  at: number;
}

/** A message as the lanes read it: the card that sent it, the card it reached, and when. */
export interface LaneMessage {
  from: string;
  to: string;
  at: number;
}

/** A card's box inside the lanes, in px from the lanes' own top-left corner. */
export interface Box {
  left: number;
  top: number;
  width: number;
  height: number;
}

/** A card as the columns read it: its key and when it started. */
export interface Dated {
  key: string;
  start: number | null;
}

/** Where the cards stand across the board: each card's column, and how many cards are dated. */
export interface Columns {
  at: Map<string, number>;
  dated: number;
  undated: number;
}

/**
 * Each card's column, shared by every lane: the dated cards of the whole board in start order,
 * oldest first, one column each. No two cards stand one above the other, so an arc between two
 * lanes runs across the board instead of down a gap, and a strand read left to right is the order
 * the sessions started. A card with no start makes no claim on that order (Marlin, 2026-09-25): the
 * undated cards stand apart, one column each in lane order after a gap column, under a caption that
 * says their start is unknown (lanes.tsx).
 */
export function columnsOf(lanes: readonly (readonly Dated[])[]): Columns {
  const all = lanes.flatMap((cards, lane) => cards.map((card, at) => ({ card, lane, at })));
  const dated = all.filter((entry) => entry.card.start !== null);
  dated.sort((left, right) => (left.card.start ?? 0) - (right.card.start ?? 0) || left.lane - right.lane || left.at - right.at);
  const undated = all.filter((entry) => entry.card.start === null);
  const gap = dated.length > 0 && undated.length > 0 ? 1 : 0;
  return {
    at: new Map([
      ...dated.map((entry, column) => [entry.card.key, column] as const),
      ...undated.map((entry, column) => [entry.card.key, dated.length + gap + column] as const),
    ]),
    dated: dated.length,
    undated: undated.length,
  };
}

/** The strand's columns: one per dated card, then a gap and one per undated card when there are both. */
export function trackTemplate({ dated, undated }: Pick<Columns, 'dated' | 'undated'>): string {
  const cards = (count: number): string => `repeat(${count}, minmax(var(--lane-card-min), 1fr))`;
  if (dated === 0 || undated === 0) return cards(Math.max(1, dated + undated));
  return `${cards(dated)} var(--lane-gap) ${cards(undated)}`;
}

/** The arcs to draw: the newest message of each direction between two cards on the lanes, oldest
 *  first so the newest paints on top. A message with an end that is not a card (a session gone,
 *  a peer outside the board) is not drawn: the hand-offs list still names it. */
export function newestArcs(messages: readonly LaneMessage[], cards: ReadonlySet<string>): LaneArc[] {
  const newest = new Map<string, LaneArc>();
  for (const message of messages) {
    if (message.from === message.to || !cards.has(message.from) || !cards.has(message.to)) continue;
    const key = `${message.from}>${message.to}`;
    const held = newest.get(key);
    if (held === undefined || message.at > held.at) newest.set(key, { key, from: message.from, to: message.to, at: message.at });
  }
  return [...newest.values()].sort((left, right) => left.at - right.at);
}

/** The newest message of each direction of every pair, drawn or not: what the lanes have SEEN is
 *  kept over every message, so a card that appears after its hand-off (a session the registry
 *  listed late) does not make that old hand-off look like one crossing now. */
export function newestPairs(messages: readonly LaneMessage[]): Map<string, number> {
  const newest = new Map<string, number>();
  for (const message of messages) {
    if (message.from === message.to) continue;
    const key = `${message.from}>${message.to}`;
    newest.set(key, Math.max(newest.get(key) ?? message.at, message.at));
  }
  return newest;
}

/** The arcs a message crossed since `seen` was taken: a pair whose newest message moved, or a pair
 *  that was not there at all. What was on the board at first paint never counts, so opening the
 *  page does not fire every arc at once. */
export function crossings(seen: ReadonlyMap<string, number>, arcs: readonly LaneArc[]): string[] {
  return arcs.filter((arc) => {
    const was = seen.get(arc.key);
    return was === undefined || arc.at > was;
  }).map((arc) => arc.key);
}

/** One read of the hand-offs: what is seen after it, and the drawn arcs that crossed since the last.
 *  Hand-offs not read yet (null) are no read at all, and the first read only takes note: an empty
 *  list taken as the first read made every message already sent cross the board when the chat
 *  arrived, on every open (Hitstop, 2026-09-25). */
export function readOf(
  seen: ReadonlyMap<string, number> | null,
  messages: readonly LaneMessage[] | null,
  arcs: readonly LaneArc[],
): { seen: ReadonlyMap<string, number> | null; crossed: string[] } {
  if (messages === null) return { seen, crossed: [] };
  const pairs = newestPairs(messages);
  if (seen === null) return { seen: pairs, crossed: [] };
  const next = new Map(seen);
  for (const [key, at] of pairs) next.set(key, Math.max(next.get(key) ?? at, at));
  return { seen: next, crossed: crossings(seen, arcs) };
}

/** Which side of a card an arc leaves from when its pair runs both ways: the two directions would
 *  otherwise draw one curve over the other. -1 left of centre, 1 right, 0 alone. */
export function sideOf(arc: LaneArc, arcs: readonly LaneArc[]): -1 | 0 | 1 {
  if (!arcs.some((other) => other.from === arc.to && other.to === arc.from)) return 0;
  return arc.from < arc.to ? 1 : -1;
}

/** One decimal: enough for a path, short enough to read in a test. */
const at = (value: number): number => Math.round(value * 10) / 10;

/** The least a curve bends, so two cards a row apart still read as joined by an arc, not a line. */
const MIN_BEND = 16;

/** The most an arch over one row rises, so a hand-off across a wide lane stays near its cards. */
const MAX_LIFT = 64;

/**
 * The curve from one card to another. Between rows it leaves the sender's facing edge and lands on
 * the receiver's, bending half the gap at each end; within one row it arches over both. `side`
 * moves both ends off centre, for a pair drawn both ways.
 */
export function arcPath(from: Box, to: Box, side: -1 | 0 | 1 = 0): string {
  const x1 = from.left + from.width / 2 + (side * from.width) / 6;
  const x2 = to.left + to.width / 2 + (side * to.width) / 6;
  if (to.top >= from.top + from.height) {
    const y1 = from.top + from.height;
    const y2 = to.top;
    const bend = Math.max((y2 - y1) / 2, MIN_BEND);
    return `M${at(x1)} ${at(y1)} C${at(x1)} ${at(y1 + bend)} ${at(x2)} ${at(y2 - bend)} ${at(x2)} ${at(y2)}`;
  }
  if (to.top + to.height <= from.top) {
    const y1 = from.top;
    const y2 = to.top + to.height;
    const bend = Math.max((y1 - y2) / 2, MIN_BEND);
    return `M${at(x1)} ${at(y1)} C${at(x1)} ${at(y1 - bend)} ${at(x2)} ${at(y2 + bend)} ${at(x2)} ${at(y2)}`;
  }
  const lift = Math.min(Math.max(Math.abs(x2 - x1) / 3, MIN_BEND), MAX_LIFT);
  const top = Math.min(from.top, to.top) - lift;
  return `M${at(x1)} ${at(from.top)} C${at(x1)} ${at(top)} ${at(x2)} ${at(top)} ${at(x2)} ${at(to.top)}`;
}
