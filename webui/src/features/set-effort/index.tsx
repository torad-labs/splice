// Effort/summary FALLBACK controls (hot). These sit at the bottom of the v27
// precedence chain: explicit body effort, then the Claude /effort picker
// (thinking budget), then THIS, then high.
import { useState } from 'react';
import { applyConfigPatch, useConfig } from '@entities/config';
import { ErrorNote, Field } from '@shared/ui';

const EFFORTS = ['null', 'low', 'medium', 'high', 'xhigh', 'max'] as const;
const SUMMARIES = ['null', 'detailed', 'concise', 'auto', 'none'] as const;

export function SetEffort() {
  const effective = useConfig((s) => s.data?.effective);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  if (!effective) return null;

  const apply = async (key: 'effort' | 'summary', raw: string) => {
    setBusy(true);
    setError(null);
    try {
      await applyConfigPatch({ [key]: raw === 'null' ? null : raw });
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="myx-control-row">
      <Field label="effort fallback" htmlFor="effort-fallback">
        <select
          id="effort-fallback"
          value={String(effective.effort ?? 'null')}
          disabled={busy}
          onChange={(e) => void apply('effort', e.target.value)}
        >
          {EFFORTS.map((v) => <option key={v} value={v}>{v === 'null' ? 'unset (picker or high)' : v}</option>)}
        </select>
      </Field>
      <Field label="summary fallback" htmlFor="summary-fallback">
        <select
          id="summary-fallback"
          value={String(effective.summary ?? 'null')}
          disabled={busy}
          onChange={(e) => void apply('summary', e.target.value)}
        >
          {SUMMARIES.map((v) => <option key={v} value={v}>{v === 'null' ? 'unset (detailed)' : v}</option>)}
        </select>
      </Field>
      {error ? <ErrorNote message={error} /> : null}
    </div>
  );
}
