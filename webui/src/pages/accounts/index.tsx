// The accounts page. One rack of every account of every provider, and the end of the manual hunt
// through browser logins for an account with room (operator, 2026-09-17).
//
// Two things this page refuses to do, both because the world's rules say so. It never prints a
// zero for a window a provider did not report — it prints the honest empty and says who is silent.
// And it never claims to have switched an account: a switch is a pin on the NEXT turn, so the
// button says switch and the note beside it says what that means.
//
// Every row on this page is a Strip, the per-head fallback cards included: while GET /api/accounts
// is still a row, the fallback is the same rack populated from the auth card, not a different page
// wearing the same route.
import { useEffect, useState } from 'react';
import { useLocation } from 'react-router';
import { SELECTOR_ORDER_TEXT, nextTarget } from '@entities/account';
import { startAccountsPolling, useAccounts } from '@entities/account';
import type { AccountRow } from '@entities/account';
import { startAuthPolling, useAuth } from '@entities/auth';
import { AccountActions, AccountLogin, HeadActions, HeadAuthStrip } from '@features/account-login';
import { useViews, ViewTabs } from '@features/views';
import type { View } from '@features/views';
import { Bay, Empty, ErrorNote, HolderEdge, SkeletonRows } from '@shared/ui';
import { AccountStrip } from '@widgets/account-strip';
import { EMPTIES, arrangeAccounts, columnsOf, fixtureName } from './model';
import { fixtureAccounts, fixtureNow } from './fixtures/accounts';
import { dispositions } from './coverage';
import { S } from './strings';
import './accounts.css';

export { dispositions };

const PAGE_ID = 'accounts';
const POLL_MS = 15000;
const CLOCK_MS = 30000;

/** The three views this page ships with. `by provider` is first because it is the default. */
export const DEFAULT_VIEWS: readonly View[] = [
  { id: 'by-provider', name: S.byProvider, layout: 'bay', filter: {}, sort: null, group: 'provider', fields: [] },
  { id: 'nearest', name: S.nearest, layout: 'bay', filter: {}, sort: { field: 'exhaustion', dir: 'desc' }, group: null, fields: [] },
  { id: 'by-head', name: S.byHead, layout: 'bay', filter: {}, sort: null, group: 'head', fields: [] },
];

/** The key the detail column is showing. One thing open at a time, addressed by what it is. */
export function openAccountKey(account: AccountRow): string {
  return `account:${account.kind}:${account.label}`;
}

export function openHeadKey(head: string): string {
  return `head:${head}`;
}

/** A slow clock, so the reset lines age instead of freezing at first render. */
function useNow(intervalMs: number, fixed: number | null): number {
  const [now, setNow] = useState(() => fixed ?? Date.now());
  useEffect(() => {
    if (fixed !== null) return;
    const id = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(id);
  }, [intervalMs, fixed]);
  return now;
}

/** One head as the page reads it off GET /api/auth, for the fallback rack. */
interface HeadRow {
  head: string;
  kind: string;
  present: boolean;
  masked: string | null;
  note: string | null;
}

function HeadBay({ label, rows, openKey, onOpen }: {
  label: string;
  rows: readonly HeadRow[];
  openKey: string | null;
  onOpen: (key: string) => void;
}) {
  return (
    <Bay label={label} count={rows.length}>
      {rows.map((row) => (
        <HeadAuthStrip
          key={row.head}
          head={row.head}
          kind={row.kind}
          present={row.present}
          masked={row.masked}
          note={row.note}
          selected={openKey === openHeadKey(row.head)}
          onOpen={() => onOpen(openHeadKey(row.head))}
        />
      ))}
    </Bay>
  );
}

/**
 * The Claude head's bay. It is not a pool and never will be: `auth = { kind = "client" }` builds no
 * poller, no availability, no exclusion and no per-turn selection, and one login per head is shared
 * by every session on it. The statement is an honest empty rather than a label, because CONTRACTS
 * section 4 keeps sentences out of the string table and this is a sentence.
 */
function ClaudeBay({ rows, openKey, onOpen }: {
  rows: readonly HeadRow[];
  openKey: string | null;
  onOpen: (key: string) => void;
}) {
  return (
    <Bay label={S.claudeBay} count={rows.length}>
      {rows.map((row) => (
        <HeadAuthStrip
          key={row.head}
          head={row.head}
          kind={row.kind}
          present={row.present}
          masked={row.masked}
          note={row.note}
          selected={openKey === openHeadKey(row.head)}
          onOpen={() => onOpen(openHeadKey(row.head))}
        />
      ))}
      <Empty text="launch-time selected, never a pool" source="one login per claude head" />
    </Bay>
  );
}

