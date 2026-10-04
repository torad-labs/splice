import { failureText } from '../../api/client';
import { useAccounts, useStatus } from '../../api/queries';
import { colourFromRegistry } from '../../lib/model';
import type { AccountRow, ClaudeLoginPlaceId } from '../../types/accounts';
import { Button, Empty, Fault, GroupHead, PageHead } from '../../ui';
import { SignIn } from '../shared/SignIn';
import { AccountBudget } from './AccountBudget';
import { AccountCard, accountIdentity, providerOf } from './AccountCard';
import { FailoverOrder } from './FailoverOrder';
import { A } from './copy';
import './accounts.css';

const PROVIDERS: Readonly<Record<string, string>> = { anthropic: A.claude, claude: A.claude, openai: 'OpenAI', chatgpt: 'OpenAI', grok: 'xAI', xai: 'xAI', kimi: 'Moonshot', muse: 'Muse' };
const CLAUDE_PLACES: readonly ClaudeLoginPlaceId[] = ['claude', 'claude-splice'];

export function AccountsPage() {
  const accounts = useAccounts();
  const status = useStatus();
  const colour = colourFromRegistry(status.data);
  const now = Date.now();
  if (accounts.isPending) return <div className="accounts-page"><PageHead title={A.title} lede={A.reading} /></div>;
  if (accounts.isError) return <div className="accounts-page"><PageHead title={A.title} /><Fault message={failureText(accounts.error)} onRetry={() => void accounts.refetch()} /></div>;
  const providers = new Map<string, AccountRow[]>([['anthropic', []]]);
  for (const row of accounts.data.accounts) {
    const provider = providerOf(row);
    providers.set(provider, [...(providers.get(provider) ?? []), row]);
  }
  return (
    <div className="accounts-page">
      <PageHead title={A.title} lede={A.lede} tools={<Button disabled={accounts.isFetching} onClick={() => void accounts.refetch()}>{A.reload}</Button>} />
      <div className="frame-cols two">
      {[...providers.entries()].map(([provider, rows]) => {
        const claude = provider === 'anthropic' || provider === 'claude';
        const heads = [...new Set([...(claude && status.data?.registry.some(row => row.key === 'claude-splice') ? ['claude-splice'] : []), ...rows.flatMap(row => row.heads)])];
        const addHead = heads[0];
        return <section className="accounts-provider" key={provider}>
          <GroupHead title={PROVIDERS[provider] ?? provider} {...(claude && CLAUDE_PLACES.some(place => !rows.some(row => row.login_place?.id === place)) ? {} : { count: rows.filter(row => row.credential_present).length })}
            {...(claude || addHead === undefined || rows.every(row => row.kind === 'api-key') ? {} : { action: <SignIn head={addHead} purpose="add"><Button small>{A.add}</Button></SignIn> })} />
          <ul className="accounts-grid">
            {claude ? CLAUDE_PLACES.map(place => <AccountCard key={place} place={place} account={rows.find(row => row.login_place?.id === place) ?? null} colour={colour('claude-splice')} now={now} />) : null}
            {rows.filter(row => !claude || row.login_place == null).map(row => <AccountCard key={accountIdentity(row)} account={row} colour={colour(row.heads[0] ?? '')} now={now} />)}
          </ul>
          {!claude && rows.length === 0 ? <Empty title={A.noAccounts} why={A.noAccountsWhy} /> : null}
          {heads.map(head => <div key={head}><FailoverOrder head={head} accounts={rows.filter(row => row.heads.includes(head))} /><AccountBudget head={head} /></div>)}
        </section>;
      })}
      </div>
    </div>
  );
}
