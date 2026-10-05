import { useState } from 'react';
import { M } from '../lib/words-models';
import { Button } from './Button';
import { Select, TextInput } from './controls';

/** Catalog labels are choices; IDs stay write identities. Unlisted IDs remain selectable and editable. */
export function ModelSelect({ value, models, label, defaultLabel = M.commandDefault, onChange }: {
  value: string | null;
  models: readonly { id: string; label: string }[];
  label: string;
  defaultLabel?: string;
  onChange: (value: string | null) => void;
}) {
  const [custom, setCustom] = useState(false);
  const known = [...new Map(models.filter(model => model.id !== '').map(model => [model.id, model])).values()];
  const options = [{ id: '', label: defaultLabel }, ...known];
  if (value !== null && value !== '' && !known.some(model => model.id === value)) options.push({ id: value, label: value });
  return <div className="model-choice">
    <Select label={label} value={value ?? ''} options={options} onChange={next => { setCustom(false); onChange(next === '' ? null : next); }} />
    <Button small aria-expanded={custom} onClick={() => setCustom(!custom)}>{M.enterModelId}</Button>
    {custom ? <TextInput label={M.customModelId} value={value ?? ''} onCommit={next => onChange(next.trim() || null)} /> : null}
  </div>;
}
