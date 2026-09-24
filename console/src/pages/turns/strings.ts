// Labels of the turns page. Three words or fewer, lowercase, no em-dash (the label
// wall globs this file). The pending empties, the "telemetry dropped" gap and the
// capture sentences are not labels and live in the component (CONTRACTS.md
// section 4).
import { ABSENT } from '@shared/lib';

export const S = {
  title: 'turns',
  locked: 'console locked',
  sample: 'sample data',
  inflight: 'in flight',
  landed: 'landed',
  summary: 'summary',
  /** THE TWO NEW MEMBERS (M2-20). Each is a small table with its own header, which is what M1-109
   *  measured the comp earning its printed area with -- not a wider rack. */
  stages: 'time per stage',
  stage: 'stage',
  share: 'share',
  perTurn: 'per turn',
  /** The five parts of a turn, in the order the time is spent (entities/perf StageGroup). */
  stageIngest: 'splice work',
  stageQueue: 'waiting for slot',
  stageUpstream: 'waiting on provider',
  stageStream: 'streaming reply',
  stageFinish: 'closing',
  tokens: 'tokens',
  tokIn: 'in',
  tokCached: 'cached',
  tokWrite: 'cache write',
  tokOut: 'out',
  hit: 'hit',
  detail: 'turn detail',
  close: 'close',
  /** The strip fields, in the order the views declare them. */
  time: 'time',
  head: 'head',
  model: 'model',
  outcome: 'outcome',
  session: 'session',
  compact: 'compact',
  phase: 'phase',
  age: 'age',
  idle: 'idle',
  account: 'account',
  total: 'total',
  firstByte: 'first byte',
  tokensIn: 'in',
  cached: 'cached',
  cacheWrite: 'cache write',
  tokensOut: 'out',
  retries: 'retries',
  attempts: 'attempts',
  inflightCount: 'inflight',
  dropped: 'dropped',
  /** The summary bay. */
  failureShare: 'failed',
  cacheHit: 'cache hit',
  turnTime: 'turn time',
  turnTimeP95: 'turn time p95',
  firstByteP95: 'first byte p95',
  /** Printed before the heads a window holds no turns for, on one line under the rack. */
  noTurnsIn: 'no turns in',
  peakInflight: 'peak concurrent',
  ioDrops: 'lost log rows',
  rows: 'turns',
  coverage: 'coverage',
  refreshes: 'refreshes',
  /** What any cell with no value prints: the approved comp's own glyph (m1 design review B8,
   *  which found thirteen phrasings across the console for one fact). Two characters carry the
   *  whole statement — the basis word that used to sit beside it was a second sentence saying
   *  the same thing, and printed as "- unavailable" it read as a typo. A cell that has a value
   *  but a qualified one still prints its basis: `estimated` and `stale` qualify something. */
  absent: ABSENT,
} as const;

// THE ABSENCE VOCABULARY, written down where the next person writing a cell will see it (M1-66).
// These are DIFFERENT FACTS and collapsing them destroys information; adding a word without one of
// these meanings is how the console reached eleven phrasings for "nothing here".
//   –          (en dash, ABSENT in @shared/lib) nobody reported a value for this cell. The
//              default. It was `n/r`, which no reader could expand.
//   none       the question was asked and its answer is nothing (no tier hands this model out).
//   unknown    we asked and were NOT TOLD - a different fact from none, and never a zero.
//   unavailable  it exists and we cannot reach it.
//   ineligible   it does not apply here.
//   not built    it does not exist yet; a pending route names its row.
// A site whose fact cannot be told from the code KEEPS the word it has and gets a note beside it.
// Renaming an absence you have not understood is how `unknown` silently becomes `none`.
