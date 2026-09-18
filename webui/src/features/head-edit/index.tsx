// Add, edit and disable a head, as a form over the topology document.
//
// The feature owns no state and performs no write: it takes the parsed topology, hands back a new
// one, and lets the page decide when that becomes a PUT. That is deliberate — the page holds the
// draft so the diff and the backup note are computed against the same document the operator is
// looking at, and a feature that wrote on every keystroke would take that decision away.
//
// `disable` is a two-step action, not a one-click one: the daemon has no enabled flag, so
// disabling a head means removing it from the document, and the operator should see what they are
// about to un-declare before it happens.
import { useState } from 'react';
import { ConfirmBtn, FieldBox } from '@shared/ui';
import { EMPTY_DRAFT, TOPOLOGY_PROVENANCE, headRows, providerKeys, validateNewHead, withHeadField, withNewHead, withoutHead } from './model';
import type { HeadDraft, HeadFinding } from './model';
import { S } from './strings';
import './head-edit.css';

/** The head's editable fields, in the order the topology declares them. `key` is not here: the
 *  key IS the topology key, and renaming it would orphan every session bound to the old name. */
type HeadField = Exclude<keyof HeadDraft, 'key'>;

const FIELDS: ReadonlyArray<{ field: HeadField; label: string }> = [
  { field: 'provider', label: S.provider },
  { field: 'port', label: S.port },
  { field: 'discoveryPrefix', label: S.prefix },
  { field: 'pinnedModel', label: S.pinned },
];

// The model is re-exported here because a slice's public API is its barrel and a deep import of
// `@features/head-edit/model` is a lint error: the page needs the same readings the form makes.
export { EMPTY_DRAFT, TOPOLOGY_PROVENANCE, headRows, providerKeys, validateNewHead, withoutHead, withHeadField, withNewHead } from './model';
export type { HeadDraft, HeadFinding, HeadRow } from './model';

export function HeadEditRow({ topology, row, busy, onChange }: {
  topology: Record<string, unknown>;
  row: ReturnType<typeof headRows>[number];
  busy: boolean;
  onChange: (next: Record<string, unknown>) => void;
}) {
  return (
    <div className="myx-head">
      <div className="myx-head-id">
        <span className="myx-head-key">{row.key}</span>
        <span className="myx-head-note">{S.source}</span>
      </div>
      <div className="myx-head-fields">
        {FIELDS.map(({ field, label }) => (
          <FieldBox
            key={field}
            label={label}
            value={row[field]}
            provenance={TOPOLOGY_PROVENANCE}
            hot={false}
            onChange={(value) => onChange(withHeadField(topology, row.key, field, value))}
          />
        ))}
      </div>
      <ConfirmBtn busy={busy} onConfirm={() => onChange(withoutHead(topology, row.key))}>{S.disable}</ConfirmBtn>
    </div>
  );
}

/** The add form. It refuses to hand the page a head it already knows is wrong. */
export function HeadAddForm({ topology, onAdd }: {
  topology: Record<string, unknown>;
  onAdd: (next: Record<string, unknown>) => void;
}) {
  const [draft, setDraft] = useState<HeadDraft>(EMPTY_DRAFT);
  const [shown, setShown] = useState<HeadFinding[]>([]);
  const providers = providerKeys(topology);

  const field = (name: keyof HeadDraft, label: string) => (
    <FieldBox
      label={label}
      value={draft[name]}
      provenance={TOPOLOGY_PROVENANCE}
      hot={false}
      onChange={(value) => setDraft((current) => ({ ...current, [name]: value }))}
    />
  );

  return (
    <div className="myx-head myx-head-new">
      <div className="myx-head-id">
        <span className="myx-head-key">{S.addHead}</span>
        <span className="myx-head-note">{providers.length === 0 ? 'no providers declared' : providers.join(', ')}</span>
      </div>
      <div className="myx-head-fields">
        {field('key', S.key)}
        {field('provider', S.provider)}
        {field('port', S.port)}
        {field('discoveryPrefix', S.prefix)}
        {field('pinnedModel', S.pinned)}
      </div>
      <div className="myx-head-actions">
        <button
          type="button"
          className="myx-btn myx-btn-primary"
          onClick={() => {
            const findings = validateNewHead(draft, topology);
            setShown(findings);
            if (findings.length === 0) {
              onAdd(withNewHead(topology, draft));
              setDraft(EMPTY_DRAFT);
            }
          }}
        >
          {S.addHead}
        </button>
      </div>
      {shown.length === 0 ? null : (
        <ul className="myx-head-findings" role="alert">
          {shown.map((finding) => (
            <li key={finding.field}>{finding.message}</li>
          ))}
        </ul>
      )}
    </div>
  );
}
