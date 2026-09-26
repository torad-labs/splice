// The five parts of a turn, in words, for every surface that names them. The turns page's stage
// table and the waterfall beside it named the same phases twice (splice work against ingest,
// waiting on provider against upstream), and a reader comparing the two saw ten phases (walkthrough
// polish). Sentence case, three words or fewer (tests/copy.test.ts).
import type { StageGroup } from './model/derive';

export const STAGE_NAMES: Record<StageGroup, string> = {
  ingest: 'Splice work',
  queue: 'Slot wait',
  upstream: 'Provider wait',
  stream: 'Streaming',
  finish: 'Closing',
};
