// The five parts of a turn, in words, for every surface that names them. The turns page's stage
// table and the waterfall beside it named the same phases twice (splice work against ingest,
// waiting on provider against upstream), and a reader comparing the two saw ten phases (walkthrough
// polish). Lowercase, three words or fewer, no em-dash: the label wall globs this file.
import type { StageGroup } from './model/derive';

export const STAGE_NAMES: Record<StageGroup, string> = {
  ingest: 'splice work',
  queue: 'waiting for slot',
  upstream: 'waiting on provider',
  stream: 'streaming reply',
  finish: 'closing',
};