export function AccountsPage() {
  const { search } = useLocation();
  const views = useViews(PAGE_ID, DEFAULT_VIEWS);
  const active = views.active;
  const accountsResource = useAccounts((state) => state);
  const authResource = useAuth((state) => state);

  const fixture = fixtureName(search, import.meta.env.DEV);
  const nowMs = useNow(CLOCK_MS, fixtureNow(fixture));

  useEffect(() => startAccountsPolling(POLL_MS), []);
  useEffect(() => startAuthPolling(POLL_MS), []);

  const [openKey, setOpenKey] = useState<string | null>(null);
  const toggle = (key: string) => setOpenKey((current) => (current === key ? null : key));

  const rows = fixtureAccounts(fixture);
  const payload = rows === null ? accountsResource.data : { accounts: rows };
  const pending = payload !== null && 'pending' in payload;
  const accounts: readonly AccountRow[] = payload === null || pending ? [] : payload.accounts;

  const target = nextTarget(accounts);
  const groups = arrangeAccounts(accounts, active);
  const columns = columnsOf(active);

  // The claude head is `client`: it appears in the auth card and never in the pool payload.
  const headRows: HeadRow[] = Object.entries(authResource.data ?? {}).map(([head, auth]) => ({
    head,
    kind: auth.kind,
    present: auth.present,
    masked: auth.account_id_masked ?? null,
    note: auth.refresh_latched ?? null,
  }));
  const pooledHeads = headRows.filter((row) => row.kind !== 'client');
  const claudeHeads = headRows.filter((row) => row.kind === 'client');

  const opened = accounts.find((account) => openAccountKey(account) === openKey) ?? null;
  const openedHead = opened === null && openKey?.startsWith('head:') === true
    ? openKey.slice('head:'.length)
    : null;
  const anyHead = opened?.heads[0] ?? pooledHeads[0]?.head ?? claudeHeads[0]?.head ?? null;

  return (
    <div className="myx-accounts">
      <header className="myx-accounts-head">
        <h1 className="myx-accounts-title">{S.title}</h1>
        <ViewTabs pageId={PAGE_ID} defaults={DEFAULT_VIEWS} />
      </header>

      {accountsResource.error === null ? null : <ErrorNote message={accountsResource.error} />}

      {fixture === null ? null : <HolderEdge state="grey" label={S.sample} />}

      {accountsResource.data === null && fixture === null ? <SkeletonRows rows={4} cols={6} /> : null}

      <div className="myx-accounts-body">
        <div className="myx-accounts-bays">
          {pending ? (
            <>
              {/* Until the pooled route exists, the page shows what the daemon reports today per
                  head rather than an empty screen: the per-head auth card is a real answer to
                  "which account is this head on". */}
              <HeadBay label={S.bay} rows={pooledHeads} openKey={openKey} onOpen={toggle} />
              <Empty text={EMPTIES.pooledPending.text} source={EMPTIES.pooledPending.source} />
            </>
          ) : groups.length === 0 ? (
            <Empty text={EMPTIES.noAccounts.text} source={EMPTIES.noAccounts.source} />
          ) : (
            groups.map((group) => (
              <Bay
                key={group.key === '' ? S.bay : group.key}
                label={group.key === '' ? S.bay : group.key}
                count={group.accounts.length}
                actions={<span className="myx-accounts-order">{SELECTOR_ORDER_TEXT}</span>}
              >
                {group.accounts.map((account) => {
                  const key = openAccountKey(account);
                  return (
                    <AccountStrip
                      key={key}
                      account={account}
                      isNext={target !== null && target.label === account.label}
                      nextRule={target !== null && target.label === account.label ? target.rule : ''}
                      columns={columns}
                      nowMs={nowMs}
                      selected={openKey === key}
                      onOpen={() => toggle(key)}
                    />
                  );
                })}
              </Bay>
            ))
          )}

          <ClaudeBay rows={claudeHeads} openKey={openKey} onOpen={toggle} />
        </div>

        <aside className="myx-accounts-detail" aria-label={S.detail}>
          {opened !== null ? (
            <AccountActions kind={opened.kind} label={opened.label} heads={opened.heads} />
          ) : openedHead !== null ? (
            <HeadActions head={openedHead} />
          ) : (
            <Empty text={EMPTIES.noOpened.text} source={EMPTIES.noOpened.source} />
          )}
          {anyHead === null ? null : <AccountLogin head={anyHead} />}
        </aside>
      </div>
    </div>
  );
}

export default AccountsPage;
