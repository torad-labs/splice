import { useState } from 'react';
import { refreshAuth } from '@entities/auth';
import { Btn } from '@shared/ui';

export function RefreshAuth() {
  const [busy, setBusy] = useState(false);
  const [outcome, setOutcome] = useState<string | null>(null);

  const run = async () => {
    setBusy(true);
    setOutcome(null);
    const refreshed = await refreshAuth();
    setOutcome(refreshed ? 'token refreshed' : 'refresh did not produce a new token');
    setBusy(false);
  };

  return (
    <span className="myx-inline-action">
      <Btn onClick={() => void run()} busy={busy}>refresh token</Btn>
      {outcome ? <span className="myx-inline-note" role="status">{outcome}</span> : null}
    </span>
  );
}
