// The grey each part of a turn is drawn in, on every surface that draws one (docs/design/DESIGN.md
// section 9: a kind of thing takes a grey, never a hue). The proxy's own work is the lightest, a
// wait (for a slot, for the provider) the middle, the reply streaming the strongest. Parts that
// meet on a turn's clock never share a grey, save the two waits, which read as one wait.
import type { Mark } from '@shared/ui';
import type { StageGroup } from './model/derive';

export const STAGE_MARKS: Record<StageGroup, Mark> = {
  ingest: 'series-3',
  queue: 'series-2',
  upstream: 'series-2',
  stream: 'series-1',
  finish: 'series-3',
};
