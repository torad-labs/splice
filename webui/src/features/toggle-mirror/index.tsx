// Mirror mode control — hot-applies showReasoning via /mgmt/config.
// text = thinking UI + the visible [reasoning summary] mirror (the mythos loop)
// thinking = thinking UI only; off = neither.
import { useState } from 'react';
import { applyConfigPatch, useConfig } from '@entities/config';
import { ErrorNote, Field } from '@shared/ui';

const MODES = ['text', 'thinking', 'off'] as const;

export function ToggleMirror() {
  const effective = useConfig((s) => s.data?.effective);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  if (!effective) return null;
  const current = String(effective.showReasoning ?? 'text');

  const apply = async (mode: string) => {
    setBusy(true);
    setError(null);
    try {
      await applyConfigPatch({ showReasoning: mode });
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="myx-control-row">
      <Field label="mirror mode" htmlFor="mirror-mode">
        <select
          id="mirror-mode"
          value={current}
          disabled={busy}
          onChange={(e) => void apply(e.target.value)}
        >
          {MODES.map((m) => <option key={m} value={m}>{m}</option>)}
        </select>
      </Field>
      {error ? <ErrorNote message={error} /> : null}
    </div>
  );
}
