// `splice add-model` from the Models page (V4-220): the catalogue models an OpenRouter head does not
// reach yet, picked and added in one request (AddModelRoutes.kt), where the CLI asks two prompts.
//
// THE OFFER IS THE DAEMON'S, READ WHEN THE PANEL OPENS. The add names ids, and the daemon checks them
// against splice.toml as it stands when the add runs: an id no longer on offer is its 409, printed
// verbatim, never a list this page kept.
//
// AN INLINE TWO-STEP KEY, as the add's save: the add writes splice.toml and restarts the daemon, so
// the armed key says both, and the answer is the restart the daemon took.
import { useEffect, useState } from 'react';
import { addModels, fetchAddModelOffers } from '@entities/add';
import type { AddModelOffers, AddModelsAdded } from '@entities/add';
import { Blank, Choice, Confirm, Fault, Flag } from '@shared/controls';
import { fmtTokens } from '@shared/lib';
import { Badge, Empty, KeyValue } from '@shared/ui';
import { offerOf, pickedIds, toggled } from './model';
import { H, S } from './strings';
import './add-model.css';

const messageOf = (err: unknown): string => (err instanceof Error ? err.message : String(err));

/** What an add wrote, and the restart that makes the models reachable. */
export function AddedModels({ added }: { added: AddModelsAdded }) {
  const { restart } = added;
  return (
    <div className="myx-amodel">
      <KeyValue rows={[
        [S.head, added.head],
        [S.added, added.added.join(', ')],
        [S.written, added.path],
        [S.restart, <Badge key="restart" tone={restart.status === 'refused' ? 'warn' : 'accent'}>{restart.status}</Badge>],
      ]} />
      {restart.status === 'draining' ? <p className="myx-amodel-note" role="status">{H.draining}</p>
        : restart.status === 'waiting' ? <p className="myx-amodel-note" role="status">{H.waiting(restart.compactions?.length ?? 0)}</p>
        : <Fault message={restart.error ?? restart.status} />}
    </div>
  );
}

/** The pick: one head's offered models, each a switch, and the key that adds what is on. */
export function PickModels({ offers, head, picked, busy, onHead, onPick, onAdd }: {
  offers: AddModelOffers;
  head: string | null;
  picked: ReadonlySet<string>;
  busy: boolean;
  onHead: (head: string) => void;
  onPick: (next: ReadonlySet<string>) => void;
  onAdd: (head: string, ids: string[]) => void;
}) {
  const offer = offerOf(offers.heads, head);
  if (offer === null) return <Empty text={S.noHeads} source={H.noHeads} />;
  const ids = pickedIds(offer, picked);
  return (
    <div className="myx-amodel">
      {offers.heads.length > 1
        ? <Choice label={S.head} value={offer.head} options={offers.heads.map((each) => ({ value: each.head, label: each.head }))} onChange={onHead} />
        : <KeyValue rows={[[S.head, offer.head]]} />}
      {offer.models.length === 0 ? <Empty text={S.allOffered} source={H.allOffered} /> : (
        <ul className="myx-amodel-list" aria-label={S.offered}>
          {offer.models.map((model) => (
            <li key={model.id} className="myx-amodel-row">
              <span className="myx-amodel-name">
                <span>{model.label}</span>
                <code className="myx-amodel-id">{model.id}</code>
              </span>
              <span className="myx-amodel-window">{fmtTokens(model.context_window)}</span>
              <Flag
                on={picked.has(model.id)}
                onLabel={S.picked}
                offLabel={S.pick}
                ariaLabel={S.pickModel(model.id)}
                onChange={(on) => onPick(toggled(picked, model.id, on))}
              />
            </li>
          ))}
        </ul>
      )}
      {ids.length === 0 ? null : <Confirm label={S.add(ids.length)} confirmLabel={S.addArmed} busy={busy} onConfirm={() => onAdd(offer.head, ids)} />}
    </div>
  );
}

export function AddModels() {
  const [offers, setOffers] = useState<AddModelOffers | null>(null);
  const [head, setHead] = useState<string | null>(null);
  const [picked, setPicked] = useState<ReadonlySet<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [fault, setFault] = useState<string | null>(null);
  const [added, setAdded] = useState<AddModelsAdded | null>(null);

  useEffect(() => {
    fetchAddModelOffers().then(setOffers, (err: unknown) => setFault(messageOf(err)));
  }, []);

  const add = (target: string, ids: string[]) => {
    setBusy(true);
    setFault(null);
    addModels(target, ids).then(setAdded, (err: unknown) => setFault(messageOf(err))).finally(() => setBusy(false));
  };

  if (added !== null) return <AddedModels added={added} />;
  return (
    <>
      {offers === null ? (fault === null ? <Blank strips={3} /> : null) : (
        <PickModels
          offers={offers}
          head={head}
          picked={picked}
          busy={busy}
          onHead={(next) => {
            setHead(next);
            setPicked(new Set());
          }}
          onPick={setPicked}
          onAdd={add}
        />
      )}
      {fault === null ? null : <Fault message={fault} />}
    </>
  );
}
