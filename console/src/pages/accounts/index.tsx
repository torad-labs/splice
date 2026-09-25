// Accounts: every account of every provider, drawn by how much of each window it has used, and the
// end of the manual hunt through browser logins for an account with room (operator, 2026-09-17).
//
// A live list (docs/design/DESIGN.md section 7): the accounts by state as one split bar, the window
// nearest its limit, and one row per account with its five-hour and weekly windows as meters and
// their reset countdowns beside them. Two things this page refuses to do, both because the world's
// rules say so. It never draws a zero for a window a provider did not report: the cell prints the
// absence and the state says unknown. And it never claims to have switched an account: a switch is a
// pin on the NEXT turn, and the action says so when it lands.
//
// The Claude heads and the api-key heads have tables of their own below the pools: neither has a
// pool, and GET /api/accounts names neither.
import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { useLocation } from 'react-router';
import { startAccountsPolling, useAccounts } from '@entities/account';
import type { AccountRow, AccountsState } from '@entities/account';
import { signInOf, startAuthPolling, startKeysPolling, useAuth, useKeys } from '@entities/auth';
import type { KeysPayload, KeyState, SignInState } from '@entities/auth';
import { HeadMark } from '@entities/control-status';
import { familyName } from '@entities/heads';
import { startUsagePolling, useUsage } from '@entities/usage';
import { AccountActions, AccountLogin, HeadActions } from '@features/account-login';
import { ApiKeyForm, sourceWord } from '@features/api-key';
import { limitText, limitTone, nearestLimit } from '@features/nearest-limit';
import { useViews, ViewTabs } from '@features/views';
import type { View } from '@features/views';
import type { AuthPayload, UsagePayload } from '@shared/api';
import { Blank, Fault } from '@shared/controls';
import { ABSENT, fmtInt } from '@shared/lib';
import { Badge, DataTable, DetailPanel, Empty, InfoTip, KeyValue, Meter, PageHeader, Section, StackedBar, Stat, StatRow } from '@shared/ui';
import type { Column, RowGroup } from '@shared/ui';
import {
  AccountFacts, AccountStateBadge, accountColumns, accountKey, accountName, accountTone, countdown, stateOf, stateParts,
} from '@widgets/account-table';
import { fixtureAccounts, fixtureNow } from './fixtures/accounts';
import { dispositions } from './coverage';
import { arrangeAccounts, columnsOf, fixtureName, headNote, keyHelp, keyTarget, nextReset, orderText } from './model';
import type { HeadRow } from './model';
import { H, S, U } from './strings';
import './accounts.css';

/** The columns an opened account's facts repeat, so an open panel takes their width and the
 *  windows keep theirs. */
const OPEN_REPEATS: ReadonlySet<string> = new Set(['provider', 'plan', 'heads']);

export { dispositions };
export type { HeadRow };

const PAGE_ID = 'accounts';
const POLL_MS = 15000;
const CLOCK_MS = 30000;

export const DEFAULT_VIEWS: readonly View[] = [
  { id: 'by-provider', name: S.byProvider, layout: 'bay', filter: {}, sort: null, group: 'provider', fields: [] },
  { id: 'nearest', name: S.nearest, layout: 'bay', filter: {}, sort: { field: 'exhaustion', dir: 'desc' }, group: null, fields: [] },
  { id: 'by-head', name: S.byHead, layout: 'bay', filter: {}, sort: null, group: 'head', fields: [] },
];

/** The key the detail panel is showing. One thing open at a time, addressed by what it is. */
export function openAccountKey(account: AccountRow): string {
  return `account:${accountKey(account)}`;
}

export function openHeadKey(head: string): string {
  return `head:${head}`;
}

export function openKeyHeadKey(head: string): string {
  return `key:${head}`;
}

/** A slow clock, so the countdowns age instead of freezing at first render. */
function useNow(intervalMs: number, fixed: number | null): number {
  const [now, setNow] = useState(() => fixed ?? Date.now());
  useEffect(() => {
    if (fixed !== null) return;
    const id = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(id);
  }, [intervalMs, fixed]);
  return now;
}

/** The figures the page leads with: every account by state, the nearest limit (the one definition
 *  the strip and the fleet print), the soonest reset of any window, and how many accounts the pools
 *  are refusing. */
