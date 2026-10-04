import { useState } from 'react';
import { failureText } from '../../api/client';
import { useTopology, useTopologyEdit } from '../../api/config';
import { isPendingRoute } from '../../api/auth';
import { useConfig, useHeads } from '../../api/queries';
import { headOptions, knobDispositions } from '../../lib/config';
import { KNOB_META, unitText } from '../../lib/knobs';
import { controlOf, otherKnobs, textOf, withHeadOverride } from '../../lib/settings';
import { GROUP_LABELS, KNOB_HELP, KNOB_LABELS } from '../../lib/words-knobs';
import type { KnobDisposition } from '../../types/config';
import type { ConfigValue } from '../../types/core';
import { Button, NumberInput, Select, Switch, TextInput } from '../../ui';
import { AS } from './copy';
import { Row, SaveNote, useSetting } from './Row';
import type { Saved } from './Row';

const nameOf = (key: string): string => (KNOB_LABELS as Record<string, string>)[key] ?? key;

/** One knob as its own typed control. Scoped to all plans it is PATCHed; scoped to one plan it is that plan's override in
 *  splice.toml, which is a different write and never a PATCH (a PATCH would land in the state file, above every override). */
function KnobRow({ knob, head, topology }: { knob: KnobDisposition; head: string | null; topology: Record<string, unknown> | null }) {
  const global = useSetting(knob.key);
  const edit = useTopologyEdit();
  const [own, setOwn] = useState<Saved>(null);
  const meta = KNOB_META[knob.key];
  const control = controlOf(knob.value, meta, head);
  const scoped = head !== null;

  const save = (value: ConfigValue): void => {
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
  const reset = (): void => {
    if (head === null || topology === null) return;
    edit.mutate({ topology: withHeadOverride(topology, head, knob.key, null), keys: [`heads.${head}.overrides.${knob.key}`] }, {
      onSuccess: () => setOwn({ kind: 'saved' }),
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
        return <NumberInput label={nameOf(knob.key)} value={typeof knob.value === 'number' ? knob.value : 0} suffix={number.suffix} onCommit={save} />;
      case 'text':
        return <TextInput label={nameOf(knob.key)} value={textOf(knob.value)} onCommit={(next) => save(next === '' ? null : next)} />;
    }
  })();

  const saved = scoped ? own : global.saved;
  const notes = [
    control.kind === 'locked' ? AS.locked : null,
    control.kind === 'head-only' ? AS.headOnly : null,
    number.readable,
    knob.provenance === 'default' ? null : AS.source[knob.provenance],
    !scoped && knob.overriddenBy.length > 0 ? AS.overriddenBy(knob.overriddenBy.join(', ')) : null,
  ].filter((note): note is string => note !== null);

  return (
    <Row
      title={nameOf(knob.key)}
      why={KNOB_HELP[knob.key] ?? ''}
      settingKey={knob.key}
      control={
        <>
          {ctl}
          {scoped && knob.provenance === 'head override' ? <Button small onClick={reset}>{AS.reset}</Button> : null}
        </>
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
  const wide = useConfig();
  const declared = heads.data?.heads.map((entry) => entry.key) ?? [];
  const options = headOptions(wide.data?.layers.perHead, declared);
  const labelOf = (key: string): string => (key === 'global' ? AS.scopeAll : (heads.data?.heads.find((entry) => entry.key === key)?.label ?? key));

  if (config.isError) return <p className="hint alert row-note" role="alert">{failureText(config.error)}</p>;
  if (config.data === undefined) return <p className="hint row-note">…</p>;
  const file = topology.data === undefined || isPendingRoute(topology.data) ? null : topology.data.topology;
  const groups = otherKnobs(knobDispositions(config.data, head ?? undefined));

  return (
    <>
      <Row
        title={AS.scope}
        why={AS.scopeWhy}
        control={<Select label={AS.scope} value={head ?? 'global'} options={options.map((key) => ({ id: key, label: labelOf(key) }))} onChange={(next) => setHead(next === 'global' ? null : next)} />}
        note={head !== null && file === null ? <span className="tip bad">{AS.headUnserved}</span> : null}
      />
      {groups.length === 0 ? <p className="hint row-note">{AS.empty}</p> : null}
      {groups.map((group) => (
        <div key={group.group} className="knob-group">
          <h3 className="knob-group-title">{GROUP_LABELS[group.group]}</h3>
          {group.knobs.map((knob) => (
            <KnobRow key={`${head ?? 'global'}:${knob.key}`} knob={knob} head={head} topology={file} />
          ))}
        </div>
      ))}
    </>
  );
}
