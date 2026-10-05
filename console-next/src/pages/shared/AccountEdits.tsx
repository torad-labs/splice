import * as Dialog from '@radix-ui/react-dialog';
import { useState } from 'react';
import { isPendingRoute, useRelabelAccount } from '../../api/auth';
import { failureText } from '../../api/client';
import { accountName } from '../../lib/accounts';
import { R } from '../../lib/words-remove';
import type { AccountEditTarget, AccountRow } from '../../types/accounts';
import { Button, Close } from '../../ui';
import { D } from '../fleet/copy';
import { RemoveAccount } from './RemoveAccount';

function RenameAccount({ head, target, current }: { head: string; target: AccountEditTarget; current: string }) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState(current);
  const [note, setNote] = useState<string | null>(null);
  const rename = useRelabelAccount();
  return (
    <Dialog.Root open={open} onOpenChange={next => { setOpen(next); if (next) { setName(current); setNote(null); } }}>
      <Dialog.Trigger asChild><Button small>{D.relabel}</Button></Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className="scrim" />
        <Dialog.Content className="dialog">
          <div className="dialog-head">
            <Dialog.Title>{D.relabelAsk}</Dialog.Title>
            <Dialog.Close asChild><button type="button" className="icon-btn" aria-label={D.cancel}><Close /></button></Dialog.Close>
          </div>
          <Dialog.Description className="hint">{current}</Dialog.Description>
          <form onSubmit={event => {
            event.preventDefault();
            setNote(null);
            rename.mutate({ head, target, next: name.trim() }, {
              onSuccess: answer => { if (isPendingRoute(answer)) setNote(D.unsupported); else setOpen(false); },
              onError: error => setNote(failureText(error)),
            });
          }}>
            <label className="field"><span className="eyebrow">{D.newName}</span><input className="input" value={name} onChange={event => setName(event.target.value)} autoFocus /></label>
            {note === null ? null : <p className="hint alert" role="alert">{note}</p>}
            <Button kind="go" type="submit" disabled={name.trim() === '' || name.trim() === current || rename.isPending}>{D.save}</Button>
          </form>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

/** The same capability-gated acts wherever this login appears. Names never address credentials. */
export function AccountEdits({ account, head = account.heads[0] ?? '' }: { account: AccountRow; head?: string }) {
  const target = account.edit_target;
  if (target == null || head === '') return null;
  const name = accountName(account);
  return <>
    {account.can_rename === true ? <RenameAccount head={head} target={target} current={name} /> : null}
    {account.can_remove === true ? <RemoveAccount head={head} target={target} name={name}><Button small kind="danger">{R.remove}</Button></RemoveAccount> : null}
  </>;
}
