import { useEffect, useState } from 'react';
import { applyConfigPatch, fetchConfig, useConfig } from '@entities/config';
import { fetchKept, fetchTraceKept, removeKept, removeTraceKept } from '@entities/kept';
import type { KeptInventory, KeptStore, TraceInventory } from '@entities/kept';
import { fetchHeads, useHeads } from '@entities/heads';
import type { ConfigValue, EffectiveConfig } from '@shared/api';
import { Confirm, Fault, Input, Key, KeyLink } from '@shared/controls';
import { readFor } from '@shared/lib';
import { Empty, PageHeader, Section } from '@shared/ui';
import { ageOutText, keptRows } from './model';
import type { KeptRow } from './model';
import { H, S } from './strings';
import './kept.css';

type Inventories = Partial<Record<KeptStore, KeptInventory>>;
type Faults = Partial<Record<KeptStore, string>>;
type Traces = Readonly<Record<string, TraceInventory | undefined>>;
type TraceFaults = Readonly<Record<string, string | undefined>>;

function StoreInventory({ store, inventory, fault, onDelete }: {
  store: KeptStore;
  inventory: KeptInventory | undefined;
  fault: string | undefined;
  onDelete: (store: KeptStore) => void;
}) {
  const expires = ageOutText(inventory?.ages_out ?? null);
  return (
    <div className="myx-kept-inventory">
      {fault === undefined ? null : <Fault message={fault} />}
      {inventory === undefined ? <Empty text={H.unknown} /> : (
        <>
          <p>{S.keptCount(inventory.days, inventory.rows)}</p>
          {inventory.reason === undefined ? null : <p>{inventory.reason}</p>}
          {expires === null ? null : <p>{H.agesOut(expires)}</p>}
          {inventory.days === 0 ? <Empty text={H.none} /> : (
            <>
              {store === 'turns' ? <p>{H.turnDelete} {H.turnAfter} {H.turnSessionCost} {H.turnStill}</p> : null}
              <Confirm label={S.deleteKept} confirmLabel={S.deleteCount(inventory.days, inventory.rows)}
                onConfirm={() => onDelete(store)} />
            </>
          )}
        </>
      )}
    </div>
  );
}

function TraceInventoryRow({ head, inventory, fault, onDelete }: {
  head: string;
  inventory: TraceInventory | undefined;
  fault: string | undefined;
  onDelete: ((head: string) => void) | undefined;
}) {
  const expires = ageOutText(inventory?.ages_out ?? null);
  return (
    <div className="myx-kept-inventory">
      <h3>{S.traceFor(head)}</h3>
      {fault === undefined ? null : <Fault message={fault} />}
      {inventory === undefined ? <Empty text={H.unknown} /> : (
        <>
          <p>{S.traceCount(inventory.records, inventory.bytes)}</p>
          {inventory.reason === undefined ? null : <p>{inventory.reason}</p>}
          {expires === null ? null : <p>{H.agesOut(expires)}</p>}
          {inventory.days === 0 ? <Empty text={H.none} /> : onDelete === undefined ? <Empty text={H.pendingTrace} /> : (
            <Confirm label={S.deleteKept} confirmLabel={S.deleteCount(inventory.days, inventory.records)}
              onConfirm={() => onDelete(head)} />
          )}
        </>
      )}
    </div>
  );
}

function LabelControl({ effective, onSwitch }: {
  effective: EffectiveConfig | null;
  onSwitch?: ((key: string, value: ConfigValue) => void) | undefined;
}) {
  const [draft, setDraft] = useState<string | null>(null);
  const current = effective?.activityStoreHeads;
  const selected = draft ?? (typeof current === 'string' ? current : '');
  return (
    <div className="myx-kept-actions">
      <Input label={S.labelHeads} value={selected} onChange={setDraft} />
      <span>{H.labelChoice}</span>
      <Key disabled={effective === null || onSwitch === undefined || selected.trim() === ''}
        onClick={() => { onSwitch?.('activityStoreHeads', selected.trim()); setDraft(null); }}>{S.saveHeads}</Key>
      <Key disabled={effective === null || onSwitch === undefined || current === ''}
        onClick={() => { onSwitch?.('activityStoreHeads', ''); setDraft(null); }}>{S.switch}</Key>
      <span>{S.restart}: {H.restart}</span>
    </div>
  );
}

function TurnControl({ effective, onSwitch }: {
  effective: EffectiveConfig | null;
  onSwitch?: ((key: string, value: ConfigValue) => void) | undefined;
}) {
  const [draft, setDraft] = useState<string | null>(null);
  const current = effective?.perfArchiveRetentionDays;
  const selected = draft ?? (typeof current === 'number' ? String(current) : '');
  const days = Number(selected.trim());
  return (
    <div className="myx-kept-actions">
      <Input label={S.archiveDays} value={selected} onChange={setDraft} numeric w={8} />
      <Key disabled={effective === null || onSwitch === undefined || !Number.isSafeInteger(days) || days <= 0}
        onClick={() => { onSwitch?.('perfArchiveRetentionDays', days); setDraft(null); }}>{S.saveArchive}</Key>
      <Key disabled={typeof current !== 'number' || onSwitch === undefined || current === 0}
        onClick={() => { onSwitch?.('perfArchiveRetentionDays', 0); setDraft(null); }}>{S.stopArchive}</Key>
      <span>{S.restart}: {H.restart}</span>
    </div>
  );
}

