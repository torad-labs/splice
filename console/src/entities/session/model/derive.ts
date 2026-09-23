// Pure derivations over the session registry: the three groupings the Sessions page ships as saved
// views (FEATURES.md 4.4) and the timeline bucketer. No rendering, no store, no clock.
import { UNKNOWN_HEAD } from './types';
import type { SessionEdge, SessionRow } from './types';

/** What a session with no value for the grouping field is filed under, so no row is dropped from a
 *  count (FEATURES.md 4.13's rule for turns, applied here). */
export const UNATTRIBUTED = 'unattributed';

export type GroupBy = 'head' | 'repo' | 'team';

export interface SessionGroup {
  key: string;
  count: number;
  sessions: SessionRow[];
}

/**
 * The group key for one row. Every branch has a stated fallback rather than a dropped row:
 *
 * - head: the daemon writes UNKNOWN_HEAD itself when splice did not launch the session.
 * - repo: the resolved git root; until V4-130 lands the field is absent, so the row groups under
 *   its cwd, which is the same answer the resolver gives for a cwd outside the trusted root set.
 *   A row with neither is unattributed, never silently missing.
 * - team: the operator's assignment, which does not exist yet (V4-131/V4-133), so unattributed is
 *   the honest group for every row today.
 */
export function groupKeyOf(row: SessionRow, by: GroupBy): string {
  switch (by) {
    case 'head':
      return row.head === '' ? UNKNOWN_HEAD : row.head;
    case 'repo':
      return row.repo?.root ?? row.cwd ?? UNATTRIBUTED;
    case 'team':
      return row.team !== undefined && row.team !== null && row.team !== '' ? row.team : UNATTRIBUTED;
  }
}

/** The rows filed by [by], biggest group first and ties broken by key so the order is stable. */
export function groupSessions(rows: readonly SessionRow[], by: GroupBy): SessionGroup[] {
  const groups = new Map<string, SessionGroup>();
  for (const row of rows) {
    const key = groupKeyOf(row, by);
    const existing = groups.get(key);
    if (existing === undefined) groups.set(key, { key, count: 1, sessions: [row] });
    else {
      existing.count++;
      existing.sessions.push(row);
    }
  }
  return [...groups.values()].sort((a, b) => (b.count - a.count) || a.key.localeCompare(b.key));
}

/** Which timestamp places a session on the timeline: when it started, or when it was last heard
 *  from. The Sessions page ships "timeline of the day", which is a day of starts. */
export type SessionTimeField = 'started_at' | 'updated_at';

export interface TimelineOptions {
  /** Window start, epoch ms. Included. */
  from: number;
  /** Window end, epoch ms. EXCLUDED, so adjacent windows never count a session twice. */
  to: number;
  /** Bucket width in ms. Must be positive. */
  bucketMs: number;
  by?: SessionTimeField;
}

export interface TimelineBucket {
  start: number;
  end: number;
  sessions: SessionRow[];
}

export interface Timeline {
  /** Every bucket in the window, oldest first, EMPTY ONES INCLUDED: an idle hour has to be a gap
   *  on the page, exactly as the economics chart keeps its idle hours (economics/model/derive.ts
   *  hourly), and a bucket list with holes silently restates the day. */
  buckets: TimelineBucket[];
  /** Rows the timeline cannot place because the chosen timestamp is absent. Reported beside the
   *  buckets, never dropped: the daemon's schema is the client's and a registration without
   *  `startedAt` is a real row. Rows that fall OUTSIDE the window are not listed here - the window
   *  is what the caller asked for. */
  undated: SessionRow[];
}

export function timeline(rows: readonly SessionRow[], options: TimelineOptions): Timeline {
  const { from, to, bucketMs } = options;
  const field: SessionTimeField = options.by ?? 'started_at';
  if (bucketMs <= 0) throw new Error(`timeline needs a positive bucketMs, got ${bucketMs}`);
  const buckets: TimelineBucket[] = [];
  for (let start = from; start < to; start += bucketMs) {
    buckets.push({ start, end: Math.min(start + bucketMs, to), sessions: [] });
  }
  const undated: SessionRow[] = [];
  for (const row of rows) {
    const ts = row[field];
    if (ts === null) {
      undated.push(row);
      continue;
    }
    // A row outside the window belongs to no bucket of THIS window; it is not undated.
    if (ts < from || ts >= to) continue;
    const bucket = buckets[Math.floor((ts - from) / bucketMs)];
    if (bucket !== undefined) bucket.sessions.push(row);
  }
  return { buckets, undated };
}

/** The availability split the page prints counts for, in the daemon's own three states. */
export interface AvailabilityCounts {
  live: number;
  stale: number;
  gone: number;
}

export function availabilityCounts(rows: readonly SessionRow[]): AvailabilityCounts {
  const counts: AvailabilityCounts = { live: 0, stale: 0, gone: 0 };
  for (const row of rows) counts[row.availability]++;
  return counts;
}

/**
 * The addresses a session exchanged messages with, most recent first, deduplicated.
 *
 * Direction is deliberately NOT filtered: the peer on the other end is the same peer whether this
 * session sent the message or received it, and a hand-off reads as one relationship, not two.
 */
export function peerAddresses(edges: readonly SessionEdge[]): string[] {
  const seen = new Set<string>();
  const ordered: string[] = [];
  for (const edge of [...edges].sort((a, b) => b.at - a.at)) {
    const peer = edge.direction === 'out' ? edge.to : edge.from;
    if (peer === '' || seen.has(peer)) continue;
    seen.add(peer);
    ordered.push(peer);
  }
  return ordered;
}

/**
 * The readable name behind an address, or null when no registered session owns it.
 *
 * SessionRow.address is `uds:<socket path>` for the session that minted that socket, which is the
 * same string the edge carries, so the join is exact and needs no parsing. A peer that is not a
 * registered session (a stale edge to a session that has since gone) resolves to null, and the
 * caller prints the address rather than inventing a name.
 */
export function nameForAddress(rows: readonly SessionRow[], address: string): string | null {
  const owner = rows.find((row) => row.address === address);
  if (owner === undefined) return null;
  return owner.name !== null && owner.name !== '' ? owner.name : address;
}

/** A session's own label: its name, else the first 8 of its session id, else its pid. */
export function sessionLabel(row: SessionRow): string {
  if (row.name !== null && row.name !== '') return row.name;
  if (row.session_id !== null && row.session_id !== '') return row.session_id.slice(0, 8);
  return row.pid === null ? 'unknown' : `pid ${row.pid}`;
}
