// The activity feed: what each session of the team was doing, as a label the daemon asks its
// client for about every 30 seconds (GET /api/teams/{id}/activity, served since V4-131).
//
// IT IS A SAMPLE AND SAYS SO. A row is what the client reported at that instant, not a log of
// everything it did between samples, so the section's help says it is sampled and the rows print
// newest first, the way a reading is read. A day of samples runs to thousands of rows, so the feed
// prints the newest and counts the rest.
//
// TWO EMPTIES THAT ARE NOT THE SAME ANSWER. "Nothing sampled" means the sampler ran and the client
// had no label to give; "client not matching" means the sampler cannot find the client that
// session was bound to any more (it was closed, or re-attached elsewhere), so no sample CAN arrive.
// Printing one for the other would tell the operator to wait for something that is not coming.
import { Empty, Section } from '@shared/ui';
import type { PendingRoute } from '@shared/api';
import type { TeamActivity } from '@entities/team';
import { fmtInt } from '@shared/lib';
import { H, S, U } from './strings';
import './activity-feed.css';

export interface ActivityFeedPayload {
  activity: TeamActivity[];
  /** False when the sampler can no longer find the client the session was bound to. */
  clientMatching: boolean;
}

export type ActivityFeedState = ActivityFeedPayload | PendingRoute | { error: string } | null;

/** How many samples the feed prints, newest first. */
export const FEED_ROWS = 40;

const secondsOf = (time: string): number => {
  const [h = '0', m = '0', s = '0'] = time.split(':');
  return Number(h) * 3600 + Number(m) * 60 + Number(s);
};

/** Newest first, stable for samples taken in the same second. */
export function feedOrder(activity: readonly TeamActivity[]): TeamActivity[] {
  return activity
    .map((entry, at) => ({ entry, at }))
    .sort((a, b) => secondsOf(b.entry.time) - secondsOf(a.entry.time) || a.at - b.at)
    .map(({ entry }) => entry);
}

/** The feed's empty, when it has one: the five answers the route can give that are not rows. */
export function feedEmpty(state: ActivityFeedState): { text: string; source?: string } | null {
  if (state === null) return { text: S.reading };
  if ('pending' in state) return { text: S.unavailable, source: H.unavailable };
  if ('error' in state) return { text: S.unreadable, source: state.error };
  if (state.activity.length > 0) return null;
  if (!state.clientMatching) return { text: S.notMatching, source: H.notMatching };
  return { text: S.nothingSampled, source: H.nothingSampled };
}

export function ActivityFeed({ state }: { state: ActivityFeedState }) {
  const empty = feedEmpty(state);
  const all = state !== null && 'activity' in state ? feedOrder(state.activity) : [];
  const rows = all.slice(0, FEED_ROWS);
  return (
    <Section title={S.activity} {...(all.length === 0 ? {} : { count: all.length })} info={{ text: H.activity, label: S.activityWhy }}>
      {empty !== null ? <Empty text={empty.text} source={empty.source} /> : (
        <ol className="myx-feed">
          {rows.map((entry) => (
            <li key={`${entry.time}-${entry.member}-${entry.activity}`} className="myx-feed-row">
              <span className="myx-feed-time">{entry.time}</span>
              <span className="myx-feed-member">{entry.member}</span>
              <span className="myx-feed-doing">{entry.activity}</span>
              {entry.detail === '' ? null : <code className="myx-feed-detail">{entry.detail}</code>}
            </li>
          ))}
        </ol>
      )}
      {all.length > rows.length ? <p className="myx-feed-more">{`${fmtInt(all.length - rows.length)} ${U.older}`}</p> : null}
      {/* A client that stopped matching while rows are on screen: the rows are still true, and
          the reason no new one will follow is printed under them. */}
      {empty === null && state !== null && 'activity' in state && !state.clientMatching
        ? <Empty text={S.notMatching} source={H.notMatching} />
        : null}
    </Section>
  );
}
