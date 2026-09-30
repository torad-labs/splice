import { useEffect, useState } from 'react';
import { failureText } from '../../api/client';
import { isPendingRoute } from '../../api/auth';
import { usePreviewInstructionFile, useTopology, useTopologyEdit } from '../../api/config';
import { useHeads } from '../../api/queries';
import { canSave, commandInstructionsOf, noteOf, topologyKeysOf, withCommandInstructions } from '../../lib/command-instructions';
import type { CommandInstruction, InstructionMode, InstructionSource } from '../../lib/command-instructions';
import { I } from '../../lib/words-instructions';
import { Button, Segmented, Select } from '../../ui';
import { Row } from './Row';

/** The file a plan's instructions come from, read by the daemon without saving anything. A short wait after typing, and each new
 *  path replaces the last answer, so what shows is what the path now names. */
function useFilePreview(head: string, current: CommandInstruction | null) {
  const preview = usePreviewInstructionFile();
  const { reset, mutate } = preview;
  const path = current?.source === 'file' ? current.text.trim() : '';
  const mode = current?.mode ?? 'append';
  useEffect(() => {
    reset();
    if (path === '') return undefined;
    const timer = window.setTimeout(() => mutate({ head, file: path, mode }), 150);
    return () => window.clearTimeout(timer);
  }, [head, path, mode, reset, mutate]);
  return preview;
}

/** A plan's standing instructions: written here or read from a file, and whether they add to, replace or cut from the client's. */
export function PlanInstructions() {
  const heads = useHeads();
  const topology = useTopology();
  const edit = useTopologyEdit();
  const plans = heads.data?.heads ?? [];
  const [picked, setPicked] = useState('');
  const [draft, setDraft] = useState<{ head: string; value: CommandInstruction | null } | null>(null);
  const head = plans.some((plan) => plan.key === picked) ? picked : plans[0]?.key ?? '';
  const payload = topology.data;
  const held = payload === undefined || isPendingRoute(payload) || head === '' ? null : commandInstructionsOf(payload.topology, head);
  const current = draft?.head === head ? draft.value : held;
  const file = useFilePreview(head, current);
  if (topology.isError) return <p className="hint alert row-note" role="alert">{failureText(topology.error)}</p>;
  if (payload === undefined || heads.data === undefined) return null;
  if (isPendingRoute(payload)) return <p className="hint row-note">{I.unavailable}</p>;
  if (head === '') return <p className="hint row-note">{I.noPlans}</p>;
  const set = (value: CommandInstruction | null): void => setDraft({ head, value });
  const changed = draft?.head === head && JSON.stringify(draft.value) !== JSON.stringify(held);
  const ready = canSave(current, changed, file.isSuccess);
  const findings = edit.data?.findings ?? [];
  return (
    <>
      <Row title={I.title} why={I.why} control={<Select label={I.plan} value={head} options={plans.map((plan) => ({ id: plan.key, label: plan.label }))} onChange={setPicked} />} />
      {current === null ? (
        <div className="try"><Button onClick={() => set({ source: 'inline', text: '', mode: 'append' })}>{I.add}</Button></div>
      ) : (
        <div className="instructions">
          <div className="pickers">
            <Segmented<InstructionMode> label={I.how} value={current.mode} options={I.modes} onChange={(mode) => set({ ...current, mode })} />
            <Segmented<InstructionSource> label={I.from} value={current.source} options={I.sources} onChange={(source) => set({ ...current, source, text: '' })} />
          </div>
          {current.source === 'file' ? (
            <input className="input wide" aria-label={I.file} placeholder={I.file} value={current.text} spellCheck={false} onChange={(event) => set({ ...current, text: event.currentTarget.value })} />
          ) : (
            <label className="field">
              <span className="eyebrow">{current.mode === 'strip' ? I.strip : I.text}</span>
              <textarea className="input" spellCheck={false} value={current.text} onChange={(event) => set({ ...current, text: event.currentTarget.value })} />
            </label>
          )}
          <section className="preview" aria-label={I.preview}>
            <h4>{I.preview}</h4>
            <p className="hint">{noteOf(current)}</p>
            {current.source === 'inline' ? <pre>{current.text}</pre> : file.isPending ? <p className="hint">{I.reading}</p> : file.isError ? <p className="hint alert" role="alert">{failureText(file.error)}</p> : file.data === undefined ? null : (
              <>
                <pre>{file.data.text}</pre>
                <p className="hint">{I.chars(file.data.chars)}{file.data.truncated ? ` · ${I.firstLines}` : ''}</p>
              </>
            )}
            {current.mode === 'replace' ? <p className="hint">{I.replaceEffect}</p> : null}
            <p className="hint">{I.depends}</p>
          </section>
        </div>
      )}
      {current === null && held === null ? null : (
        <div className="try">
          <Button kind="go" disabled={!ready || edit.isPending} onClick={() => edit.mutate({ topology: withCommandInstructions(payload.topology, head, current), keys: topologyKeysOf(head) }, { onSuccess: () => setDraft(null) })}>{edit.isPending ? I.saving : I.save}</Button>
          {current === null ? null : <Button onClick={() => set(null)}>{I.remove}</Button>}
        </div>
      )}
      {edit.isSuccess && findings.length === 0 && !changed ? <p className="hint row-note" role="status">{I.saved}</p> : null}
      {findings.map((finding) => <p key={finding.path} className="hint alert row-note" role="alert">{finding.path}: {finding.message}</p>)}
      {edit.isError ? <p className="hint alert row-note" role="alert">{I.failed} {failureText(edit.error)}</p> : null}
    </>
  );
}
