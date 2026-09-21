// The numbered form of knob field boxes (FEATURES 4.7, CONTRACTS.md section 4).
//
// One row per knob, and the row answers the three questions the operator actually has: what is the
// value, where did that value come from, and does saving a new one do anything now. The provenance
// and the hot/restart verdict are the FieldBox primitive's own printed text — this widget never
// restates them in its own words, because two vocabularies for one fact is how they drift.
//
// The row is NOT a Strip. A Strip is a focusable role="button", and an input inside one is a
// nested interactive element; the world reaches this page through the bay, the plate, the holder
// edge and the type instead. The holder edge appears only on a row that is genuinely in a state
// worth flagging: saved, and not yet in force.
import { useState } from 'react';
import type { ConfigValue } from '@shared/api';
import { parseConfigInput } from '@entities/config';
import type { KnobDisposition } from '@entities/config';
import { Key } from '@shared/controls';
import { FieldBox, HolderEdge } from '@shared/ui';
import { S } from './strings';
import './knob-form.css';

/** The effective value as the editable string the field box holds. `null` is "unset", not "0". */
function shown(value: ConfigValue): string {
  return value === null ? '' : String(value);
}

/** Two digits, so the rack reads as a numbered list at any length. */
function ordinal(index: number): string {
  return String(index).padStart(2, '0');
}

export function KnobForm({ index, disposition, pending, busy, onSave }: {
  index: number;
  disposition: KnobDisposition;
  /** Saved in this console session and not read by the running daemon yet. */
  pending: boolean;
  busy?: boolean;
  onSave: (key: string, value: ConfigValue) => void;
}) {
  const [draft, setDraft] = useState<string | null>(null);
  const shownValue = draft ?? shown(disposition.value);
  const parsed = parseConfigInput(shownValue, disposition.value);
  const dirty = draft !== null && parsed !== disposition.value;

  return (
    <div className="myx-knob" data-knob={disposition.key}>
      <span className="myx-knob-num" aria-hidden="true">{ordinal(index)}</span>
      <FieldBox
        label={disposition.key}
        value={shownValue}
        provenance={disposition.provenance}
        hot={disposition.hot}
        onChange={setDraft}
      />
      <span className="myx-knob-act">
        {pending ? <HolderEdge state="amber" label={S.pending} /> : null}
        {dirty ? (
          <Key busy={busy ?? false} onClick={() => onSave(disposition.key, parsed)}>{S.save}</Key>
        ) : null}
      </span>
    </div>
  );
}

/**
 * The whole rack. `dispositions` arrives already ordered and already filtered by the page's active
 * view; the widget never sorts or hides anything on its own, so what the operator sees is what the
 * view says and not a second opinion.
 */
export function KnobRack({ dispositions, pending, busyKey, onSave }: {
  dispositions: readonly KnobDisposition[];
  /** Keys saved and not yet in force, for the row's holder edge. */
  pending: readonly string[];
  busyKey: string | null;
  onSave: (key: string, value: ConfigValue) => void;
}) {
  return (
    <div className="myx-knobs">
      {dispositions.map((disposition, offset) => (
        <KnobForm
          key={disposition.key}
          index={offset + 1}
          disposition={disposition}
          pending={pending.includes(disposition.key)}
          busy={busyKey === disposition.key}
          onSave={onSave}
        />
      ))}
    </div>
  );
}
