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
 *  severity order: the first cause that holds is the one reported. Each names what the operator
 *  sees rather than what the code checked: no credential on disk is `signed out` for a login and
 *  `key missing` for an api-key head, a latched refresh is `login expired`, a gate at its ceiling is
 *  `queue full` and a topology the head has not reloaded is `restart needed` (console review,
 *  2026-09-24). The strip's edge prints the shorter EDGE_WORDS below. */
export const ATTENTION_CAUSES = [
  'unhealthy',
  'version mismatch',
  'signed out',
  'key missing',
  'login expired',
  'account excluded',
  'queue full',
  'restart needed',
] as const;

export type AttentionCause = (typeof ATTENTION_CAUSES)[number];

/** The printed cause, or 'down' for a struck head, or 'ok'. */
export type HeadState = AttentionCause | 'down' | 'ok';

/** The word a state prints on the strip's edge. The edge holds 8ch (ui.css), and the causes run to
 *  16: `account excluded` printed as `account…` and `signed out` as `signed o…` (walkthrough S1).
 *  Each word fits whole; the opened head says the cause in a sentence with its fix. */
export const EDGE_WORDS: Record<HeadState, string> = {
  unhealthy: 'failing',
  'version mismatch': 'mismatch',
  'signed out': 'no login',
  'key missing': 'no key',
  'login expired': 'expired',
  'account excluded': 'excluded',
  'queue full': 'full',
  'restart needed': 'restart',
  down: 'down',
  ok: 'ok',
};

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
    return { edge: 'grey', cocked: false, struck: true, label: EDGE_WORDS.down, cause: 'down' };
  }
  // Unhealthy is the only red: every other cause is a warning the operator can act on, while an
  // unhealthy head has already broken a promise it made.
  if (!head.healthy) {
    return { edge: 'red', cocked: true, struck: false, label: EDGE_WORDS.unhealthy, cause: 'unhealthy' };
  }
  // An api-key head has no login to sign out of: its credential is a key in a variable, and
  // `signed out` sent the operator to the accounts page, which cannot set one (walkthrough S2).
  const noCredential: AttentionCause = head.authKind === 'api-key' ? 'key missing' : 'signed out';
  const cause: AttentionCause | null =
    head.versionMatch === false ? 'version mismatch'
      : signals.credentialPresent === false ? noCredential
        : signals.refreshLatched !== null ? 'login expired'
          : signals.accountExcluded ? 'account excluded'
            : queueAtMax(head) ? 'queue full'
              : signals.topologyStale ? 'restart needed'
                : null;
  if (cause === null) {
    return { edge: 'green', cocked: false, struck: false, label: EDGE_WORDS.ok, cause: 'ok' };
  }
  return { edge: 'amber', cocked: true, struck: false, label: EDGE_WORDS[cause], cause };
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
 * The name a family prints. Words and no color: the brief is explicit that provider family is
 * never a color. This replaced a two-letter monogram printed before the family id (`ak key`,
 * `cg chatgpt`): the monogram repeated the word beside it, and the id `key` is not a provider
 * anyone names, so the api-key family now reads `api key` (console review, 2026-09-24).
 */
export const FAMILY_NAME: Record<ProviderFamily, string> = {
  chatgpt: 'chatgpt',
  grok: 'grok',
  kimi: 'kimi',
  muse: 'muse',
  anthropic: 'anthropic',
  key: 'api key',
  local: 'local',
};

/** The provider name an auth kind prints as (`chatgpt-oauth` -> `chatgpt`, `api-key` -> `api
 *  key`): every page that names a provider says it the same way. */
export function familyName(authKind: string): string {
  return FAMILY_NAME[providerFamily(authKind)];
}

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
