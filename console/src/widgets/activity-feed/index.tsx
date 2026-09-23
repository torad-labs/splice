// The activity feed: what each session of the team was doing, as a label the daemon asks its
// client for about every 30 seconds (GET /api/teams/{id}/activity, served since V4-131).
//
// IT IS A SAMPLE AND SAYS SO. A row is what the client reported at that instant, not a log of
// everything it did between samples, so the panel is labelled `30 s sample` and prints newest
// first, the way a reading is read.
//
// TWO EMPTIES THAT ARE NOT THE SAME ANSWER. "Nothing sampled" means the sampler ran and the client
// had no label to give; "client no longer matching" means the sampler cannot find the client that
// session was bound to any more (it was closed, or re-attached elsewhere), so no sample CAN arrive.
// Printing one for the other would tell the operator to wait for something that is not coming.
import { Empty, HolderEdge } from '@shared/ui';
import type { PendingRoute } from '@shared/api';
import type { TeamActivity } from '@entities/team';
import { S } from './strings';
import './activity-feed.css';

export interface ActivityFeedPayload {
  activity: TeamActivity[];
  /** False when the sampler can no longer find the client the session was bound to. */
  clientMatching: boolean;
}

export type ActivityFeedState = ActivityFeedPayload | PendingRoute | { error: string } | null;

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
export function feedEmpty(state: ActivityFeedState): { text: string; source: string } | null {
  if (state === null) return { text: 'reading activity', source: 'GET /api/teams/{id}/activity' };
  if ('pending' in state) return { text: 'no activity route', source: `${state.pending} pending` };
  if ('error' in state) return { text: 'activity unreadable', source: state.error };
  if (state.activity.length > 0) return null;
  if (!state.clientMatching) return { text: 'client no longer matching', source: 'the 30 s sampler' };
  return { text: 'nothing sampled yet', source: 'the 30 s sampler' };
}

export function ActivityFeed({ state }: { state: ActivityFeedState }) {
  const empty = feedEmpty(state);
  const rows = state !== null && 'activity' in state ? feedOrder(state.activity) : [];
  return (
    <section className="myx-feed" aria-label={S.activity}>
      <h3 className="myx-feed-title">
        {S.activity}
        <span className="myx-feed-tag">{S.sample}</span>
      </h3>
      {empty !== null ? <Empty text={empty.text} source={empty.source} /> : (
        <ol className="myx-feed-rows">
          {rows.map((entry) => (
            <li key={`${entry.time}-${entry.member}-${entry.activity}`} className="myx-feed-row">
              <HolderEdge state="grey" label="" />
              <span className="myx-feed-cell myx-feed-fig" title={S.time}>{entry.time}</span>
              <span className="myx-feed-cell" title={S.member}>{entry.member}</span>
              <span className="myx-feed-cell myx-feed-doing" title={S.doing}>{entry.activity}</span>
            </li>
          ))}
        </ol>
      )}
      {/* A client that stopped matching while rows are on screen: the rows are still true, and
          the reason no new one will follow is printed under them. */}
      {empty === null && state !== null && 'activity' in state && !state.clientMatching
        ? <Empty text="client no longer matching" source="the 30 s sampler" />
        : null}
    </section>
  );
}
