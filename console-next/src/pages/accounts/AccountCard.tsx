import { useMutation, useQueryClient } from '@tanstack/react-query';
import { refreshClaudeLogin, refreshLogin } from '../../api/auth';
import { failureText } from '../../api/client';
import { keys } from '../../api/queries';
import { awaitRefetch } from '../../api/refetch';
import { familyName, localInstantText } from '../../lib/heads';
import type { AccountRow, ClaudeLoginPlaceId } from '../../types/accounts';
import type { ModelColour } from '../../lib/model';
import { Button, State, Window } from '../../ui';
import { SignIn } from '../shared/SignIn';
import { AccountLimits } from './AccountLimits';
import { A } from './copy';

export function accountIdentity(row: AccountRow): string {
  return row.login_place?.id ?? row.credential_path ?? `${row.kind}:${row.label ?? row.heads.join(',')}`;
}

export function AccountCard({ account, place, colour, now }: {
  account: AccountRow | null; place?: ClaudeLoginPlaceId; colour: ModelColour; now: number;
}) {
  const client = useQueryClient();
  const head = place === undefined ? account?.heads[0] ?? '' : 'claude-splice';
  const present = account?.credential_present;
  const name = account?.account?.email ?? account?.label ?? (place ?? A.primary);
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
  const state = refused ? A.refused : excluded ? A.excluded : present === undefined ? A.unknownLogin : !present ? A.notSignedIn : held ? A.limitReached : A.signedIn;
  return (
    <Window as="li" colour={colour} className="card account-card" attention={refused || excluded || held || present === false}>
      <div className="bar"><h3>{name}</h3><State tone={refused || excluded ? 'stuck' : held ? 'quota' : present ? 'work' : present === false ? 'stuck' : 'wait'}>{state}</State></div>
      <div className="account-place">
        {place === undefined ? <p>{account?.heads.join(' · ')}{account?.plan ? ` · ${account.plan}` : ''}</p> : <><b>{place}</b><p>{place === 'claude' ? A.nativeWhy : A.spliceWhy}</p></>}
      </div>
      <div className="glass one"><AccountLimits windows={account?.windows ?? []} now={now} /></div>
      <div className="quiet-meta">
        {account?.next_target === true ? <span className="tag">{A.next}</span> : null}
        {account?.selected === true ? <span>{A.serving}</span> : null}
        {account?.pinned === true ? <span>{A.pinned}</span> : null}
        {account?.held_until_epoch_seconds == null ? null : <span>{A.heldUntil} {localInstantText(account.held_until_epoch_seconds, 'America/Chicago')} {A.ct}</span>}
      </div>
      {account?.refusal || account?.auth_exclusion_reason ? <p className="quiet-line alert">{account.refusal ?? account.auth_exclusion_reason}</p> : null}
      <div className="account-acts">
        <SignIn head={head} {...(place === undefined ? {} : { place })} label={account?.label ?? ''} purpose={renewing ? 'renew' : 'add'}>
          <Button small disabled={refused} kind={present ? 'quiet' : 'go'}>{renewing ? A.renew : A.signIn}</Button>
        </SignIn>
        {present ? <Button small disabled={refresh.isPending} onClick={() => refresh.mutate()}>{refresh.isPending ? A.refreshing : A.refresh}</Button> : null}
        {refresh.isError ? <span className="hint alert" role="alert">{failureText(refresh.error)}</span> : null}
      </div>
    </Window>
  );
}

export function providerOf(row: AccountRow): string {
  const provider = row.login_place != null ? 'anthropic' : row.provider ?? familyName(row.kind);
  const canonical: Readonly<Record<string, string>> = { claude: 'anthropic', anthropic: 'anthropic', chatgpt: 'openai', openai: 'openai', grok: 'xai', xai: 'xai' };
  return canonical[provider.toLowerCase()] ?? provider;
}
