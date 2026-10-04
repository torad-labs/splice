import * as Dialog from '@radix-ui/react-dialog';
import { useState } from 'react';
import type { ReactNode } from 'react';
import { Button } from './Button';
import { Close } from './icons';

/** An act that cannot be undone, asked once in a dialog: the trigger opens it, the act runs on the one press, and a refusal
 *  keeps it open with the reason. */
export function Confirm({
  trigger, title, why, act, cancel, onConfirm,
}: {
  trigger: ReactNode;
  title: string;
  why: string;
  act: string;
  cancel: string;
  /** Resolves when it happened; rejects with a sentence to show. */
  onConfirm: () => Promise<void>;
}) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const change = (next: boolean): void => {
    setOpen(next);
    setProblem(null);
  };
  return (
    <Dialog.Root open={open} onOpenChange={change}>
      <Dialog.Trigger asChild>{trigger}</Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className="scrim" />
        <Dialog.Content className="dialog">
          <div className="dialog-head">
            <Dialog.Title>{title}</Dialog.Title>
            <Dialog.Close asChild><button type="button" className="icon-btn" aria-label={cancel}><Close /></button></Dialog.Close>
          </div>
          <Dialog.Description className="hint">{why}</Dialog.Description>
          <div className="acts-row">
            <Button
              kind="danger"
              disabled={busy}
              onClick={() => {
                setBusy(true);
                setProblem(null);
                onConfirm().then(
                  () => {
                    setBusy(false);
                    setOpen(false);
                  },
                  (err: unknown) => {
                    setBusy(false);
                    setProblem(err instanceof Error ? err.message : String(err));
                  },
                );
              }}
            >
              {act}
            </Button>
            <Button onClick={() => change(false)}>{cancel}</Button>
          </div>
          {problem === null ? null : <p className="hint alert" role="alert">{problem}</p>}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
