// Which live events change this entity's data, declared beside the store they are about.
//
// The plan windows: a turn ending is what moves the five-hour output-token count, and a head changing state changes which windows exist at all.
//
// THE WIRING IS NOT HERE, and cannot be: an entity may not import a sibling slice (the boundaries
// wall denies entities-api -> entities), and the event bus IS a sibling slice. So the kinds are
// this slice's own declaration - the public answer to "what changes me" - and
// widgets/rule/wire.ts is where the bus meets the entities, in the one layer that can see both.
import type { EventKind } from '@shared/lib/live';

export const LIVE_KINDS: readonly EventKind[] = ['head.state', 'turn.end'];
