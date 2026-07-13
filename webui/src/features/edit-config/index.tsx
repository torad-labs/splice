// Config editing with the reference's diff-modal + confirm pattern: the PATCH
// is shown as old value to new value per key, restart-required keys flagged,
// nothing applies until confirmed.
import { useState } from 'react';
import { applyConfigPatch, diffPatch, parseConfigInput, useConfig } from '@entities/config';
import type { DiffEntry } from '@entities/config';
import type { ConfigValue } from '@shared/api';
import { Btn, ErrorNote, Field, StatusPill } from '@shared/ui';

function fmtVal(v: ConfigValue | undefined): string {
  if (v === null || v === undefined) return 'null';
  return String(v);
}

export function EditConfig() {
  const data = useConfig((s) => s.data);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [pending, setPending] = useState<DiffEntry[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastResult, setLastResult] = useState<string | null>(null);

  if (!data) return null;
  const { effective, restart_required_keys: restartKeys } = data;

  const buildPatch = (): Record<string, ConfigValue> => {
    const patch: Record<string, ConfigValue> = {};
    for (const [key, raw] of Object.entries(drafts)) {
      if (raw === fmtVal(effective[key])) continue;
      patch[key] = parseConfigInput(raw, effective[key] ?? null);
    }
    return patch;
  };

  const review = () => {
    setError(null);
    const entries = diffPatch(effective, buildPatch(), restartKeys);
    if (!entries.length) {
      setLastResult('no changes to apply');
      return;
    }
    setPending(entries);
  };

  const confirm = async () => {
    if (!pending) return;
    setBusy(true);
    setError(null);
    try {
      const patch: Record<string, ConfigValue> = {};
      for (const entry of pending) patch[entry.key] = entry.to;
      const result = await applyConfigPatch(patch);
      const applied = Object.keys(result.applied).join(', ') || 'nothing';
      const restart = result.restart_required.length
        ? `; restart required for ${result.restart_required.join(', ')}`
        : '';
      setLastResult(`applied ${applied}${restart}`);
      setDrafts({});
      setPending(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="myx-editcfg">
      <div className="myx-editcfg-grid">
        {Object.entries(effective).map(([key, value]) => (
          <Field key={key} label={restartKeys.includes(key) ? `${key} (restart)` : key} htmlFor={`cfg-${key}`}>
            <input
              id={`cfg-${key}`}
              value={drafts[key] ?? fmtVal(value)}
              onChange={(e) => setDrafts((d) => ({ ...d, [key]: e.target.value }))}
              spellCheck={false}
            />
          </Field>
        ))}
      </div>
      <div className="myx-editcfg-actions">
        <Btn kind="primary" onClick={review}>review changes</Btn>
        {lastResult ? <span className="myx-editcfg-result">{lastResult}</span> : null}
      </div>
      {error ? <ErrorNote message={error} /> : null}

      {pending ? (
        <div className="myx-modal-scrim" role="dialog" aria-modal="true" aria-label="confirm config changes">
          <div className="myx-modal">
            <h3 className="myx-modal-title">confirm {pending.length} change{pending.length === 1 ? '' : 's'}</h3>
            <table className="myx-table">
              <thead>
                <tr><th>key</th><th>current</th><th>new</th><th>scope</th></tr>
              </thead>
              <tbody>
                {pending.map((entry) => (
                  <tr key={entry.key}>
                    <td>{entry.key}</td>
                    <td className="myx-ink-mute">{fmtVal(entry.from)}</td>
                    <td className="myx-ink-strong">{fmtVal(entry.to)}</td>
                    <td>
                      {entry.restartRequired
                        ? <StatusPill tone="amber">restart</StatusPill>
                        : <StatusPill tone="pos">hot</StatusPill>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="myx-modal-actions">
              <Btn onClick={() => setPending(null)} disabled={busy}>cancel</Btn>
              <Btn kind="primary" onClick={() => void confirm()} busy={busy}>apply</Btn>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}
