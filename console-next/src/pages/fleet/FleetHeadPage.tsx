import { Link, useParams, useSearchParams } from 'react-router';
import { useKeyStore } from '../../api/auth';
import { useAccounts, useAuth, useHealth, useHeads, useSessions, useUsage } from '../../api/queries';
import { SELECTOR_ORDER_TEXT, canRefresh, poolOf } from '../../lib/accounts';
import { fleetCard } from '../../lib/fleet';
import { localInstantText } from '../../lib/heads';
import { planWindows } from '../../lib/usage';
import { Back, Button, Empty, Fault, PageHead, Plus, Segmented, State, Window } from '../../ui';
import { failureText } from '../../api/client';
import { SignIn } from '../shared/SignIn';
import { AccountRowView } from './AccountRows';
import { D } from './copy';
import { LogTab } from './LogTab';
import { ModelsTab } from './ModelsTab';
import { FleetFix } from './FleetFix';
import { HeadKey } from './HeadKey';
import { RefreshSignIn } from './RefreshSignIn';
import { WindowBars } from './WindowBars';
import './head.css';

type Tab = 'windows' | 'models' | 'log';
const TABS: readonly (readonly [Tab, string])[] = [['windows', D.windows], ['models', D.models], ['log', D.log]];
const tabOf = (value: string | null): Tab => TABS.find(([id]) => id === value)?.[0] ?? 'windows';

const tailOf = (value: string | null): number | null => {
  const size = value === null ? NaN : Number(value);
  return Number.isSafeInteger(size) && size >= 10 && size <= 2000 ? size : null;
};

const OAUTH = new Set(['chatgpt-oauth', 'grok-oauth', 'kimi-oauth', 'muse-oauth']);

/** One plan's own page: every window it reports, the accounts of its pool and what a person does to them. */
export function FleetHeadPage() {
  const { head: key = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const tab = tabOf(params.get('tab'));
  const heads = useHeads();
  const usage = useUsage();
  const auth = useAuth();
  const accounts = useAccounts();
  const sessions = useSessions();
  const health = useHealth();
  const keyStore = useKeyStore();
  const now = Date.now();
  const back = (
    <Link className="crumb" to="/fleet">
      <Back />
      {D.back}
    </Link>
  );

  if (heads.isPending) return <>{back}<PageHead title={key} lede={D.readingHead} /></>;
  if (heads.isError) return <>{back}<Fault message={failureText(heads.error)} onRetry={() => void heads.refetch()} /></>;
  const head = heads.data.heads.find((candidate) => candidate.key === key);
  if (head === undefined) return <>{back}<Empty title={D.notFound} /></>;

  const rows = accounts.data?.accounts ?? [];
  const pool = poolOf(rows, head.key);
  const live = new Map<string, number>();
  for (const row of sessions.data?.sessions ?? []) if (row.availability !== 'gone') live.set(row.head, (live.get(row.head) ?? 0) + 1);
  const facts = fleetCard(head, { usage: usage.data ?? null, auth: auth.data ?? null, accounts: rows, sessions: live, topologyStale: health.data?.topologyStale === true, keys: keyStore.data ?? null, now });
  const windows = planWindows(usage.data?.heads.find((row) => row.key === head.key)?.usage ?? null, now);

  return (
    <>
      {back}
      <header className="top">
        <div>
          <h1>{head.label}</h1>
          <div className="facts quiet-meta">
            <State tone={facts.tone}>{facts.state}</State>
            {facts.meta.map((part) => (
              <span key={part}>{part}</span>
            ))}
          </div>
        </div>
        {facts.fix === null ? null : <div className="acts"><FleetFix fix={facts.fix} head={head} pool={pool} now={now} keyCommand={facts.keyCommand ?? null} /></div>}
      </header>
      <Window as="section" colour={facts.colour} attention={facts.attention} className="sheet head-sheet" aria-label={D.windowsTab}>
        <div className="bar">
          <h3>{tab === 'windows' ? D.windowsTab : tab === 'models' ? D.models : D.log}</h3>
          <Segmented label={D.tabsLabel} value={tab} options={TABS} onChange={(next) => setParams(next === 'windows' ? {} : { tab: next }, { replace: true })} />
        </div>
        <div className="head-body">
          {tab === 'models' ? <ModelsTab head={head.key} /> : tab === 'log' ? <LogTab head={head.key} initialTail={tailOf(params.get('tail'))} /> : (
            <>
              {windows.length === 0 ? <p className="hint">{facts.none ?? D.noWindows}</p> : <WindowBars windows={windows} now={now} format={localInstantText} />}
              <h2 className="sub-head">{D.accounts}</h2>
              {pool.length === 0 ? <p className="hint">{D.noAccounts}</p> : (
                <>
                {pool.length > 1 ? <p className="hint">{D.selectorOrder(SELECTOR_ORDER_TEXT)}</p> : null}
                <ul className="accounts">
                  {pool.map((account) => (
                    <AccountRowView key={account.credential_path ?? account.label ?? account.kind} account={account} now={now} pooled={pool.length > 1 || account.single_login === false} pool={pool} />
                  ))}
                </ul>
                </>
              )}
              {OAUTH.has(head.authKind) ? (
                <SignIn head={head.key} purpose="add">
                  <Button small><Plus />{D.addAccount}</Button>
                </SignIn>
              ) : null}
              {OAUTH.has(head.authKind) && canRefresh(pool) ? <RefreshSignIn head={head.key} /> : null}
              {head.authKind === 'api-key' ? <HeadKey head={head.key} /> : null}
            </>
          )}
        </div>
      </Window>
    </>
  );
}
