// The fleet's pure derivations: what a head's holder edge should say, and which provider family a
// head belongs to.
//
// Attention is one printed cause, not a set of flags. The operator's question is "which head needs
// me", and a strip that printed four simultaneous conditions would answer it by making them read to
// nobody. So the causes are ordered by severity and the first one that holds is the one printed.
import type { HeadStatus } from '@shared/api';
import { fmtMs } from '@shared/lib';
import type { Edge } from '@shared/ui';

/** Every condition that can cock a head's strip, in the order they are tested. The order is the
 *  severity order: the first cause that holds is the printed one. */
export const ATTENTION_CAUSES = [
  'unhealthy',
  'version mismatch',
  'token missing',
  'token expired',
  'account excluded',
  'queue at max',
  'topology stale',
] as const;

export type AttentionCause = (typeof ATTENTION_CAUSES)[number];

/** The printed cause, or 'down' for a struck head, or 'ok'. */
export type HeadState = AttentionCause | 'down' | 'ok';

/** What the other slices know about one head. Every field is nullable because a route that has
 *  not answered must not read as a healthy answer. */
export interface HeadSignals {
  /** /api/auth: false means no credential on disk for that head. */
  credentialPresent: boolean | null;
  /** /api/auth: a non-null latch means the refresh is blocked, i.e. the credential expired. */
  refreshLatched: string | null;
  /** /api/accounts: true when this head rides a pool whose selected account is excluded. */
  accountExcluded: boolean;
  /** The same flag /health carries as topologyStale. */
  topologyStale: boolean;
}

/** No signals yet: every nullable field unknown, every flag off. */
export const NO_SIGNALS: HeadSignals = {
  credentialPresent: null,
  refreshLatched: null,
  accountExcluded: false,
  topologyStale: false,
};

export interface HeadAttention {
  edge: Edge;
  cocked: boolean;
  /** True when the head cannot take work. A struck strip is a disabled one, so it outranks every
   *  attention cause: an amber "unhealthy" on a stopped head points the eye at a head that is not
   *  running at all. */
  struck: boolean;
  label: string;
  cause: HeadState;
}

/**
 * The gate is at its ceiling. `max` may be the string 'unlimited', which is never a ceiling, and a
 * head with no gate at all (not running) has no queue either.
 */
export function queueAtMax(head: HeadStatus): boolean {
  const gate = head.gate;
  if (gate === null || gate.max === 'unlimited') return false;
  return gate.queued >= gate.max;
}

export function headAttention(head: HeadStatus, signals: HeadSignals = NO_SIGNALS): HeadAttention {
  if (!head.running) {
    return { edge: 'grey', cocked: false, struck: true, label: 'down', cause: 'down' };
  }
  // Unhealthy is the only red: every other cause is a warning the operator can act on, while an
  // unhealthy head has already broken a promise it made.
  if (!head.healthy) {
    return { edge: 'red', cocked: true, struck: false, label: 'unhealthy', cause: 'unhealthy' };
  }
  const cause: AttentionCause | null =
    head.versionMatch === false ? 'version mismatch'
      : signals.credentialPresent === false ? 'token missing'
        : signals.refreshLatched !== null ? 'token expired'
          : signals.accountExcluded ? 'account excluded'
            : queueAtMax(head) ? 'queue at max'
              : signals.topologyStale ? 'topology stale'
                : null;
  if (cause === null) {
    return { edge: 'green', cocked: false, struck: false, label: 'ok', cause: 'ok' };
  }
  return { edge: 'amber', cocked: true, struck: false, label: cause, cause };
}

/** The provider families the fleet groups by. Derived from the head's auth kind, which is the only
 *  family signal the heads route carries. */
export const PROVIDER_FAMILIES = ['chatgpt', 'grok', 'kimi', 'muse', 'anthropic', 'key', 'local'] as const;
export type ProviderFamily = (typeof PROVIDER_FAMILIES)[number];

/** The auth kinds and the family each belongs to. A kind not listed is `local`: an OpenAI-compatible
 *  base URL on the operator's own machine, which is what the daemon calls it too. */
const FAMILY_BY_AUTH_KIND: Record<string, ProviderFamily> = {
  'chatgpt-oauth': 'chatgpt',
  'grok-oauth': 'grok',
  'kimi-oauth': 'kimi',
  'muse-oauth': 'muse',
  client: 'anthropic',
  'api-key': 'key',
};

export function providerFamily(authKind: string): ProviderFamily {
  return FAMILY_BY_AUTH_KIND[authKind] ?? 'local';
}

/**
 * The monochrome mark a family prints beside its name.
 *
 * A two-letter monogram rather than a glyph: the world's strips are lettered, and a symbol set
 * would be an icon system smuggled in as text. It carries no color — the brief is explicit that
 * provider family is never a color, so this is the whole signal.
 */
export const PROVIDER_MARK: Record<ProviderFamily, string> = {
  chatgpt: 'cg',
  grok: 'gk',
  kimi: 'km',
  muse: 'ms',
  anthropic: 'an',
  key: 'ak',
  local: 'lo',
};

/** A head's in-flight count as printed: `n/max`, or `n` when the gate reports no ceiling. */
export function inflightText(head: HeadStatus): string {
  const gate = head.gate;
  if (gate === null) return '';
  return gate.max === 'unlimited' ? `${gate.inflight}` : `${gate.inflight}/${gate.max}`;
}

/**
 * The head's most recent turn, as far as the routes this page reads can see it: the gate's live
 * list, which carries in-flight turns only.
 *
 * A head with nothing in flight returns null and the strip prints its honest empty rather than a
 * zero. Landed turns are not visible from here at all — `/api/perf/turns` is V4-127 — so this
 * field is deliberately wired to the one turn source that exists, and will need the perf route
 * before it can show a completed turn's time.
 */
export function liveTurnText(head: HeadStatus): string | null {
  const live = head.gate?.live[0];
  if (live === undefined) return null;
  return `${live.phase} ${fmtMs(live.age_ms)}`;
}
