import { useState } from 'react';
import { useIsMutating } from '@tanstack/react-query';
import { failureText } from '../../api/client';
import { topologyKey, useTopology, useTopologyEdit } from '../../api/config';
import { isPendingRoute } from '../../api/auth';
import { useModels } from '../../api/models';
import { useConfig, useHeads } from '../../api/queries';
import { headOptions, knobDispositions, shadowOfOverride } from '../../lib/config';
import { KNOB_META, unitText } from '../../lib/knobs';
import { controlOf, headOverrideOf, otherKnobs, textOf, withHeadOverride } from '../../lib/settings';
import { GROUP_LABELS, KNOB_HELP, KNOB_LABELS } from '../../lib/words-knobs';
import type { KnobDisposition } from '../../types/config';
import type { ConfigValue } from '../../types/core';
import { Button, NumberInput, QuantityInput, Select, Switch, TextInput, UrlInput } from '../../ui';
import { ModelSelect } from '../../ui/ModelSelect';
import { AS } from './copy';
import { Row, SaveNote, useSetting } from './Row';
import type { Saved } from './Row';

const nameOf = (key: string): string => (KNOB_LABELS as Record<string, string>)[key] ?? key;

/** These knobs name provider models, not command keys. Catalog labels never become the saved IDs. */
function ModelKnob({ knob, value, masked, scoped, save }: { knob: KnobDisposition; value: string; masked: boolean; scoped: boolean; save: (value: ConfigValue) => void }) {
  const catalog = useModels();
  const heads = useHeads();
  const provider = knob.key === 'grokModel' ? 'grok' : 'codex';
  const authKind = provider === 'grok' ? 'grok-oauth' : 'chatgpt-oauth';
  const models = catalog.data === undefined || isPendingRoute(catalog.data) ? [] : catalog.data.heads.filter(entry => entry.provider === provider || heads.data?.heads.some(head => head.key === entry.head && head.authKind === authKind) === true).flatMap(head => head.models);
  const labelOf = (id: string): string => models.find(model => model.id === id)?.label || id;
  const current = textOf(knob.value);
  const reported = knob.key === 'foldReasoningModels'
    ? current.split(',').map(id => id.trim()).filter(id => id !== '').map(labelOf).join(', ') || AS.noFoldModels
    : labelOf(current) || AS.modelDefault;
  const note = <>
    {value === current ? null : <p className="hint">{AS.reportedModel(reported)}</p>}
    {masked ? <p className="hint">{AS.modelMasked}</p> : null}
    {catalog.isError ? <p className="hint alert" role="alert">{failureText(catalog.error)}</p> : null}
  </>;
  if (knob.key !== 'foldReasoningModels') return <div className="setting-model">
    <ModelSelect label={nameOf(knob.key)} value={value === '' ? null : value} models={models} defaultLabel={scoped ? AS.reset : AS.modelDefault} onChange={save} />
    {note}
  </div>;
  const chosen = [...new Set(value.split(',').map(id => id.trim()).filter(id => id !== ''))];
  const write = (ids: string[]): void => {
    // A nonblank empty comma list stays empty through scalar coercion rather than restoring the default.
    save(ids.length === 0 ? ',' : ids.join(','));
  };
  return <div className="setting-model">
    {chosen.length === 0 ? <p className="hint">{AS.noFoldModels}</p> : <ul className="fold-models">
      {chosen.map(id => {
        const label = labelOf(id);
        return <li key={id}><span>{label}</span><Button small aria-label={AS.removeModel(label)} onClick={() => write(chosen.filter(current => current !== id))}>{AS.remove}</Button></li>;
      })}
    </ul>}
    <ModelSelect label={AS.addFoldModel} value={null} models={models.filter(model => !chosen.includes(model.id))} defaultLabel={AS.addFoldModel} onChange={next => { if (next !== null && !chosen.includes(next)) write([...chosen, next]); }} />
    {note}
  </div>;
}

/** One knob as its own typed control. Scoped to all plans it is PATCHed; scoped to one plan it is that plan's override in
 *  splice.toml, which is a different write and never a PATCH (a PATCH would land in the state file, above every override). */
