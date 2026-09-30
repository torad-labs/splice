import * as Dialog from '@radix-ui/react-dialog';
import { useState } from 'react';
import type { ReactNode } from 'react';
import { Button } from './Button';
import { Close } from './icons';

/** One text answer asked in a dialog: the trigger opens it, the submit hands the text over, and a rejection keeps it open with
 *  the reason. `secret` masks the field and forgets the text the moment the dialog closes. */
export function Prompt({
  trigger, title, why, field, submit, cancel, secret = false, initial = '', onSubmit,
}: {
  trigger: ReactNode;
  title: string;
  why?: string;
  field: string;
  submit: string;
  cancel: string;
  secret?: boolean;
  initial?: string;
  /** Resolves when the answer was taken; rejects with a sentence to show. */
  onSubmit: (text: string) => Promise<void>;
}) {
  const [open, setOpen] = useState(false);
  const [text, setText] = useState(initial);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const change = (next: boolean): void => {
    setOpen(next);
    setText(initial);
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
          {why === undefined ? <Dialog.Description className="sr-only">{title}</Dialog.Description> : <Dialog.Description className="hint">{why}</Dialog.Description>}
          <form
            autoComplete="off"
            onSubmit={(event) => {
              event.preventDefault();
              setBusy(true);
              setProblem(null);
              onSubmit(text).then(
                () => {
                  setBusy(false);
                  change(false);
                },
                (err: unknown) => {
                  setBusy(false);
                  setProblem(err instanceof Error ? err.message : String(err));
                },
              );
            }}
          >
            <label className="field">
              <span className="eyebrow">{field}</span>
              <input className="input" type={secret ? 'password' : 'text'} value={text} onChange={(event) => setText(event.target.value)} autoFocus spellCheck={false} />
            </label>
            {problem === null ? null : <p className="hint alert" role="alert">{problem}</p>}
            <Button kind="go" type="submit" disabled={text.trim() === '' || busy}>{submit}</Button>
          </form>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
