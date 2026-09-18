// An editable field: the same boxed field every strip prints, with a caret in it.
//
// The brief puts edits in the strip's own field boxes, so an input here is not a form control
// dressed as a field - it is the field, with the label above it and the value in the box, and the
// only differences from a printed one are that the box takes a caret, a `--focus` ring and a
// hover/invalid state. Numbers come through the figure face with tabular figures, because a column
// of numbers that does not line up is a column nobody can read down.
import type { ChangeEvent } from 'react';
import { cx } from '@shared/lib';

export function Input({ label, value, onChange, numeric = false, w = 20, id, placeholder, invalid, disabled }: {
  label: string;
  value: string;
  onChange: (next: string) => void;
  /** Numeric fields take the figure face and the numeric keypad, and never an `input type=number`:
   *  a spinner on a knob value is a control the daemon never asked for. */
  numeric?: boolean;
  /** The box's width in `ch`, as every field's is. */
  w?: number;
  id?: string;
  placeholder?: string;
  invalid?: boolean;
  disabled?: boolean;
}) {
  const change = (event: ChangeEvent<HTMLInputElement>) => onChange(event.target.value);
  return (
    <label className={cx('myx-input', invalid === true && 'myx-input-invalid')} htmlFor={id}>
      <span className="myx-input-label">{label}</span>
      <input
        id={id}
        className="myx-input-box"
        style={{ width: `${w}ch` }}
        value={value}
        onChange={change}
        placeholder={placeholder}
        inputMode={numeric ? 'numeric' : undefined}
        autoComplete="off"
        spellCheck={false}
        disabled={disabled}
      />
    </label>
  );
}
