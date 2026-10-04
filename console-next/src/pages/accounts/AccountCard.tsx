import { useMutation, useQueryClient } from '@tanstack/react-query';
import { refreshClaudeLogin, refreshLogin } from '../../api/auth';
import { failureText } from '../../api/client';
import { keys } from '../../api/queries';
import { awaitRefetch } from '../../api/refetch';
import { familyName } from '../../lib/heads';
import type { AccountRow, ClaudeLoginPlaceId } from '../../types/accounts';
import type { ModelColour } from '../../lib/model';
import { Button, State, Window } from '../../ui';
import { RemoveAccount } from '../shared/RemoveAccount';
import { SignIn } from '../shared/SignIn';
import { AccountLimits } from './AccountLimits';
import { R } from '../../lib/words-remove';
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
  const keyed = account?.kind === 'api-key';
  const name = keyed ? account.heads.join(' · ') : account?.account?.email ?? account?.label ?? (place ?? A.primary);
  const held = account?.held === true;
  const renewing = present === true || (place === undefined && account?.label != null);
  const refused = Boolean(account?.refusal);
  const excluded = account?.auth_excluded_until_epoch_millis != null && account.auth_excluded_until_epoch_millis > now;
  // An added account can be taken off the command it was added to. The primary login cannot: for a Claude command
  // that is the caller's own Claude Code sign-in, which splice forwards and has never held, and for every other
  // provider it is the login the pool is built from.
  const removable = place === undefined && account?.label != null && account.primary !== true && head !== '';
  const refresh = useMutation({
    mutationFn: async () => {
      if (place !== undefined) { await refreshClaudeLogin(place); return; }
      const answer = await refreshLogin(head);
      if (answer.action === 'refresh' && !answer.result.ok) throw new Error(answer.result.note ?? A.notSignedIn);
    },
    onSuccess: () => awaitRefetch(client, [keys.accounts, keys.auth, keys.heads, keys.usage]),
  });
  const state = refused ? A.refused : excluded ? A.excluded : present === undefined ? A.unknownLogin : keyed ? present ? A.keyReady : A.keyMissing : !present ? A.notSignedIn : held ? A.limitReached : A.signedIn;
  return (
    <Window as="li" colour={colour} className="card account-card" attention={refused || excluded || held || present === false}>
      <div className="bar"><h3>{name}</h3><State tone={refused || excluded ? 'stuck' : held ? 'quota' : present ? 'work' : present === false ? 'stuck' : 'wait'}>{state}</State></div>
      <div className="account-place">
        {place === undefined ? <p>{account?.heads.join(' · ')}{account?.plan ? ` · ${account.plan}` : ''}</p> : <><b>{place}</b><p>{place === 'claude' ? A.nativeWhy : A.spliceWhy}</p></>}
      </div>
      {keyed ? <p className="hint">{A.keyWhy}</p> : <div className="glass one"><AccountLimits windows={account?.windows ?? []} now={now} {...(account === null ? {} : { kind: account.kind })} /></div>}
      <div className="quiet-meta">
        {account?.next_target === true ? <span className="tag">{A.next}</span> : null}
        {account?.selected === true ? <span>{A.serving}</span> : null}
        {account?.pinned === true ? <span>{A.pinned}</span> : null}
        {account?.held_until_epoch_seconds == null ? null : <span>{A.heldUntil} {A.instant(account.held_until_epoch_seconds)}</span>}
      </div>
      {account?.refusal || account?.auth_exclusion_reason ? <p className="quiet-line alert">{account.refusal ?? account.auth_exclusion_reason}</p> : null}
      {present === undefined ? <p className="hint">{A.unknownLoginWhy}</p> : null}
      {keyed ? null : <div className="account-acts">
        <SignIn head={head} {...(place === undefined ? {} : { place })} label={account?.label ?? ''} purpose={renewing ? 'renew' : 'add'}>
          <Button small disabled={refused} kind={present ? 'quiet' : 'go'}>{renewing ? A.renew : A.signIn}</Button>
        </SignIn>
        {present ? <Button small disabled={refresh.isPending} onClick={() => refresh.mutate()}>{refresh.isPending ? A.refreshing : A.refresh}</Button> : null}
        {removable && account?.label != null ? <RemoveAccount head={head} label={account.label}><Button small kind="danger">{R.remove}</Button></RemoveAccount> : null}
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
