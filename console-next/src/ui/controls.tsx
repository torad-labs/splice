import * as Menu from '@radix-ui/react-dropdown-menu';
import { useEffect, useState } from 'react';
import { Check, Chevron } from './icons';
import { W } from '../lib/words';
import { quantityText, quantityUnits, quantityValue } from '../lib/quantity';

/** An on/off choice that takes effect as it is made. */
export function Switch({ checked, onChange, label, disabled = false }: { checked: boolean; onChange: (next: boolean) => void; label: string; disabled?: boolean }) {
  return <button type="button" role="switch" aria-checked={checked} aria-label={label} disabled={disabled} className="switch" onClick={() => onChange(!checked)} />;
}

/** A whole number moved by one: `−` and `+`, bounded. */
export function Stepper({ value, onChange, min, max, label }: { value: number; onChange: (next: number) => void; min: number; max: number; label: string }) {
  return (
    <span className="step" role="group" aria-label={label}>
      <button type="button" aria-label="Fewer" disabled={value <= min} onClick={() => onChange(Math.max(min, value - 1))}>−</button>
      <b aria-live="polite">{value}</b>
      <button type="button" aria-label="More" disabled={value >= max} onClick={() => onChange(Math.min(max, value + 1))}>+</button>
    </span>
  );
}

/** A number on a range, saved when it is let go, not on every step of the drag. */
export function Slider({ value, onCommit, min, max, unit, label }: { value: number; onCommit: (next: number) => void; min: number; max: number; unit: string; label: string }) {
  const [held, setHeld] = useState(value);
  useEffect(() => setHeld(value), [value]);
  const commit = (): void => {
    if (held !== value) onCommit(held);
  };
  const at = ((held - min) / (max - min)) * 100;
  return (
    <div className="slider">
      <input
        type="range"
        aria-label={label}
        min={min}
        max={max}
        value={held}
        style={{ '--at': `${at}%` } as React.CSSProperties}
        onChange={(event) => setHeld(Number(event.currentTarget.value))}
        onPointerUp={commit}
        onKeyUp={commit}
        onBlur={commit}
      />
      <small>
        <span>{min}{unit}</span>
        <b>{W.selectedValue(held, unit)}</b>
        <span>{max}{unit}</span>
      </small>
    </div>
  );
}

export interface Choice<T extends string> {
  id: T;
  label: string;
  hint?: string;
}

/** One of a few named choices, in a menu: the current one is checked, and each says what it does. */
export function Select<T extends string>({ value, options, onChange, label, menuClassName = '', menuState, placeholder }: { value: T; options: readonly Choice<T>[]; onChange: (next: T) => void; label: string; placeholder?: string; menuClassName?: string; menuState?: { open: boolean; onOpenChange: (open: boolean) => void } }) {
  const current = options.find((option) => option.id === value);
  return (
    <Menu.Root modal={false} {...menuState}>
      <Menu.Trigger className="select" aria-label={label}>
        {current?.label ?? placeholder ?? value}
        <Chevron />
      </Menu.Trigger>
      <Menu.Portal>
        <Menu.Content className={`menu select-menu ${menuClassName}`} align="end" sideOffset={8} collisionPadding={8}>
          <Menu.RadioGroup value={value} onValueChange={(next) => onChange(next as T)}>
            {options.map((option) => (
              <Menu.RadioItem key={option.id} value={option.id} className="menu-item">
                <span>{option.label}</span>
                {option.hint === undefined ? null : <small>{option.hint}</small>}
                <Menu.ItemIndicator className="menu-check"><Check /></Menu.ItemIndicator>
              </Menu.RadioItem>
            ))}
          </Menu.RadioGroup>
        </Menu.Content>
      </Menu.Portal>
    </Menu.Root>
  );
}

/** A number typed in, saved when the field is left or Enter is pressed; anything that is not a whole number goes back to what it was. */
export function NumberInput({ value, onCommit, label, suffix, scale = 1 }: { value: number; onCommit: (next: number) => void; label: string; suffix?: string | null; scale?: number }) {
  const [text, setText] = useState(quantityText(value, scale));
  useEffect(() => setText(quantityText(value, scale)), [value, scale]);
  const commit = (): void => {
    const next = quantityValue(text, scale);
    if (next === null) setText(quantityText(value, scale));
    else if (next !== value) onCommit(next);
  };
  return (
    <span className="number">
      <input
        className="input"
        type="number"
        step={scale === 1 ? 1 : 'any'}
        inputMode={scale === 1 ? 'numeric' : 'decimal'}
        aria-label={label}
        value={text}
        onChange={(event) => setText(event.currentTarget.value)}
        onBlur={commit}
        onKeyDown={(event) => {
          if (event.key === 'Enter') event.currentTarget.blur();
        }}
      />
      {suffix === null || suffix === undefined ? null : <small>{suffix}</small>}
    </span>
  );
}

/** Changing the display unit does not write; leaving the numeric field writes the exact base value. */
export function QuantityInput({ value, onCommit, label, unit }: { value: number; onCommit: (next: number) => void; label: string; unit: 'ms' | 'bytes' }) {
  const units = quantityUnits(unit);
  const [scale, setScale] = useState<number>(() => [...units].reverse().find(choice => Math.abs(value) >= choice.factor)?.factor ?? 1);
  return <span className="quantity">
    <NumberInput value={value} label={label} scale={scale} onCommit={onCommit} />
    <Select label={W.unitFor(label)} value={String(scale)} options={units.map(choice => ({ id: String(choice.factor), label: choice.label }))} onChange={next => setScale(Number(next))} />
  </span>;
}

/** A single URL that wraps visually without adding newlines to the value sent on blur or Enter. */
export function UrlInput({ value, onCommit, label }: { value: string; onCommit: (next: string) => void; label: string }) {
  const [text, setText] = useState(value);
  useEffect(() => setText(value), [value]);
  return <textarea
    className="input wide"
    aria-label={label}
    inputMode="url"
    spellCheck={false}
    rows={2}
    style={{ fieldSizing: 'content', height: 'auto', maxWidth: '100%' }}
    value={text}
    onChange={event => setText(event.currentTarget.value.replace(/[\r\n]/g, ''))}
    onBlur={() => text !== value && onCommit(text)}
    onKeyDown={event => {
      if (event.key === 'Enter') {
        event.preventDefault();
        event.currentTarget.blur();
      }
    }}
  />;
}

/** A text value typed in, saved when the field is left or Enter is pressed. */
export function TextInput({ value, onCommit, label }: { value: string; onCommit: (next: string) => void; label: string }) {
  const [text, setText] = useState(value);
  useEffect(() => setText(value), [value]);
  return (
    <input
      className="input wide"
      aria-label={label}
      spellCheck={false}
      value={text}
      onChange={(event) => setText(event.currentTarget.value)}
      onBlur={() => text !== value && onCommit(text)}
      onKeyDown={(event) => {
        if (event.key === 'Enter') event.currentTarget.blur();
      }}
    />
  );
}
