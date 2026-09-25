// The nearest limit: ONE definition, read by the status strip, the fleet page and the accounts page.
// It printed 38% on the strip and fleet and 64% on accounts for one fleet, because the strip and
// fleet read /api/usage, which reports only each head's selected account, while accounts read every
// pooled account (review of #264, 2026-09-25). The definition: the highest used share across the
// windows of the accounts that can serve a turn, with that window's reset.
//
// Two routes carry windows, and both count. /api/accounts carries every pooled account and every
// single login; /api/usage carries what a head reports for itself, the only source for a head no
// account row names (the Claude head builds no pool; a key head has no login). A head that rides an
// account row is read from the rows, which hold every account of its pool, not only the selected one.
//
// An account that cannot serve a turn (refused by its pool, no credential, or spent) ranks after
// every account that can: a spent account beside one with room is not the limit the fleet is nearest,
// the pool has already stepped past it. When none can serve, the fullest of the rest is the limit the
// fleet has reached, and it is named rather than hidden.
//
// A feature because the strip is a widget and the entity model segments are fenced: this is the
// lowest layer a widget and a page may both import that may read two entities.
import {
  COCK_AT_PERCENT, EXHAUSTED_AT_PERCENT, isExcluded, nearestWindow as accountWindow, windowLengthText,
} from '@entities/account';
import type { AccountRow, AccountWindow } from '@entities/account';
import { nearestWindow as headWindow, planLevel, resetsInText } from '@entities/usage';
import type { AuthPayload, UsagePayload } from '@shared/api';
import type { Tone } from '@shared/ui';

export interface NearestLimit {
  /** The head the window rides, for its mark; null for an account no head rides. */
  head: string | null;
  /** The pool's label, or the masked id a head reports; null for a single login or an unnamed one. */
  account: string | null;
  /** The window's reported length (5h, 7d, 30d), model-scoped where the provider scopes it. */
  window: string;
  pct: number;
  /** When the window resets (`in 2h 3m`); null where the provider sent none. */
  reset: string | null;
  /** The daemon's own lines: critical at its critical share, warn past the configured warn share, or
   *  past the account entity's own warn line while /api/usage has not answered. */
  level: 'ok' | 'warn' | 'critical';
}

export interface LimitSources {
  /** Every account row; empty until /api/accounts answers, or where the route does not exist. */
  accounts: readonly AccountRow[];
  usage: UsagePayload | null;
  auth: AuthPayload | null;
}

/** A window's name: its reported length, prefixed by the model where the provider scopes one. The
 *  length is the window's own (Grok reports 30d), never assumed from its slot. */
export function windowName(window: AccountWindow): string {
  const length = windowLengthText(window.seconds);
  return window.model === undefined ? length : `${window.model} ${length}`;
}

type Candidate = Omit<NearestLimit, 'level'> & { serving: boolean };

function fromAccounts(accounts: readonly AccountRow[], nowMs: number): Candidate[] {
  const out: Candidate[] = [];
  for (const account of accounts) {
    const window = accountWindow(account, nowMs);
    if (window === null || window.used_percent === null) continue;
    out.push({
      serving: !isExcluded(account, nowMs) && account.credential_present && window.used_percent < EXHAUSTED_AT_PERCENT,
      head: account.heads[0] ?? null,
      account: account.label,
      window: windowName(window),
      pct: window.used_percent,
      reset: resetsInText(window.reset_epoch_seconds, nowMs),
    });
  }
  return out;
}

function fromHeads({ accounts, usage, auth }: LimitSources, nowMs: number): Candidate[] {
  if (usage === null) return [];
  const covered = new Set(accounts.flatMap((account) => account.heads));
  const out: Candidate[] = [];
  for (const entry of usage.heads) {
    if (covered.has(entry.key)) continue;
    const own = headWindow({ ...usage, heads: [entry] }, auth, nowMs);
    if (own === null) continue;
    out.push({ ...own, serving: own.pct < EXHAUSTED_AT_PERCENT });
  }
  return out;
}

/** The nearest limit, or null when no account and no head reports a window. */
export function nearestLimit(sources: LimitSources, nowMs: number): NearestLimit | null {
  const best = [...fromAccounts(sources.accounts, nowMs), ...fromHeads(sources, nowMs)].reduce<Candidate | null>(
    (held, next) => (held === null || (next.serving !== held.serving ? next.serving : next.pct > held.pct) ? next : held),
    null,
  );
  if (best === null) return null;
  const { head, account, window, pct, reset } = best;
  return { head, account, window, pct, reset, level: planLevel(pct, sources.usage?.warn_pct ?? COCK_AT_PERCENT) };
}

/** The limit's tone, one mapping for every surface that draws it. */
export function limitTone(limit: NearestLimit): Tone {
  return limit.level === 'critical' ? 'danger' : limit.level === 'warn' ? 'warn' : 'ok';
}

/** Whose window, which one and when it resets (`work 7d in 3d 10h`), as a figure's caption. */
export function limitText(limit: NearestLimit): string {
  return [limit.account ?? limit.head, limit.window, limit.reset].filter((part) => part !== null).join(' ');
}