export function switchOf(row: KeptRow): { key: string; on: (value: ConfigValue) => boolean; off: ConfigValue; enabled: ConfigValue; restart: boolean } | null {
  switch (row.id) {
    case 'edges': return { key: 'messageEdges', on: (value) => value === true, off: false, enabled: true, restart: true };
    case 'transcripts': return { key: 'transcriptView', on: (value) => value === true, off: false, enabled: true, restart: false };
    default: return null;
  }
}

export function KeptBoard({ inventories, faults = {}, heads = null, traces = {}, traceFaults = {}, effective = null,
  onDelete, onDeleteTrace, onSwitch }: {
  inventories: Inventories;
  faults?: Faults;
  heads?: readonly string[] | null;
  traces?: Traces;
  traceFaults?: TraceFaults;
  effective?: EffectiveConfig | null;
  onDelete: (store: KeptStore) => void;
  onDeleteTrace?: (head: string) => void;
  onSwitch?: (key: string, value: ConfigValue) => void;
}) {
  return (
    <div className="myx-kept">
      <PageHeader title={S.title} info={{ text: H.about, label: S.about }} />
      {keptRows.map((row) => {
        const switcher = switchOf(row);
        const current = switcher === null ? null : effective?.[switcher.key];
        const storing = switcher !== null && current !== undefined && switcher.on(current);
        return (
          <Section key={row.id} title={row.name}>
            <p>{row.holds}</p>
            <p>{row.window}</p>
            <p className="myx-kept-path">{row.location}</p>
            <p>{row.switch}</p>
            {row.id === 'edges' || row.id === 'labels' || row.id === 'turns' ? (
              <StoreInventory store={row.id} inventory={inventories[row.id]} fault={faults[row.id]} onDelete={onDelete} />
            ) : row.id === 'trace' ? heads === null ? <Empty text={H.unknown} />
              : heads.length === 0 ? <Empty text={H.noHeads} />
                : heads.map((head) => <TraceInventoryRow key={head} head={head} inventory={traces[head]}
                  fault={traceFaults[head]} onDelete={onDeleteTrace} />) : null}
            {row.id === 'labels' ? <LabelControl effective={effective} onSwitch={onSwitch} />
              : row.id === 'turns' ? <TurnControl effective={effective} onSwitch={onSwitch} />
                : switcher === null ? <KeyLink href="#/settings">{S.openSettings}</KeyLink> : (
              <div className="myx-kept-actions">
                <Key disabled={effective === null || onSwitch === undefined}
                  onClick={() => onSwitch?.(switcher.key, storing ? switcher.off : switcher.enabled)}>
                  {storing ? S.switch : S.enable}
                </Key>
                {switcher.restart ? <span>{S.restart}: {H.restart}</span> : null}
              </div>
            )}
          </Section>
        );
      })}
    </div>
  );
}

export default function KeptPage() {
  const config = useConfig((state) => state);
  const heads = useHeads((state) => state.data);
  const headKeys = heads?.map((head) => head.key) ?? null;
  const headSignature = headKeys?.join(',') ?? '';
  const [inventories, setInventories] = useState<Inventories>({});
  const [faults, setFaults] = useState<Faults>({});
  const [traces, setTraces] = useState<Record<string, TraceInventory | undefined>>({});
  const [traceFaults, setTraceFaults] = useState<Record<string, string | undefined>>({});
  const [writeFault, setWriteFault] = useState<string | null>(null);
  useEffect(() => {
    void fetchConfig();
    void fetchHeads();
    for (const store of ['edges', 'labels', 'turns'] as const) {
      void fetchKept(store).then(
        (inventory) => setInventories((was) => ({ ...was, [store]: inventory })),
        (error: unknown) => setFaults((was) => ({ ...was, [store]: error instanceof Error ? error.message : String(error) })),
      );
    }
  }, []);
  useEffect(() => {
    if (headKeys === null) return;
    for (const head of headKeys) {
      void fetchTraceKept(head).then(
        (inventory) => setTraces((was) => ({ ...was, [head]: inventory })),
        (error: unknown) => setTraceFaults((was) => ({ ...was, [head]: error instanceof Error ? error.message : String(error) })),
      );
    }
  }, [headSignature]);
  const change = (key: string, value: ConfigValue) => {
    setWriteFault(null);
    void applyConfigPatch({ [key]: value }).then((result) => {
      setWriteFault(result.rejected[key] ?? (result.persisted === null ? result.not_persisted : null));
    }, (error: unknown) => setWriteFault(error instanceof Error ? error.message : String(error)));
  };
  const remove = (store: KeptStore) => {
    void removeKept(store).then((outcome) => {
      setInventories((was) => ({ ...was, [store]: outcome.inventory ?? undefined }));
      const unread = outcome.readError === null ? null
        : outcome.deleted ? H.deletedUnread(outcome.readError) : H.failedUnread(outcome.readError);
      setFaults((was) => ({ ...was, [store]: outcome.deleteError ?? unread ?? undefined }));
    });
  };
  const removeTrace = (head: string) => {
    void removeTraceKept(head).then((outcome) => {
      setTraces((was) => ({ ...was, [head]: outcome.inventory ?? undefined }));
      const unread = outcome.readError === null ? null
        : outcome.deleted ? H.deletedUnread(outcome.readError) : H.failedUnread(outcome.readError);
      setTraceFaults((was) => ({ ...was, [head]: outcome.deleteError ?? unread ?? undefined }));
    });
  };
  return (
    <>
      {writeFault === null ? null : <Fault message={writeFault} />}
      <KeptBoard inventories={inventories} faults={faults} heads={headKeys} traces={traces} traceFaults={traceFaults}
        effective={readFor(config, null).data?.effective ?? null}
        onDelete={remove} onDeleteTrace={removeTrace} onSwitch={change} />
    </>
  );
}
