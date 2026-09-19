// Sample data for the design capture, under the fixture rule (CONTRACTS.md section 4): DEV only,
// behind `?fixture=<name>` in the hash query, never in the shipped dist, and labelled with a grey
// `sample data` holder edge while it is shown.
//
// The outcomes are the daemon's own names (`gateway/compact`), including the two failure kinds, so
// the capture shows the red edges a real bad day produces and not only the happy path.
import type { CompactPayload } from '@shared/api';

/** The events are anchored to the load clock, not to a fixed instant: the feed prints `timeAgo`, so
 *  a sample pinned to a past date would show a page of `645h ago` and teach the reviewer the wrong
 *  density. The sample is a still of a working day, and a working day is recent. */
const ANCHOR = Date.now();

const MINUTE = 60_000;

export const fixtureCompact: CompactPayload = {
  stats: {
    total: 214,
    by_outcome: {
      model_summary: 131,
      model_fallback: 58,
      truncated: 14,
      empty_model: 7,
      stream_error: 3,
      upstream_error: 1,
    },
    tail: [
      { head: 'claudex', ts: ANCHOR - 3 * MINUTE, outcome: 'model_summary', chars: 48_210, ms: 4_180, status: 200 },
      { head: 'claude-splice', ts: ANCHOR - 11 * MINUTE, outcome: 'model_fallback', chars: 39_880, ms: 6_450, status: 200 },
      { head: 'claude-deepseek', ts: ANCHOR - 24 * MINUTE, outcome: 'truncated', chars: 12_040, ms: 2_310, status: 200 },
      { head: 'claudex', ts: ANCHOR - 41 * MINUTE, outcome: 'empty_model', chars: 0, ms: 9_120, status: 200 },
      { head: 'claude-kimi', ts: ANCHOR - 63 * MINUTE, outcome: 'stream_error', chars: 22_400, ms: 18_770, status: 502, error: 'upstream reset the stream after 18s with 22400 chars salvaged' },
      { head: 'claudex', ts: ANCHOR - 96 * MINUTE, outcome: 'model_summary', chars: 51_330, ms: 3_940, status: 200 },
    ],
  },
};
