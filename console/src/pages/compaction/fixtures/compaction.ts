// Sample data for the design capture, under the fixture rule (CONTRACTS.md section 4): DEV only,
// behind `?fixture=<name>` in the hash query, never in the shipped dist, and labelled with a grey
// `sample data` holder edge while it is shown.
//
// The outcomes are the names the daemon writes (StreamPromote.kt, StreamCompact.kt), including the
// failure kinds, so the capture shows the red edges a real bad day produces and not only the happy
// path. The rows carry what a live row carries: head, ts, outcome, ms, chars when a summary came
// back, error on a failure, and instructions_source. They carried an HTTP `status` before, which
// the daemon never writes.
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
      model_text: 181,
      model_thinking: 8,
      tooled_no_text: 2,
      empty_model: 14,
      stream_error: 8,
      upstream_error: 1,
    },
    tail: [
      { head: 'claudex', ts: ANCHOR - 3 * MINUTE, outcome: 'model_text', chars: 48_210, ms: 4_180, instructions_source: 'client' },
      { head: 'claude-splice', ts: ANCHOR - 11 * MINUTE, outcome: 'model_thinking', chars: 39_880, ms: 6_450, instructions_source: 'project:/home/dev/splice' },
      { head: 'claude-deepseek', ts: ANCHOR - 24 * MINUTE, outcome: 'tooled_no_text', ms: 2_310, instructions_source: 'client' },
      { head: 'claudex', ts: ANCHOR - 41 * MINUTE, outcome: 'empty_model', ms: 9_120, error: 'api_error', instructions_source: 'client' },
      { head: 'claude-kimi', ts: ANCHOR - 63 * MINUTE, outcome: 'stream_error', ms: 18_770, error: 'overloaded_error', instructions_source: 'global' },
      { head: 'claudex', ts: ANCHOR - 96 * MINUTE, outcome: 'model_text', chars: 51_330, ms: 3_940, instructions_source: 'client' },
    ],
  },
};
