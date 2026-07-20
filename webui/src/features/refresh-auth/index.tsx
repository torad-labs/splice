// Token refresh for the codex head: exchanges the OAuth refresh_token.
import { useState } from 'react';
import { refreshAuth } from '@entities/auth';
import { Btn } from '@shared/ui';

export function RefreshAuth({ head }: { head: string }) {
  const [busy, setBusy] = useState(false);
  const [outcome, setOutcome] = useState<string | null>(null);

  const run = async () => {
    setBusy(true);
    setOutcome(null);
    try {
      const result = await refreshAuth(head);
      setOutcome(result.ok ? 'token refreshed' : (result.note ?? 'refresh did not produce a new token'));
    } catch (err) {
      setOutcome(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <span className="myx-inline-action">
      <Btn onClick={() => void run()} busy={busy}>refresh</Btn>
      {outcome ? <span className="myx-inline-note" role="status">{outcome}</span> : null}
    </span>
  );
}
