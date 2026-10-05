import * as Dialog from '@radix-ui/react-dialog';
import { useState } from 'react';
import type { ReactNode } from 'react';
import type { AccountEditTarget } from '../../types/accounts';
import { isPendingRoute, useRemoveAccount } from '../../api/auth';
import { failureText } from '../../api/client';
import { R } from '../../lib/words-remove';
import { Button, Close } from '../../ui';

/** Confirm the displayed login, remove its explicit credential location, and retain any daemon refusal. */
export function RemoveAccount({ head, target, name, children }: { head: string; target: AccountEditTarget; name: string; children: ReactNode }) {
  const [open, setOpen] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const remove = useRemoveAccount();
  const act = (): void => {
    setNote(null);
    remove.mutate({ head, target }, {
      onSuccess: (answer) => {
        if (isPendingRoute(answer)) setNote(R.unsupported);
        else setOpen(false);
      },
      onError: (error) => setNote(failureText(error)),
    });
  };
  return (
    <Dialog.Root
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) setNote(null);
      }}
    >
      <Dialog.Trigger asChild>{children}</Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className="scrim" />
        <Dialog.Content className="dialog">
          <div className="dialog-head">
            <Dialog.Title>{R.ask}</Dialog.Title>
            <Dialog.Close asChild>
              <button type="button" className="icon-btn" aria-label={R.cancel}><Close /></button>
            </Dialog.Close>
          </div>
          <Dialog.Description className="hint">{name}: {R.why}</Dialog.Description>
          {note === null ? null : <p className="hint alert" role="alert">{note}</p>}
          <div className="acts-row">
            <Button kind="go" disabled={remove.isPending} onClick={act}>{remove.isPending ? R.removing : R.remove}</Button>
            <Button onClick={() => setOpen(false)}>{R.cancel}</Button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
