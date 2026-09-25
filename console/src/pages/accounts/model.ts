// The accounts page's pure half: how a saved view turns the payload into groups, the help an
// api-key head gets, and how a capture fixture is selected. An account's state and its windows are
// widgets/account-table's, shared with the fleet's pool. Kept out of the component so all of it is
// testable without a renderer, and so the sort rule below is pinned rather than eyeballed.
import { SELECTOR_ORDER_TEXT, nearestWindow } from '@entities/account';
import type { AccountRow } from '@entities/account';
import type { View } from '@features/views';
import { ACCOUNT_FIELDS } from '@widgets/account-table';
import { H, S } from './strings';

/** The order the selector walks, once for the page, from the entity's own rule list so the words
 *  cannot drift from the daemon's order. */
export function orderText(): string {
  return `${SELECTOR_ORDER_TEXT.charAt(0).toUpperCase()}${SELECTOR_ORDER_TEXT.slice(1)}.`;
}

export interface AccountGroup {
  key: string;
  accounts: AccountRow[];
}

/** The head-group key for an account no head is riding. Its own group rather than dropped: an
 *  account in the pool that nothing is riding is a fact the operator wants to see. */
export const NO_HEAD_GROUP = S.noHeads;

/**
 * How far into its nearest window an account is, for the `Nearest limit` sort.
 *
 * An account whose provider reports no figure ranks BELOW every account that reported one, which
 * is the opposite of treating the absence as zero. FEATURES 4.5 states the rule for the page
 * ("unknown shown as unknown, never sorted first") and the reason is the operator's: a console
 * that sorts the unknown to the top trains the eye to skip the top, which is exactly where the
 * account that is about to fail belongs.
 */
export function exhaustionRank(account: AccountRow, nowMs: number): number {
  return nearestWindow(account, nowMs)?.used_percent ?? Number.NEGATIVE_INFINITY;
}

function byExhaustion(left: AccountRow, right: AccountRow, nowMs: number): number {
  const delta = exhaustionRank(right, nowMs) - exhaustionRank(left, nowMs);
  if (delta !== 0) return delta;
  // A stable, meaningful tiebreak: the label, so two equally-spent accounts do not swap places
  // between polls and make the table flicker.
  return (left.label ?? '').localeCompare(right.label ?? '');
}

function keysFor(account: AccountRow, group: string | null): string[] {
  if (group === 'head') return account.heads.length === 0 ? [NO_HEAD_GROUP] : [...account.heads].sort();
  if (group === 'provider') return [account.kind];
  return [''];
}

/**
 * The groups for one saved view.
 *
 * Grouping is by provider family (the default), by head, or not at all; sorting is applied INSIDE
 * a group, never across groups, because a table's order is its grouping and re-sorting across it
 * would put an account under a heading that does not name it.
 *
 * One account can appear in more than one group under `By head`, and that is the honest rendering:
 * a login under two heads is one pool row riding both, and hiding it from one of them would make
 * the page disagree with the daemon about which heads are on it.
 */
export function arrangeAccounts(accounts: readonly AccountRow[], view: View, nowMs: number): AccountGroup[] {
  const grouped = new Map<string, AccountRow[]>();
  for (const account of accounts) {
    for (const key of keysFor(account, view.group)) {
      const bucket = grouped.get(key);
      if (bucket === undefined) grouped.set(key, [account]);
      else bucket.push(account);
    }
  }

  const groups = [...grouped.entries()].map(([key, rows]) => ({ key, accounts: rows }));
  for (const group of groups) {
    if (view.sort?.field === 'exhaustion') {
      const ranked = [...group.accounts].sort((left, right) => byExhaustion(left, right, nowMs));
      group.accounts = view.sort.dir === 'desc' ? ranked : ranked.reverse();
    }
  }
  groups.sort((left, right) => left.key.localeCompare(right.key));
  return groups;
}

/** Which columns a view shows. An empty list means every column, so a view that never touched its
 *  fields is not a view that hides everything. */
export function columnsOf(view: View): readonly string[] {
  return view.fields.length === 0 ? ACCOUNT_FIELDS : view.fields;
}

/** One head as the page reads it off GET /api/auth: the Claude logins, the api-key heads, and the
 *  pooled heads while GET /api/accounts is not served. */
export interface HeadRow {
  head: string;
  kind: string;
  present: boolean;
  masked: string | null;
  note: string | null;
  /** api-key heads: the variable the key is read from, the key masked, and the key file. */
  envVar?: string | undefined;
  keyMasked?: string | undefined;
  keyFile?: string | undefined;
}

/**
 * The help an api-key head gets. THE DAEMON READS THREE PLACES IN ORDER
 * (ApiKeyAuthProvider.readKey): the variable in its own environment, then the head's key_file, then
 * the key store `splice key set` writes. A stored key is the one a set replaces, so a head whose key
 * is present is told which source wins over the store, and a head reading a key_file is sent to the
 * file: a `splice key set` there writes a key the daemon never reads.
 */
export function keyHelp(row: HeadRow): string {
  if (row.envVar === undefined) return H.keyOne;
  if (!row.present) return H.keyStore;
  return row.keyFile === undefined ? H.keyReplace : H.keyFile;
}

/** The command that stores a head's key, or null where a set would not reach the daemon: a head
 *  reading a key file with a key in it is sent to the file instead. */
export function keyCommand(row: HeadRow): string | null {
  if (row.envVar === undefined) return null;
  if (row.keyFile !== undefined && row.present) return null;
  return `splice key set ${row.envVar}`;
}

/**
 * The capture fixture's name, or null.
 *
 * Gated on `dev` explicitly rather than reading `import.meta.env` here, so the rule is testable:
 * a fixture must never be reachable in a shipped artifact, and the caller passes the real
 * `import.meta.env.DEV`. CONTRACTS.md section 4 fixes the rest: the address carries
 * `?fixture=<name>`, and a rendered fixture is labelled `Sample data`.
 */
export function fixtureName(search: string, dev: boolean): string | null {
  if (!dev) return null;
  const name = new URLSearchParams(search).get('fixture');
  if (name === null) return null;
  const trimmed = name.trim();
  return trimmed === '' ? null : trimmed;
}
