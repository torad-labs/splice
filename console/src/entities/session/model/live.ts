// Which live events change this entity's data, declared beside the store they are about.
//
// The registry: a session registering, changing status or going away, and a message edge (a session that just sent a message has rewritten its own registration).
//
// THE WIRING IS NOT HERE, and cannot be: an entity may not import a sibling slice (the boundaries
// wall denies entities-api -> entities), and the event bus IS a sibling slice. So the kinds are
// this slice's own declaration - the public answer to "what changes me" - and
// widgets/rule/wire.ts is where the bus meets the entities, in the one layer that can see both.
import type { EventKind } from '@shared/lib/live';

export const LIVE_KINDS: readonly EventKind[] = ['session.change', 'message.edge'];
