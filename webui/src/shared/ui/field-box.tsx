// A form field with its provenance layer. The question "where did this value
// come from" is answered under every field, and "does saving it do anything
// now" is answered in words: a hot knob says it applies live, everything else
// says it waits for a restart. Without onChange the field is a readout, not an
// editor, so it renders readOnly rather than swallowing keystrokes.
import { cx } from '../lib';

export type Provenance = 'default' | 'defaults table' | 'head override' | 'state file' | 'env' | 'patch';

export function FieldBox({ label, value, provenance, hot, onChange }: {
  label: string;
  value: string;
  provenance: Provenance;
  hot?: boolean;
  onChange?: (v: string) => void;
}) {
  const editable = onChange !== undefined;
  return (
    <label className={cx('myx-fbox', editable && 'myx-fbox-editable')}>
      <span className="myx-fbox-label">{label}</span>
      <input
        className="myx-fbox-input"
        value={value}
        readOnly={!editable}
        onChange={editable ? (event) => onChange(event.target.value) : undefined}
      />
      <span className="myx-fbox-prov">{provenance}</span>
      <span className="myx-fbox-hot">{hot ? 'applies live' : 'restart to apply'}</span>
    </label>
  );
}
