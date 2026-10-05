import { useMutation, useQueryClient } from '@tanstack/react-query';
import { refreshClaudeLogin, refreshLogin } from '../../api/auth';
import { failureText } from '../../api/client';
import { keys } from '../../api/queries';
import { awaitRefetch } from '../../api/refetch';
import { familyName } from '../../lib/heads';
import { accountEmail, accountName } from '../../lib/accounts';
import type { AccountRow, ClaudeLoginPlaceId } from '../../types/accounts';
import type { ModelColour } from '../../lib/model';
import { Button, State, Window } from '../../ui';
import { AccountEdits } from '../shared/AccountEdits';
import { SignIn } from '../shared/SignIn';
import { AccountLimits } from './AccountLimits';
import { A } from './copy';

export { accountIdentity } from '../../lib/accounts';

export function AccountCard({ account, place, colour, now, commandLabels, localRuntime = false }: {
  account: AccountRow | null; place?: ClaudeLoginPlaceId; colour: ModelColour; now: number; commandLabels?: readonly string[]; localRuntime?: boolean;
}) {
  const client = useQueryClient();
  const head = place === undefined ? account?.heads[0] ?? '' : 'claude-splice';
  const present = account?.credential_present;
  const keyed = account?.kind === 'api-key';
  const commands = (commandLabels ?? account?.heads ?? []).join(' · ');
  const name = keyed || localRuntime ? commands : account === null ? place ?? A.primary : accountName(account);
  const email = account === null ? null : accountEmail(account);
  const held = account?.held === true;
  const renewing = present === true || (place === undefined && account?.label != null);
  const refused = Boolean(account?.refusal);
  const excluded = account?.auth_excluded_until_epoch_millis != null && account.auth_excluded_until_epoch_millis > now;
  const refresh = useMutation({
    mutationFn: async () => {
      if (place !== undefined) { await refreshClaudeLogin(place); return; }
      const answer = await refreshLogin(head);
      if (answer.action === 'refresh' && !answer.result.ok) throw new Error(answer.result.note ?? A.notSignedIn);
    },
    onSuccess: () => awaitRefetch(client, [keys.accounts, keys.auth, keys.heads, keys.usage]),
  });
  const state = localRuntime ? A.localState : refused ? A.refused : excluded ? A.excluded : present === undefined ? A.unknownLogin : keyed ? present ? A.keyReady : A.keyMissing : !present ? A.notSignedIn : held ? A.limitReached : A.signedIn;
  return (
    <Window as="li" colour={colour} className="card account-card" attention={!localRuntime && (refused || excluded || held || present === false)}>
      <div className="bar"><h3>{name}</h3><State tone={localRuntime ? 'idle' : refused || excluded ? 'stuck' : held ? 'quota' : present ? 'work' : present === false ? 'stuck' : 'wait'}>{state}</State></div>
      {email === null ? null : <p className="quiet-line">{email}</p>}
      <div className="account-place">
        {place === undefined ? <p>{commands}{account?.plan ? ` · ${account.plan}` : ''}</p> : <><b>{place}</b><p>{place === 'claude' ? A.nativeWhy : A.spliceWhy}</p></>}
      </div>
      {localRuntime ? <p className="hint">{A.localWhy}</p> : keyed ? <p className="hint">{A.keyWhy}</p> : <div className="glass one"><AccountLimits windows={account?.windows ?? []} now={now} {...(account === null ? {} : { kind: account.kind })} /></div>}
      <div className="quiet-meta">
        {account?.next_target === true ? <span className="tag">{A.next}</span> : null}
        {account?.selected === true ? <span>{A.serving}</span> : null}
        {account?.pinned === true ? <span>{A.pinned}</span> : null}
        {account?.held_until_epoch_seconds == null ? null : <span>{A.heldUntil} {A.instant(account.held_until_epoch_seconds)}</span>}
      </div>
      {account?.refusal || account?.auth_exclusion_reason ? <p className="quiet-line alert">{account.refusal ?? account.auth_exclusion_reason}</p> : null}
      {!localRuntime && present === undefined ? <p className="hint">{A.unknownLoginWhy}</p> : null}
      {keyed || localRuntime ? null : <div className="account-acts">
        <SignIn head={head} {...(place === undefined ? {} : { place })} label={account?.label ?? ''} displayName={name} purpose={renewing ? 'renew' : 'add'}>
          <Button small disabled={refused} kind={present ? 'quiet' : 'go'}>{renewing ? A.renew : A.signIn}</Button>
        </SignIn>
        {present ? <Button small disabled={refresh.isPending} onClick={() => refresh.mutate()}>{refresh.isPending ? A.refreshing : A.refresh}</Button> : null}
        {account === null ? null : <AccountEdits account={account} head={head} />}
        {refresh.isError ? <span className="hint alert" role="alert">{failureText(refresh.error)}</span> : null}
      </div>}
    </Window>
  );
}

export function providerOf(row: AccountRow): string {
  const provider = row.login_place != null ? 'anthropic' : row.provider ?? familyName(row.kind);
  const canonical: Readonly<Record<string, string>> = { claude: 'anthropic', anthropic: 'anthropic', chatgpt: 'openai', openai: 'openai', grok: 'xai', xai: 'xai' };
  return canonical[provider.toLowerCase()] ?? provider;
}
