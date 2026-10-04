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

/** How many SUBSCRIPTIONS a provider's logins hold, which is what a count of accounts means. Two logins of one
 *  Claude subscription are one account spending one pair of limit windows, so counting login rows made this group
 *  say two while its order had one thing to order. A login whose account splice cannot name counts as its own. */
function subscriptions(rows: readonly AccountRow[]): number {
  const signedIn = rows.filter(row => row.credential_present);
  return new Set(signedIn.map((row, index) => row.account?.uuid ?? `row-${index}`)).size;
}

export function AccountsPage() {
  const accounts = useAccounts();
  const status = useStatus();
  const colour = colourFromRegistry(status.data);
  const commandLabel = (head: string): string => status.data?.registry.find(row => row.key === head)?.label ?? head;
  const localCommands = new Set(status.data?.registry.filter(row => row.family === 'local').map(row => row.key));
  const now = Date.now();
  if (accounts.isError) return <div className="accounts-page"><PageHead title={A.title} /><Fault message={failureText(accounts.error)} onRetry={() => void accounts.refetch()} /></div>;
  if (status.isError && status.data === undefined) return <div className="accounts-page"><PageHead title={A.title} /><Fault message={failureText(status.error)} onRetry={() => void status.refetch()} /></div>;
  if (accounts.isPending || status.isPending) return <div className="accounts-page"><PageHead title={A.title} lede={A.reading} /></div>;
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
        const title = claude || heads.length === 0 ? (PROVIDERS[provider] ?? provider) : heads.map(commandLabel).join(' · ');
        return <section className="accounts-provider" key={provider}>
          <GroupHead title={title} {...(claude && CLAUDE_PLACES.some(place => !rows.some(row => row.login_place?.id === place)) ? {} : { count: subscriptions(rows) })}
            {...(addHead === undefined || rows.every(row => row.kind === 'api-key') ? {} : { action: <SignIn head={addHead} purpose="add"><Button small>{A.add}</Button></SignIn> })} />
          <ul className="accounts-grid">
            {claude ? CLAUDE_PLACES.map(place => <AccountCard key={place} place={place} account={rows.find(row => row.login_place?.id === place) ?? null} commandLabels={[commandLabel('claude-splice')]} colour={colour('claude-splice')} now={now} />) : null}
            {rows.filter(row => !claude || row.login_place == null).map(row => <AccountCard key={accountIdentity(row)} account={row} commandLabels={row.heads.map(commandLabel)} localRuntime={row.heads.length > 0 && row.heads.every(head => localCommands.has(head))} colour={colour(row.heads[0] ?? '')} now={now} />)}
          </ul>
          {!claude && rows.length === 0 ? <Empty title={A.noAccounts} why={A.noAccountsWhy} /> : null}
          {heads.map(head => <div key={head}><FailoverOrder head={head} label={commandLabel(head)} accounts={rows.filter(row => row.heads.includes(head))} localRuntime={localCommands.has(head)} /><AccountBudget head={head} label={commandLabel(head)} /></div>)}
        </section>;
      })}
      </div>
    </div>
  );
}