function Figures({ accounts, usage, auth, nowMs }: {
  accounts: readonly AccountRow[];
  usage: UsagePayload | null;
  auth: AuthPayload | null;
  nowMs: number;
}) {
  const nearest = nearestLimit({ accounts, usage, auth }, nowMs);
  const reset = nextReset(accounts, nowMs);
  const excluded = accounts.filter((account) => stateOf(account, nowMs) === 'excluded').length;
  const nearestTile = nearest === null ? <Stat label={S.nearestLimit} value={ABSENT} /> : (
    <Stat
      label={S.nearestLimit}
      value={`${Math.round(nearest.pct)}${U.used}`}
      {...(limitTone(nearest) === 'ok' ? {} : { tone: limitTone(nearest) })}
      chart={<Meter value={nearest.pct / 100} tone={limitTone(nearest)} label={S.nearestLimit} />}
      sub={limitText(nearest)}
    />
  );
  return (
    <StatRow>
      <Stat
        label={S.accounts}
        value={fmtInt(accounts.length)}
        chart={<StackedBar parts={stateParts(accounts, nowMs)} label={S.accounts} legend format={fmtInt} />}
      />
      {nearestTile}
      <Stat label={S.nextReset} value={(reset === null ? null : countdown(reset, nowMs)) ?? ABSENT} />
      <Stat label={S.excluded} value={fmtInt(excluded)} {...(excluded > 0 ? { tone: 'warn' as const } : {})} />
    </StatRow>
  );
}

/** A login's badge: signed in is ok, signed out a warning the operator can act on (as the fleet's
 *  edge reads it), and unverified neutral, never green: nobody has measured it yet. */
const SIGN_IN: Record<SignInState, { tone: 'ok' | 'warn' | 'neutral'; word: string }> = {
  signedIn: { tone: 'ok', word: S.signedIn },
  signedOut: { tone: 'warn', word: S.signedOut },
  unverified: { tone: 'neutral', word: S.unverified },
};

function headColumns(nowMs: number): Column<HeadRow>[] {
  return [
    { key: 'head', label: S.head, width: '24%', primary: true, cell: (row) => <HeadMark head={row.head} /> },
    { key: 'account', label: S.account, width: '24%', mono: true, cell: (row) => row.masked ?? ABSENT },
    {
      key: 'state',
      label: S.state,
      width: '16%',
      cell: (row) => {
        const badge = SIGN_IN[signInOf(row).state];
        return <Badge tone={badge.tone} quiet>{badge.word}</Badge>;
      },
    },
    { key: 'note', label: S.note, cell: (row) => headNote(row, nowMs) },
  ];
}

function keyColumns(): Column<HeadRow>[] {
  return [
    { key: 'head', label: S.head, width: '24%', primary: true, cell: (row) => <HeadMark head={row.head} /> },
    { key: 'variable', label: S.variable, width: '28%', mono: true, cell: (row) => row.envVar ?? ABSENT },
    { key: 'key', label: S.key, width: '20%', mono: true, cell: (row) => (row.present ? row.keyMasked ?? ABSENT : ABSENT) },
    {
      key: 'state',
      label: S.state,
      cell: (row) => <Badge tone={row.present ? 'ok' : 'warn'} quiet>{row.present ? S.keySet : S.keyMissing}</Badge>,
    },
  ];
}

/** An opened api-key head: where its key comes from now, and the form that stores or removes it
 *  when a store would reach the daemon (V4-220 item 1). */
export function ApiKeyDetail({ row, keyState = null }: {
  row: HeadRow;
  /** Its variable as GET /api/keys reads it; null until that read lands. */
  keyState?: KeyState | null;
}) {
  const target = keyTarget(row);
  const reader = keyState?.heads.find((each) => each.head === row.head) ?? null;
  const rows: [string, ReactNode][] = [
    [S.state, <Badge key="state" tone={row.present ? 'ok' : 'warn'}>{row.present ? S.keySet : S.keyMissing}</Badge>],
    [S.variable, row.envVar ?? ABSENT],
    [S.keyFile, row.keyFile ?? ABSENT],
    [S.key, row.present ? row.keyMasked ?? ABSENT : ABSENT],
    [S.readFrom, reader === null ? ABSENT : sourceWord(reader.source)],
  ];
  const help = <InfoTip text={keyHelp(row)} label={S.aboutKey} />;
  return (
    <>
      <KeyValue rows={rows} />
      {target === null ? <p className="myx-ac-help">{help}</p> : <ApiKeyForm name={target} stored={keyState?.stored === true} aside={help} />}
    </>
  );
}

/** The board, drawn from a payload it is handed rather than from the store, so a test can hand it
 *  pools (a static render only ever sees a store's initial state). */