function KnobRow({ knob, head, topology, topologyPending, inherited, masked }: { knob: KnobDisposition; head: string | null; topology: Record<string, unknown> | null; topologyPending: boolean; inherited: ConfigValue; masked: boolean }) {
  const global = useSetting(knob.key);
  const edit = useTopologyEdit();
  const [own, setOwn] = useState<Saved>(null);
  const meta = KNOB_META[knob.key];
  const control = controlOf(knob.value, meta, head);
  const scoped = head !== null;
  const override = head === null || topology === null ? null : headOverrideOf(topology, head, knob.key);
  const busy = scoped ? topologyPending || topology === null : global.saved?.kind === 'saving';
  const saved: Saved = scoped ? edit.isPending ? { kind: 'saving' } : own : global.saved;

  const save = (value: ConfigValue): void => {
    if (busy) return;
    if (!scoped) {
      global.save(value);
      return;
    }
    if (topology === null) return;
    edit.mutate({ topology: withHeadOverride(topology, head, knob.key, value), keys: [`heads.${head}.overrides.${knob.key}`] }, {
      onSuccess: (result) => setOwn(result.ok ? { kind: 'saved' } : { kind: 'rejected', reason: (result.findings ?? []).map((finding) => `${finding.path}: ${finding.message}`).join('; ') }),
      onError: (err) => setOwn({ kind: 'failed', message: failureText(err) }),
    });
  };

  const number = typeof knob.value === 'number' ? unitText(meta?.unit, knob.value) : { suffix: null, readable: null };
  const ctl = ((): React.ReactNode => {
    switch (control.kind) {
      case 'locked':
        return <span className="folder code">{textOf(knob.value) || AS.none}</span>;
      case 'head-only':
        return <span className="folder code">{textOf(knob.value) || AS.none}</span>;
      case 'switch':
        return <Switch label={nameOf(knob.key)} checked={knob.value === true} onChange={save} />;
      case 'choice':
        return (
          <Select
            label={nameOf(knob.key)}
            value={textOf(knob.value)}
            options={control.choices.map((choice) => ({ id: choice, label: choice === '' ? AS.none : choice }))}
            onChange={(next) => save(next === '' ? null : next)}
          />
        );
      case 'number':
        return meta?.unit === 'ms' || meta?.unit === 'bytes'
          ? <QuantityInput label={nameOf(knob.key)} value={typeof knob.value === 'number' ? knob.value : 0} unit={meta.unit} onCommit={save} />
          : <NumberInput label={nameOf(knob.key)} value={typeof knob.value === 'number' ? knob.value : 0} suffix={number.suffix} onCommit={save} />;
      case 'text':
        if (['chatgptApiBase', 'xaiApiBase'].includes(knob.key)) return <UrlInput label={nameOf(knob.key)} value={textOf(knob.value)} onCommit={next => save(next === '' ? null : next)} />;
        return ['pinnedModel', 'grokModel', 'foldReasoningModels'].includes(knob.key)
          ? <ModelKnob knob={knob} value={scoped ? override ?? textOf(inherited) : textOf(knob.value)} scoped={scoped} masked={scoped && override !== null && masked} save={save} />
          : <TextInput label={nameOf(knob.key)} value={textOf(knob.value)} onCommit={(next) => save(next === '' ? null : next)} />;
    }
  })();

  const notes = [
    control.kind === 'locked' ? AS.locked : null,
    control.kind === 'head-only' ? AS.headOnly : null,
    control.kind === 'number' && (meta?.unit === 'ms' || meta?.unit === 'bytes') ? null : number.readable,
    knob.provenance === 'default' ? null : AS.source[knob.provenance],
    !scoped && knob.overriddenBy.length > 0 ? AS.overriddenBy(knob.overriddenBy.join(', ')) : null,
  ].filter((note): note is string => note !== null);

  return (
    <Row
      title={nameOf(knob.key)}
      why={KNOB_HELP[knob.key] ?? ''}
      settingKey={knob.key}
      control={
        <fieldset className="setting-controls" disabled={busy} aria-label={nameOf(knob.key)}>
          {ctl}
          {scoped && override !== null ? <Button small onClick={() => save(null)}>{AS.reset}</Button> : null}
        </fieldset>
      }
      note={
        <>
          {notes.length === 0 ? null : <span className="tip">{notes.join(' · ')}</span>}
          <SaveNote keys={knob.hot ? [] : [scoped ? `heads.${head}.overrides.${knob.key}` : knob.key]} saved={saved} />
        </>
      }
    />
  );
}

/** Every knob the sections above do not draw, in the groups the daemon's table places them in, for all plans or for one. */
export function AllSettings() {
  const [head, setHead] = useState<string | null>(null);
  const config = useConfig(head ?? undefined);
  const heads = useHeads();
  const topology = useTopology();
  const topologyPending = useIsMutating({ mutationKey: [...topologyKey] }) > 0;
  const wide = useConfig();
  const declared = heads.data?.heads.map((entry) => entry.key) ?? [];
  const options = headOptions(wide.data?.layers.perHead, declared);
  const labelOf = (key: string): string => (key === 'global' ? AS.scopeAll : (heads.data?.heads.find((entry) => entry.key === key)?.label ?? key));

  if (config.isError || wide.isError) return <p className="hint alert row-note" role="alert">{failureText(config.isError ? config.error : wide.error)}</p>;
  if (config.data === undefined || wide.data === undefined) return <p className="hint row-note">…</p>;
  const file = topology.isError || topology.data === undefined || isPendingRoute(topology.data) ? null : topology.data.topology;
  const groups = otherKnobs(knobDispositions(config.data, head ?? undefined));

  return (
    <>
      <Row
        title={AS.scope}
        why={AS.scopeWhy}
        control={<Select label={AS.scope} value={head ?? 'global'} options={options.map((key) => ({ id: key, label: labelOf(key) }))} onChange={(next) => setHead(next === 'global' ? null : next)} />}
        note={topology.isError ? <span className="tip bad" role="alert">{failureText(topology.error)}</span> : head !== null && file === null ? <span className="tip bad">{AS.headUnserved}</span> : null}
      />
      {groups.length === 0 ? <p className="hint row-note">{AS.empty}</p> : null}
      {groups.map((group) => (
        <div key={group.group} className="knob-group">
          <h3 className="knob-group-title">{GROUP_LABELS[group.group]}</h3>
          {group.knobs.map((knob) => (
            <KnobRow key={`${head ?? 'global'}:${knob.key}`} knob={knob} head={head} topology={file} topologyPending={topologyPending} inherited={wide.data?.effective[knob.key] ?? null} masked={shadowOfOverride(knob.key, config.data) !== null} />
          ))}
        </div>
      ))}
    </>
  );
}
