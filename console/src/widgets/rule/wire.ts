// The live wiring: which entity refetches on which event kind.
//
// WHY IT LIVES IN A WIDGET. CONTRACTS.md section 6 says entity stores subscribe and refetch, and
// that is what happens - but the SUBSCRIPTION cannot be made from an entity: the boundaries wall
// denies entities-api -> entities, so an entity may not import the events slice, and the events
// slice may not import the entities either. The bus and the six entities therefore meet in the one
// layer that can see both. This is chrome, it is mounted for the life of the console, and it is
// where connect() is already called, so the wiring starts and stops with it.
//
// (A row that owns src/app would be the more usual home for composing six slices; this is the
// in-fence one. Named on the ledger rather than left as a surprise.)
//
// Each entity declares its OWN kinds next to its store (entities/<x>/model/live.ts); this module
// only binds them to the refetch, and it is the table below - not a doc - that a test reads.
import { subscribe, subscribeReopen } from '@entities/events';
// The kind vocabulary is imported from the BUS, not from shared/lib/live: a widget may not
// deep-import into the shared slice (the boundaries entry-point rule allows only its barrel), and
// the events slice already re-exports these types as its public surface.
import type { EventKind } from '@entities/events';
import { fetchHeads, LIVE_KINDS as HEADS_KINDS } from '@entities/heads';
import { fetchUsage, LIVE_KINDS as USAGE_KINDS } from '@entities/usage';
import { fetchPerfTurns, LIVE_KINDS as PERF_KINDS } from '@entities/perf';
import { fetchSessions, LIVE_KINDS as SESSION_KINDS } from '@entities/session';
import { fetchAccounts, LIVE_KINDS as ACCOUNT_KINDS } from '@entities/account';
import { fetchAuth, LIVE_KINDS as AUTH_KINDS } from '@entities/auth';

export interface LiveBinding {
  /** The slice that refetches, for a reader (and a test) to name it by. */
  entity: string;
  kinds: readonly EventKind[];
  refetch: () => void | Promise<void>;
}

/**
 * One row per entity: its kinds, and the read that brings it up to date. The refetch is the
 * entity's existing api call, never a new route and never a new parser: an event says WHAT
 * happened, and the console still reads the truth from the route that owns it.
 */
export const LIVE_BINDINGS: readonly LiveBinding[] = [
  { entity: 'heads', kinds: HEADS_KINDS, refetch: () => fetchHeads() },
  { entity: 'usage', kinds: USAGE_KINDS, refetch: () => fetchUsage() },
  { entity: 'perf', kinds: PERF_KINDS, refetch: () => fetchPerfTurns() },
  { entity: 'session', kinds: SESSION_KINDS, refetch: () => fetchSessions() },
  { entity: 'account', kinds: ACCOUNT_KINDS, refetch: () => fetchAccounts() },
  { entity: 'auth', kinds: AUTH_KINDS, refetch: () => fetchAuth() },
];

let active: Array<() => void> | null = null;

/**
 * Start following. Idempotent: the rule mounts once, and a second call (a re-mount, a page that
 * wants to be sure) must not turn every frame into two requests.
 *
 * EVERY call returns a working unsubscribe, including one made while the wiring is already up:
 * a caller that asked for the wiring and then tears down must not leave the first caller's
 * subscriptions behind. Returns the stop the rule keeps for its unmount.
 */
export function wireLive(): () => void {
  if (active === null) {
    active = [
      ...LIVE_BINDINGS.flatMap((binding) =>
        binding.kinds.map((kind) => subscribe(kind, () => void binding.refetch())),
      ),
      // A reopened stream missed every event sent while it was down: re-read each entity once.
      subscribeReopen(() => LIVE_BINDINGS.forEach((binding) => void binding.refetch())),
    ];
  }
  const mine = active;
  return () => {
    mine.forEach((stop) => stop());
    if (active === mine) active = null;
  };
}
