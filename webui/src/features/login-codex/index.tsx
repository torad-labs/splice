// Codex sign-in: POSTs to spawn the browser OAuth flow server-side. The auth
// page's own 15s poll picks up the result once auth.json is written; this
// button only reports what the POST itself said.
import { useState } from 'react';
import { loginAuth } from '@entities/auth';
import { Btn } from '@shared/ui';

export function LoginCodex() {
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);

  const run = async () => {
    setBusy(true);
    setNote(null);
    try {
      const result = await loginAuth('codex');
      setNote(result.note ?? (result.started ? 'sign-in started' : 'sign-in not started'));
    } catch (err) {
      setNote(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <span className="myx-inline-action">
      <Btn kind="primary" onClick={() => void run()} busy={busy}>sign in with chatgpt</Btn>
      {note ? <span className="myx-inline-note" role="status">{note}</span> : null}
    </span>
  );
}
