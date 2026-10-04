import { useState } from 'react';
import { isPendingRoute } from '../../api/auth';
import { failureText } from '../../api/client';
import { useTopology, useTopologyEdit } from '../../api/config';
import { useConfig, useHeads } from '../../api/queries';
import { EFFORT_CHOICES, effortChoice, headEffort, textOf, withHeadOverride } from '../../lib/settings';
import type { Effort } from '../../lib/settings';
import { Fault, Segmented, Select } from '../../ui';
import { AS, T } from './copy';
import { Row, SaveNote, useSetting } from './Row';
import type { Saved } from './Row';

/** A saved command default is not the running value until the daemon applies it. */
function ScopedThinking({ head }: { head: string | null }) {
  const config = useConfig(head ?? undefined);
  const topology = useTopology();
  const edit = useTopologyEdit();
  const global = useSetting('effort');
  const [saved, setSaved] = useState<Saved>(null);
  if (config.isError) return <Fault message={failureText(config.error)} onRetry={() => void config.refetch()} />;
  if (config.data === undefined) return <p className="hint row-note">{T.effortReading}</p>;
  const file = topology.data === undefined || isPendingRoute(topology.data) ? null : topology.data.topology;
  const effective = config.data.effective['effort'];
  const running = effortChoice(effective);
  const runningWord = running === null ? textOf(effective) : T.effortLevel[running];
  if (head !== null && file === null) return <>
    <p className="hint row-note">{T.effortRunning(runningWord)}</p>
    {topology.isError ? <Fault message={failureText(topology.error)} onRetry={() => void topology.refetch()} /> : <p className="hint row-note">{topology.isPending ? T.effortReading : AS.headUnserved}</p>}
  </>;
  const own = head === null || file === null ? null : headEffort(file, head);
  const chosen = effortChoice(head === null ? effective : own);
  const choices = EFFORT_CHOICES.map(([id, label]) => [id, id === '' && head !== null ? T.effortInherit : label] as const);
  const savedWord = chosen === null ? textOf(own ?? effective) : chosen === '' && head !== null ? T.effortInherit : T.effortLevel[chosen];
  const key = head === null ? 'effort' : `heads.${head}.overrides.effort`;
  const save = (next: Effort): void => {
    const value = next === '' ? null : next;
    if (head === null) {
      global.save(value);
      return;
    }
    if (file === null || edit.isPending) return;
    edit.mutate({ topology: withHeadOverride(file, head, 'effort', value), keys: [key] }, {
      onSuccess: result => setSaved(result.ok ? { kind: result.restart_required ? 'waits' : 'saved' } : { kind: 'rejected', reason: (result.findings ?? []).map(finding => finding.message).join('; ') }),
      onError: error => setSaved({ kind: 'failed', message: failureText(error) }),
    });
  };
  const saving = head === null ? global.saved?.kind === 'saving' : edit.isPending;
  return <Row
    title={T.effort}
    why={T.effortWhy}
    settingKey="effort"
    control={chosen === null ? <span className="folder">{T.effortElsewhere(savedWord)}</span> : <div className="thinking-choice">
      <fieldset disabled={saving}><Segmented label={T.effort} value={chosen} options={choices} onChange={save} /></fieldset>
      <p className="hint">{head !== null && chosen === '' ? T.effortInherited : T.effortHelp[chosen]}</p>
      <details className="thinking-levels"><summary>{T.effortCompare}</summary>{EFFORT_CHOICES.map(([id, label]) => <p key={id}><b>{label}</b> · {T.effortHelp[id]}</p>)}</details>
    </div>}
    note={<>
      <span className="tip">{T.effortRunning(runningWord)}</span>
      {head === null ? null : <span className="tip">{T.effortSaved(savedWord)}</span>}
      <SaveNote keys={[key]} saved={saving ? { kind: 'saving' } : head === null ? global.saved : saved} />
    </>}
  />;
}

export function Thinking() {
  const heads = useHeads();
  const [head, setHead] = useState<string | null>(null);
  return <>
    <Row title={T.effortScope} why={T.effortScopeWhy} control={<Select
      label={T.effortScope}
      value={head ?? ''}
      options={[{ id: '', label: T.effortAll }, ...(heads.data?.heads.map(row => ({ id: row.key, label: row.label })) ?? [])]}
      onChange={next => setHead(next === '' ? null : next)}
    />} />
    {heads.isError ? <Fault message={failureText(heads.error)} onRetry={() => void heads.refetch()} /> : null}
    <ScopedThinking key={head ?? ''} head={head} />
  </>;
}
