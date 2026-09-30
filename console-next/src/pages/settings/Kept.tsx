import type { ReactNode } from 'react';
import { failureText } from '../../api/client';
import { useHeads } from '../../api/queries';
import { useKept, useKeptDelete, useTraceKept } from '../../api/kept';
import { entriesOf, keptLines } from '../../lib/kept';
import { textOf, numberOf } from '../../lib/settings';
import { K } from '../../lib/words-kept';
import type { ConfigPayload } from '../../types/core';
import type { KeptInventory, KeptStore, TraceInventory } from '../../types/kept';
import { Button, Confirm, NumberInput, Switch, TextInput } from '../../ui';
import { Row, SaveNote, useSetting } from './Row';

/** One store's census, and the one act that clears it. Nothing is claimed before the store answers. */
function Census({ inventory, fault, title, why, target }: { inventory: KeptInventory | TraceInventory | undefined; fault: string | null; title: string; why: string; target: { store: KeptStore } | { head: string } }) {
  const remove = useKeptDelete();
  if (fault !== null) return <span className="tip bad" role="alert">{fault}</span>;
  if (inventory === undefined) return <span className="tip">{K.unknown}</span>;
  return (
    <span className="census">
      {keptLines(inventory).map((line) => <span key={line}>{line}</span>)}
      {inventory.days === 0 ? null : (
        <Confirm
          title={K.deleteTitle(title)}
          why={`${why} ${K.deleteWhy}`}
          act={K.deleteAct(inventory.days, entriesOf(inventory))}
          cancel={K.cancel}
          onConfirm={() => remove.mutateAsync(target).then(() => undefined, (err: unknown) => Promise.reject(new Error(failureText(err))))}
          trigger={<Button small kind="danger">{K.delete}</Button>}
        />
      )}
    </span>
  );
}

function StoreRow({ store, title, holds, why, config, extra }: { store: KeptStore; title: string; holds: string; why: string; config: ConfigPayload; extra?: (config: ConfigPayload) => ReactNode }) {
  const read = useKept(store);
  return (
    <Row
      title={title}
      why={holds}
      control={
        <span className="kept-ctl">
          {extra?.(config)}
          <Census inventory={read.data} fault={read.isError ? failureText(read.error) : null} title={title} why={why} target={{ store }} />
        </span>
      }
    />
  );
}

function TraceHead({ head, label }: { head: string; label: string }) {
  const read = useTraceKept(head);
  return (
    <Row
      title={K.trace.plan(label)}
      why=""
      control={<Census inventory={read.data} fault={read.isError ? failureText(read.error) : null} title={K.trace.name} why={K.trace.holds} target={{ head }} />}
    />
  );
}

/** Every store splice owns: what is in it, how to stop it keeping more, and how to clear it. */
export function Kept({ config }: { config: ConfigPayload }) {
  const heads = useHeads();
  const edges = useSetting('messageEdges');
  const labels = useSetting('activityStoreHeads');
  const transcripts = useSetting('transcriptView');
  const archive = useSetting('perfArchiveRetentionDays');
  const traceHeads = heads.data?.heads ?? [];
  return (
    <section className="kept-block" aria-labelledby="settings-kept">
      <h3 id="settings-kept" className="kept-title">{K.title}</h3>
      <p className="kept-why">{K.why}</p>
      <StoreRow
        store="edges"
        title={K.edges.name}
        holds={K.edges.holds}
        why={K.edges.holds}
        config={config}
        extra={() => (
          <>
            <Switch label={K.edges.name} checked={config.effective['messageEdges'] === true} onChange={(next) => edges.save(next)} />
            <SaveNote keys={['messageEdges']} saved={edges.saved} />
          </>
        )}
      />
      <StoreRow
        store="labels"
        title={K.labels.name}
        holds={K.labels.holds}
        why={K.labels.holds}
        config={config}
        extra={() => (
          <>
            <TextInput label={K.labels.headsLabel} value={textOf(config.effective['activityStoreHeads'])} onCommit={(next) => labels.save(next.trim())} />
            <span className="tip">{K.labels.headsHint}</span>
            <SaveNote keys={['activityStoreHeads']} saved={labels.saved} />
          </>
        )}
      />
      <Row title={K.trace.name} why={K.trace.holds} control={traceHeads.length === 0 ? <span className="tip">{K.trace.none}</span> : <span />} />
      {traceHeads.map((head) => <TraceHead key={head.key} head={head.key} label={head.label} />)}
      <Row title={K.wire.name} why={K.wire.holds} control={<span />} />
      <Row
        title={K.transcripts.name}
        why={K.transcripts.holds}
        control={
          <span className="kept-ctl">
            <Switch label={K.transcripts.name} checked={config.effective['transcriptView'] === true} onChange={(next) => transcripts.save(next)} />
            <SaveNote keys={['transcriptView']} saved={transcripts.saved} />
          </span>
        }
      />
      <StoreRow
        store="turns"
        title={K.turns.name}
        holds={K.turns.holds}
        why={K.turns.deleteWhy}
        config={config}
        extra={() => (
          <>
            <NumberInput label={K.turns.archive} value={numberOf(config.effective['perfArchiveRetentionDays']) ?? 0} onCommit={(next) => archive.save(next)} suffix="days" />
            <span className="tip">{K.turns.archiveWhy}</span>
            <SaveNote keys={['perfArchiveRetentionDays']} saved={archive.saved} />
          </>
        )}
      />
    </section>
  );
}
