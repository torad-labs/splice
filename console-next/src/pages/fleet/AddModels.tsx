import * as Dialog from '@radix-ui/react-dialog';
import { useState } from 'react';
import { failureText } from '../../api/client';
import { useAddModelOffers, useAddModels } from '../../api/usage';
import { fmtTokens } from '../../lib/format';
import { AD } from '../../lib/words-add';
import type { AddModelsAdded } from '../../types/add';
import { Button, Close } from '../../ui';
import { HT } from './copy';

/** The catalogue models a plan does not reach yet, picked and added in one write. The add writes splice.toml and restarts
 *  the daemon, so the button says both. Shown only for a plan the daemon offers models to (an OpenRouter one). */
export function AddModels({ head }: { head: string }) {
  const offers = useAddModelOffers();
  const add = useAddModels();
  const [open, setOpen] = useState(false);
  const [picked, setPicked] = useState<ReadonlySet<string>>(new Set());
  const [added, setAdded] = useState<AddModelsAdded | null>(null);
  const offer = offers.data?.heads.find((entry) => entry.head === head);
  if (offer === undefined) return null;
  const ids = offer.models.filter((model) => picked.has(model.id)).map((model) => model.id);
  const toggle = (id: string): void => setPicked((held) => {
    const next = new Set(held);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    return next;
  });
  const close = (): void => {
    setOpen(false);
    setPicked(new Set());
    setAdded(null);
    add.reset();
  };
  return (
    <Dialog.Root open={open} onOpenChange={(next) => (next ? setOpen(true) : close())}>
      <Dialog.Trigger asChild><Button small>{HT.addModels}</Button></Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className="scrim" />
        <Dialog.Content className="dialog">
          <div className="dialog-head">
            <Dialog.Title>{HT.addModels}</Dialog.Title>
            <Dialog.Close asChild><button type="button" className="icon-btn" aria-label={AD.cancel}><Close /></button></Dialog.Close>
          </div>
          <Dialog.Description className="hint">{HT.addModelsWhy}</Dialog.Description>
          {added === null ? (
            <>
              {offer.models.length === 0 ? <p className="hint">{HT.addModelsNone}</p> : (
                <ul className="models pick" aria-label={HT.addModels}>
                  {offer.models.map((model) => (
                    <li key={model.id}>
                      <label className="pick-row">
                        <input type="checkbox" checked={picked.has(model.id)} onChange={() => toggle(model.id)} aria-label={HT.addModelsPick(model.id)} />
                        <span><b>{model.label}</b> <code className="id">{model.id}</code></span>
                        <small>{fmtTokens(model.context_window)}</small>
                      </label>
                    </li>
                  ))}
                </ul>
              )}
              {ids.length === 0 ? null : (
                <div className="acts-row">
                  <Button kind="go" disabled={add.isPending} onClick={() => add.mutate({ head, ids }, { onSuccess: setAdded })}>{add.isPending ? HT.addModelsAdding : HT.addModelsGo(ids.length)}</Button>
                </div>
              )}
              {add.isError ? <p className="hint alert" role="alert">{HT.addModelsFailed} {failureText(add.error)}</p> : null}
            </>
          ) : (
            <>
              <p className="hint">{HT.addModelsAdded(added.added.join(', '))} {HT.addModelsWritten(added.path)}</p>
              <p className="hint" role="status">
                {added.restart.status === 'draining' ? AD.draining : added.restart.status === 'waiting' ? AD.waiting(added.restart.compactions?.length ?? 0) : AD.restartManually}
              </p>
              {added.restart.error === undefined ? null : <p className="hint alert" role="alert">{added.restart.error}</p>}
            </>
          )}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
