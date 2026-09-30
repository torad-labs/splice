import * as Dialog from '@radix-ui/react-dialog';
import { useState } from 'react';
import { failureText } from '../../api/client';
import { isPendingRoute, useRelabelAccount, useRemoveAccount, useSwitchAccount, useUnpinAccount } from '../../api/auth';
import { accountState, exclusionText, isExcluded, isServable, nextRuleOf, refusalText, steppedPast, windowSpan, windowUsedText } from '../../lib/accounts';
import type { AccountRow } from '../../types/accounts';
import { Button, Close } from '../../ui';
import { D } from './copy';

const windowsText = (account: AccountRow): string =>
  account.windows.map((window) => `${windowSpan(window)} ${windowUsedText(window)}`).join(' · ');

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
  const remove = useRemoveAccount();
  const relabel = useRelabelAccount();
  const { note, settle, fail } = useNote();
  const [renaming, setRenaming] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [name, setName] = useState(account.label ?? '');
  const head = account.heads[0] ?? '';
  const label = account.label;
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
  const rule = nextRuleOf(account, pool);
  const canSwitch = pooled && label !== null && account.selected !== true && !isExcluded(account, now) && isServable(account) && head !== '';

  return (
    <li className="account">
      <div className="account-main">
        <b>{label ?? account.plan ?? account.kind}</b>
        <span className="state-word">{state.label}</span>
        {shown.map((mark) => (
          <span key={mark} className="tag">{mark}</span>
        ))}
        <span className="windows-text">{windowsText(account)}</span>
      </div>
      {past !== null ? <p className="hint">{D.steppedPast(label ?? D.thisAccount, D.windowWord(past.window.length_known === false ? null : past.window.seconds, windowSpan(past.window)), past.serving)}</p> : isExcluded(account, now) ? <p className="hint">{exclusionText(account)}</p> : null}
      {rule === null ? null : <p className="hint">{D.nextBecause(D.nextRule[rule])}</p>}
      {refusal === null ? null : <p className="hint alert" role="alert">{refusal}</p>}
      {refusal === null && !account.credential_present ? <p className="hint">{D.noCredential}</p> : null}
      {label === null ? null : (
        <div className="account-acts">
          {canSwitch ? <Button small disabled={pick.isPending} onClick={() => pick.mutate({ head, label }, { onSuccess: settle, onError: fail })}>{D.switch}</Button> : null}
          {account.pinned === true ? <Button small disabled={unpin.isPending} onClick={() => unpin.mutate(head, { onSuccess: settle, onError: fail })}>{D.unpin}</Button> : null}
          <Dialog.Root open={renaming} onOpenChange={setRenaming}>
            <Dialog.Trigger asChild><Button small>{D.relabel}</Button></Dialog.Trigger>
            <Dialog.Portal>
              <Dialog.Overlay className="scrim" />
              <Dialog.Content className="dialog">
                <div className="dialog-head">
                  <Dialog.Title>{D.relabelAsk}</Dialog.Title>
                  <Dialog.Close asChild><button type="button" className="icon-btn" aria-label={D.cancel}><Close /></button></Dialog.Close>
                </div>
                <Dialog.Description className="hint">{label}</Dialog.Description>
                <form
                  onSubmit={(event) => {
                    event.preventDefault();
                    relabel.mutate({ head, label, next: name.trim() }, { onSuccess: (answer) => { settle(answer); setRenaming(false); }, onError: fail });
                  }}
                >
                  <label className="field">
                    <span className="eyebrow">{D.newName}</span>
                    <input className="input" value={name} onChange={(event) => setName(event.target.value)} autoFocus />
                  </label>
                  <Button kind="go" type="submit" disabled={name.trim() === '' || name.trim() === label || relabel.isPending}>{D.save}</Button>
                </form>
              </Dialog.Content>
            </Dialog.Portal>
          </Dialog.Root>
          <Dialog.Root open={removing} onOpenChange={setRemoving}>
            <Dialog.Trigger asChild><Button small kind="danger">{D.remove}</Button></Dialog.Trigger>
            <Dialog.Portal>
              <Dialog.Overlay className="scrim" />
              <Dialog.Content className="dialog">
                <div className="dialog-head">
                  <Dialog.Title>{D.removeAsk}</Dialog.Title>
                  <Dialog.Close asChild><button type="button" className="icon-btn" aria-label={D.cancel}><Close /></button></Dialog.Close>
                </div>
                <Dialog.Description className="hint">{label}: {D.removeWhy}</Dialog.Description>
                <div className="acts-row">
                  <Button kind="go" disabled={remove.isPending} onClick={() => remove.mutate({ head, label }, { onSuccess: (answer) => { settle(answer); setRemoving(false); }, onError: fail })}>{D.remove}</Button>
                  <Button onClick={() => setRemoving(false)}>{D.cancel}</Button>
                </div>
              </Dialog.Content>
            </Dialog.Portal>
          </Dialog.Root>
        </div>
      )}
      {note === null ? null : <p className="hint alert" role="alert">{note}</p>}
    </li>
  );
}
