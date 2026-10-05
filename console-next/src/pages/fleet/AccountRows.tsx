import { useState } from 'react';
import { failureText } from '../../api/client';
import { useAccountOrder } from '../../api/account-order';
import { isPendingRoute, useSwitchAccount, useUnpinAccount } from '../../api/auth';
import { accountEmail, accountName, accountSelector, accountState, exclusionText, isExcluded, isServable, isStale, nextRuleOf, refusalText, steppedPast, windowSpan, windowUsedText } from '../../lib/accounts';
import type { AccountRow } from '../../types/accounts';
import { Button } from '../../ui';
import { AccountEdits } from '../shared/AccountEdits';
import { D } from './copy';
import { A } from '../accounts/copy';
import { Q } from '../../lib/words-quota';

const windowsText = (account: AccountRow, now: number): string =>
  account.windows.map(window => {
    const elapsed = isStale(window, now);
    const old = window.current === false;
    const words = `${windowSpan(window)} ${elapsed || old ? A.unreported : windowUsedText(window)}`;
    if (!elapsed && !old) return words;
    const observed = Q.observed(window.observed_at_epoch_seconds == null ? null : A.instant(window.observed_at_epoch_seconds));
    return `${words} · ${elapsed ? A.reset : A.readingOld} · ${observed}`;
  }).join(' · ');

/** What a write's answer says when the daemon does not serve the route. */
function useNote() {
  const [note, setNote] = useState<string | null>(null);
  const settle = (answer: unknown): void => setNote(isPendingRoute(answer) ? D.unsupported : null);
  return { note, settle, fail: (err: unknown) => setNote(failureText(err)) };
}

/** One account of a plan's pool: its name, where it stands, its windows, and the few things a person does to it. */
export function AccountRowView({ account, now, pooled, pool }: { account: AccountRow; now: number; pooled: boolean; pool: readonly AccountRow[] }) {
  const pick = useSwitchAccount();
  const unpin = useUnpinAccount();
  const { note, settle, fail } = useNote();
  const name = accountName(account);
  const email = accountEmail(account);
  const head = account.heads[0] ?? '';
  const label = accountSelector(account);
  const order = useAccountOrder(head, pooled && head !== '');
  const selectable = order.data !== undefined && !('unavailable' in order.data) && order.data.single_account !== true;
  const state = accountState(account, now, pool);
  const past = steppedPast(account, pool, now);
  const marks = [
    account.selected === true ? D.selected : null,
    account.pinned === true ? D.pinned : null,
    account.next_target === true ? D.next : null,
    account.primary ? D.primary : null,
  ] as (string | null)[];
  const shown = marks.filter((mark): mark is string => mark !== null);
  const refusal = refusalText(account);
  // The next flag proves who wins, not whether saved order or a fallback selected them.
  const rule = account.pinned === true ? nextRuleOf(account, pool) : null;
  const canSwitch = pooled && selectable && label !== null && account.selected !== true && !isExcluded(account, now) && isServable(account) && head !== '';

  return (
    <li className="account">
      <div className="account-main">
        <b>{name}</b>
        {email === null ? null : <span className="hint">{email}</span>}
        <span className="state-word">{state.label}</span>
        {shown.map((mark) => (
          <span key={mark} className="tag">{mark}</span>
        ))}
        <span className="windows-text">{windowsText(account, now)}</span>
      </div>
      {past !== null ? <p className="hint">{D.steppedPast(name, D.windowWord(past.window.length_known === false ? null : past.window.seconds, windowSpan(past.window)), past.serving)}</p> : isExcluded(account, now) ? <p className="hint">{exclusionText(account)}</p> : null}
      {rule === null ? null : <p className="hint">{D.nextBecause(D.nextRule[rule])}</p>}
      {refusal === null ? null : <p className="hint alert" role="alert">{refusal}</p>}
      {refusal === null && !account.credential_present ? <p className="hint">{D.noCredential}</p> : null}
      <div className="account-acts">
        {canSwitch && label !== null ? <Button small disabled={pick.isPending} onClick={() => pick.mutate({ head, label }, { onSuccess: settle, onError: fail })}>{D.switch}</Button> : null}
        {account.pinned === true ? <Button small disabled={unpin.isPending} onClick={() => unpin.mutate(head, { onSuccess: settle, onError: fail })}>{D.unpin}</Button> : null}
        <AccountEdits account={account} head={head} />
      </div>
      {note === null ? null : <p className="hint alert" role="alert">{note}</p>}
    </li>
  );
}
