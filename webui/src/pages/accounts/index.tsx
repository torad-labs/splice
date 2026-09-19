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
import { Bay, Empty, HolderEdge } from '@shared/ui';
import { Blank, Fault, Key } from '@shared/controls';
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
  // ONE EXPRESSION, NAMED, because this page's rest state is two conditions rather than one
  // (M2-24, applying M1-123's decision). Five pages spell it `opened === null` twice -- once on
  // aria-hidden and once on the content gate -- and the point of that decision is that the
  // exposure and the content CANNOT DESYNC because they are the same expression. With a compound
  // condition, writing it twice is how they drift, so it is named once and read twice.
  const closed = opened === null && openedHead === null;

  return (
    <div
      className="myx-accounts"
      {...(import.meta.env.DEV && rows !== null && fixture !== null ? { 'data-sample': fixture } : {})}
    >
      <header className="myx-accounts-head">
        <h1 className="myx-accounts-title">{S.title}</h1>
        <ViewTabs pageId={PAGE_ID} defaults={DEFAULT_VIEWS} />
      </header>

      {accountsResource.error === null ? null : <Fault message={accountsResource.error} />}

      {rows === null ? null : <HolderEdge state="grey" label={S.sample} />}

      {accountsResource.data === null && fixture === null ? <Blank strips={4} /> : null}

      <div className={closed ? 'myx-accounts-body' : 'myx-accounts-body myx-accounts-body-open'}>
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

        {/* THE COLUMN IS A ZERO TRACK AT REST AND SWELLS OPEN (M2-24, the last page in the console
            still resting one; the idiom is M1-116's and the landmark is M1-123's, both copied from
            what fleet, sessions and projects SHIPPED rather than from a description of them).
            Measured at rest before this: a 384x92 column in a 408x784 dead region, 20.3% of the
            frame and the third worst in the console -- 63px of that content was the `no account
            opened` placeholder, which goes with the column because an empty naming a panel that
            does not exist yet is a caption, not a report.
            THE 21px THAT WAS NOT A PLACEHOLDER IS AccountLogin, the `add account` reveal, and it
            is NOT lost: HeadActions renders its own AccountLogin (account-login/index.tsx:305), so
            opening any head still reaches it. What changes is that it is one click away instead of
            always on screen, which is a real consequence and is reported on the row rather than
            decided here. */}
        <aside className="myx-accounts-detail myx-swell" aria-label={S.detail} aria-hidden={closed}>
          {closed ? null : (
            <>
              <Key className="myx-swell-close" onClick={() => setOpenKey(null)}>{S.close}</Key>
              {/* The same three-way branch as before, minus the Empty arm that went with the
                  resting column. The final `null` is unreachable by construction -- `closed` is
                  false here, so one of the two is non-null -- and it is written out rather than
                  collapsed to `openedHead ?? ''`, which would paper over that invariant with a
                  fallback that can never be taken. */}
              {opened !== null ? (
                <>
                  <AccountActions kind={opened.kind} label={opened.label} heads={opened.heads} />
                  {/* ONE AccountLogin, NOT TWO (M2-28, found while reading M2-24). This sat
                      outside the branch, and HeadActions renders its OWN AccountLogin
                      (account-login/index.tsx:305), so opening a HEAD drew the `add account`
                      reveal twice in one column. It belongs to the account branch, which has no
                      login of its own; the head branch already carries one. */}
                  {anyHead === null ? null : <AccountLogin head={anyHead} />}
                </>
              ) : openedHead !== null ? (
                <HeadActions head={openedHead} />
              ) : null}
            </>
          )}
        </aside>
      </div>
    </div>
  );
}

export default AccountsPage;