export function AccountsBoard({ payload, headRows = [], usage = null, auth = null, keys = null, nowMs, error = null, lastRead = null, sample }: {
  payload: AccountsState | null;
  /** Every head as GET /api/auth reports it: the fallback table, the Claude logins and the keys. */
  headRows?: readonly HeadRow[];
  /** What the heads report for themselves, and their auth cards: the nearest limit reads both for a
   *  head no account row names. */
  usage?: UsagePayload | null;
  auth?: AuthPayload | null;
  /** The key store by name (GET /api/keys): what an opened api-key head's form reads. */
  keys?: KeysPayload | null;
  nowMs: number;
  error?: string | null;
  /** When the pools on screen were read, which the fault prints as stale while `error` stands. */
  lastRead?: number | null;
  /** The fixture's own file name when a fixture fed this board, undefined otherwise. */
  sample?: string | undefined;
}) {
  const { active } = useViews(PAGE_ID, DEFAULT_VIEWS);
  const [openKey, setOpenKey] = useState<string | null>(null);
  const toggle = (key: string) => setOpenKey((current) => (current === key ? null : key));

  const pending = payload !== null && 'pending' in payload;
  const accounts: readonly AccountRow[] = payload === null || pending ? [] : payload.accounts;

  // An api-key head has no login to pool or sign in; the key table is its place.
  const pooledHeads = headRows.filter((row) => row.kind !== 'client' && row.kind !== 'api-key');
  const claudeHeads = headRows.filter((row) => row.kind === 'client');
  const keyHeads = headRows.filter((row) => row.kind === 'api-key');

  const opened = accounts.find((account) => openAccountKey(account) === openKey) ?? null;
  const openedHead = headRows.find((row) => openHeadKey(row.head) === openKey) ?? null;
  const openedKey = keyHeads.find((row) => openKeyHeadKey(row.head) === openKey) ?? null;
  const anyHead = opened?.heads[0] ?? pooledHeads[0]?.head ?? claudeHeads[0]?.head ?? null;

  const groups: RowGroup<AccountRow>[] = arrangeAccounts(accounts, active, nowMs).map((group) => ({
    key: group.key === '' ? S.accounts : group.key,
    title: active.group === 'head' && group.key !== S.noHeads ? <HeadMark head={group.key} />
      : active.group === 'provider' ? familyName(group.key)
      : group.key === '' ? S.accounts : group.key,
    count: group.accounts.length,
    rows: group.accounts,
  }));

  // What the one detail panel holds: an account, an api-key head, or a head's own actions.
  const panel: { title: string; status?: ReactNode; body: ReactNode } | null = opened !== null ? {
    title: accountName(opened),
    status: <AccountStateBadge account={opened} nowMs={nowMs} />,
    body: (
      <>
        <AccountFacts account={opened} nowMs={nowMs} />
        {/* Relabel and remove act on a POOL; a single-login head has none. */}
        {opened.label === null ? null : <AccountActions kind={opened.kind} label={opened.label} heads={opened.heads} pinned={opened.pinned === true} />}
        {anyHead === null ? null : <AccountLogin head={anyHead} />}
      </>
    ),
  } : openedKey !== null ? {
    title: openedKey.head,
    body: <ApiKeyDetail row={openedKey} keyState={keys?.keys.find((each) => each.name === openedKey.envVar) ?? null} />,
  }
    : openedHead !== null ? { title: openedHead.head, body: <HeadActions head={openedHead.head} /> }
    : null;

  const headTable = (rows: readonly HeadRow[], label: string) => (
    <DataTable
      columns={headColumns(nowMs)}
      rows={rows}
      rowKey={(row) => row.head}
      label={label}
      onOpen={(row) => toggle(openHeadKey(row.head))}
      openLabel={(row) => `${S.openHead} ${row.head}`}
      selectedKey={openedHead === null ? null : openedHead.head}
      rowTone={(row) => (signInOf(row).state === 'signedOut' ? 'warn' : null)}
    />
  );

  return (
    <div className="myx-ac" {...(import.meta.env.DEV && sample !== undefined ? { 'data-sample': sample } : {})}>
      <PageHeader title={S.title} info={{ text: H.about, label: S.about }} {...(sample === undefined ? {} : { actions: <Badge tone="neutral">{S.sample}</Badge> })}>
        <ViewTabs pageId={PAGE_ID} defaults={DEFAULT_VIEWS} />
      </PageHeader>

      {error === null ? null : <Fault message={error} lastRead={lastRead} />}

      <div className={panel === null ? 'myx-ac-board' : 'myx-ac-board myx-ac-board-open'}>
        <div className="myx-ac-main">
          {payload === null ? <Blank strips={4} /> : pending ? (
            // Until the pooled route exists, the page shows what the daemon reports today per head
            // rather than an empty screen: the per-head auth card answers "which account is this
            // head on".
            <Section title={S.heads} count={pooledHeads.length}>
              <Empty text={S.poolsUnavailable} source={H.poolsUnavailable} />
              {pooledHeads.length === 0 ? null : headTable(pooledHeads, S.heads)}
            </Section>
          ) : accounts.length === 0 ? <Empty text={S.noAccounts} source={H.noAccounts} /> : (
            <>
              <Figures accounts={accounts} usage={usage} auth={auth} nowMs={nowMs} />
              <Section title={S.accounts} count={accounts.length} info={{ text: orderText(), label: S.aboutNext }}>
                <DataTable
                  columns={accountColumns({ fields: columnsOf(active).filter((field) => panel === null || !OPEN_REPEATS.has(field)), grouped: active.group, nowMs, accounts })}
                  groups={groups}
                  rowKey={openAccountKey}
                  label={S.accounts}
                  onOpen={(account) => toggle(openAccountKey(account))}
                  openLabel={(account) => `${S.openAccount} ${account.kind} ${accountName(account)}`}
                  selectedKey={openKey}
                  rowTone={(account) => accountTone(account, nowMs)}
                />
              </Section>
            </>
          )}

          {claudeHeads.length === 0 ? null : (
            <Section title={S.claudeLogins} count={claudeHeads.length} info={{ text: H.claude, label: S.aboutClaude }}>
              {headTable(claudeHeads, S.claudeLogins)}
            </Section>
          )}

          {keyHeads.length === 0 ? null : (
            <Section title={S.apiKeys} count={keyHeads.length}>
              <DataTable
                columns={keyColumns()}
                rows={keyHeads}
                rowKey={(row) => row.head}
                label={S.apiKeys}
                onOpen={(row) => toggle(openKeyHeadKey(row.head))}
                openLabel={(row) => `${S.openHead} ${row.head}`}
                selectedKey={openedKey === null ? null : openedKey.head}
                rowTone={(row) => (row.present ? null : 'warn')}
              />
            </Section>
          )}
        </div>

        {/* Unmounted at rest: no track and no empty panel until an account or a head is opened. */}
        {panel === null ? null : (
          <DetailPanel
            title={panel.title}
            label={S.detail}
            {...(panel.status === undefined ? {} : { status: panel.status })}
            onClose={() => setOpenKey(null)}
            closeLabel={S.close}
          >
            {panel.body}
          </DetailPanel>
        )}
      </div>
    </div>
  );
}

