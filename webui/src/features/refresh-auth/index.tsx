// Per-head token refresh: codex exchanges the OAuth refresh_token; claude
// invalidates the local cache so the next read picks up ~/.claude's own
// refresh (plain `claude` owns that token, splice only observes it).
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
      setOutcome(result.refreshed ? 'token refreshed' : 'refresh did not produce a new token');
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