export function AccountsPage() {
  const { search } = useLocation();
  const accountsResource = useAccounts((state) => state);
  const authResource = useAuth((state) => state);
  const usage = useUsage((state) => state.data);
  const keys = useKeys((state) => state.data);

  const fixture = fixtureName(search, import.meta.env.DEV);
  const nowMs = useNow(CLOCK_MS, fixtureNow(fixture));

  useEffect(() => startAccountsPolling(POLL_MS), []);
  useEffect(() => startAuthPolling(POLL_MS), []);
  useEffect(() => startUsagePolling(POLL_MS), []);
  useEffect(() => startKeysPolling(POLL_MS), []);

  const rows = fixtureAccounts(fixture);

  // The claude head is `client`: it appears in the auth card and never in the pool payload.
  const headRows: HeadRow[] = Object.entries(authResource.data ?? {}).map(([head, auth]) => ({
    head,
    kind: auth.kind,
    present: auth.present,
    verdict: auth.verdict,
    masked: auth.account_id_masked ?? null,
    note: auth.refresh_latched ?? null,
    envVar: auth.env_var,
    keyMasked: auth.api_key_masked,
    keyFile: auth.key_file,
  }));

  return (
    <AccountsBoard
      payload={rows === null ? accountsResource.data : { accounts: [...rows] }}
      headRows={headRows}
      usage={rows === null ? usage : null}
      auth={rows === null ? authResource.data : null}
      keys={keys}
      nowMs={nowMs}
      error={accountsResource.error}
      lastRead={rows === null ? accountsResource.lastUpdated : null}
      sample={import.meta.env.DEV && rows !== null && fixture !== null ? fixture : undefined}
    />
  );
}

export default AccountsPage;
