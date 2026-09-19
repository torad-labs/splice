// Choosing one of a list: the world's select.
//
// WHY IT EXISTS. Four `<select>`s on Logs and a system-blue checkbox in the log tail were the last
// browser chrome in the console (m1 design review B14); the set had no control for choosing, so the
// pages had nowhere to go but the OS. `appearance: none` would not have fixed it either: a native
// select's POPUP is still the OS's window, with the OS's font, its own scrollbar and its own idea
// of a hover. The control has to own the list.
//
// SO IT OWNS A PRINTED RACK, NOT A POPUP. The box prints the current value; the options print
// underneath it as a bay of one-field strips, in flow. There is no absolute layer, no z-index and
// nothing to dismiss by clicking the scrim - the world carries no stack ladder, and a list printed
// in place cannot land off-screen or be clipped by the bay it opens inside. The chosen option is
// marked the way the comp marks a selected strip: its box line is drawn in the strip's ink rather
// than the field's grey, which survives greyscale, and it carries aria-selected for a reader who
// cannot see the line at all.
//
// KEYBOARD IS THE NATIVE SELECT'S: Enter, Space, ArrowDown and ArrowUp open it; the arrows move the
// active option with wrap-around; Home and End jump; Enter and Space take the active one; Escape
// closes and puts focus back on the box; Tab closes and moves on; a printable character jumps to
// the first option whose text starts with what has been typed.
import { useEffect, useRef, useState } from 'react';
import type { KeyboardEvent as ReactKeyboardEvent } from 'react';
import { cx } from '@shared/lib';
import { HolderEdge } from '@shared/ui';
import { S } from './strings';

export type ChoiceOption = { value: string; label: string };

/** How long a typed prefix is kept before type-ahead starts over. */
const TYPEAHEAD_MS = 800;

/** The rack of printed options on its own, so a static render can show the open state (the same
 *  split ConfirmKeys makes for the armed state: a test cannot click, and the open list's markup is
 *  exactly what a reviewer needs to see). */
export function ChoiceList({ label, value, options, active, onPick, listId }: {
  label: string;
  value: string;
  options: readonly ChoiceOption[];
  active: number;
  onPick: (next: string) => void;
  /** `| undefined` because Choice forwards its own optional id through, and this tree runs
   *  exactOptionalPropertyTypes. */
  listId?: string | undefined;
}) {
  return (
    <ul className="myx-choice-options" id={listId} role="listbox" aria-label={label}>
      {options.map((option, at) => (
        <li key={option.value} className="myx-choice-option-line" role="none">
          <button
            type="button"
            className={cx(
              'myx-choice-option',
              option.value === value && 'myx-choice-chosen',
              at === active && 'myx-choice-active',
            )}
            role="option"
            aria-selected={option.value === value}
            onClick={() => onPick(option.value)}
          >
            {/* A chosen option says so in a word as well as in its box line: the ink line is the
                comp's own mark for a selected strip, and the word is what a reader who cannot see
                the line is owed. */}
            {option.value === value ? <HolderEdge state="green" label={S.chosen} /> : null}
            <span className="myx-choice-option-label">{option.label}</span>
          </button>
        </li>
      ))}
    </ul>
  );
}

export function Choice({ label, value, options, onChange, w = 18, id, disabled, className }: {
  label: string;
  value: string;
  options: readonly ChoiceOption[];
  onChange: (next: string) => void;
  /** The box's width in `ch`, as every field's is. */
  w?: number;
  id?: string;
  disabled?: boolean;
  className?: string;
}) {
  const [open, setOpen] = useState(false);
  const index = (at: string) => Math.max(0, options.findIndex((option) => option.value === at));
  const [active, setActive] = useState(() => index(value));
  const root = useRef<HTMLDivElement>(null);
  const box = useRef<HTMLButtonElement>(null);
  const typed = useRef({ text: '', at: 0 });

  const chosen = options.find((option) => option.value === value);

  const close = (returnFocus: boolean) => {
    setOpen(false);
    if (returnFocus) box.current?.focus();
  };

  const take = (next: string) => {
    onChange(next);
    setActive(index(next));
    close(true);
  };

  // The two ways out that are not a key on this control: the operator clicked somewhere else, or
  // pressed Escape while focus had already moved. Both mean "moved on", and both are what the
  // native select does. (A timer was the old way out; see confirm.tsx for why it is gone.)
  useEffect(() => {
    if (!open) return;
    const away = (event: PointerEvent) => {
      if (root.current !== null && !root.current.contains(event.target as Node)) setOpen(false);
    };
    const escape = (event: globalThis.KeyboardEvent) => {
      if (event.key === 'Escape') close(true);
    };
    document.addEventListener('pointerdown', away);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('pointerdown', away);
      document.removeEventListener('keydown', escape);
    };
  }, [open]);

  const step = (by: number) => {
    if (options.length === 0) return;
    setActive((at) => (at + by + options.length) % options.length);
  };

  const jump = (key: string) => {
    const now = Date.now();
    const keep = now - typed.current.at < TYPEAHEAD_MS ? typed.current.text : '';
    const text = (keep + key).toLowerCase();
    typed.current = { text, at: now };
    const found = options.findIndex((option) => option.label.toLowerCase().startsWith(text));
    if (found >= 0) setActive(found);
  };

  const onBoxKey = (event: ReactKeyboardEvent<HTMLButtonElement>) => {
    const { key } = event;
    if (key === 'Enter' || key === ' ' || key === 'ArrowDown' || key === 'ArrowUp') {
      event.preventDefault();
      if (!open) {
        setOpen(true);
        setActive(index(value));
        return;
      }
      if (key === 'ArrowDown') step(1);
      else if (key === 'ArrowUp') step(-1);
      else if (options[active] !== undefined) take(options[active].value);
      return;
    }
    if (!open) return;
    if (key === 'Escape') {
      event.preventDefault();
      close(true);
    } else if (key === 'Tab') {
      close(false);
    } else if (key === 'Home') {
      event.preventDefault();
      setActive(0);
    } else if (key === 'End') {
      event.preventDefault();
      setActive(options.length - 1);
    } else if (key.length === 1 && !event.metaKey && !event.ctrlKey && !event.altKey) {
      jump(key);
    }
  };

  return (
    <div
      className={cx('myx-choice', className)}
      ref={root}
      onBlur={(event) => {
        // Focus leaving the control entirely is the third way out: "moved on".
        if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false);
      }}
    >
      <span className="myx-choice-label" id={id === undefined ? undefined : `${id}-label`}>
        {label}
      </span>
      <button
        ref={box}
        type="button"
        id={id}
        className={cx('myx-choice-box', open && 'myx-choice-open')}
        style={{ width: `${w}ch` }}
        role="combobox"
        aria-expanded={open}
        aria-controls={id === undefined ? undefined : `${id}-options`}
        aria-labelledby={id === undefined ? undefined : `${id}-label`}
        aria-haspopup="listbox"
        disabled={disabled === true}
        onClick={() => (open ? close(true) : setOpen(true))}
        onKeyDown={onBoxKey}
      >
        <span className="myx-choice-value">{chosen === undefined ? value : chosen.label}</span>
        {/* The mark is printed, not drawn: the box says which way it opens in a word. No chevron
            icon - the world has no icon set, and a glyph standing in for one is a costume. */}
        <span className="myx-choice-state">{open ? S.close : S.open}</span>
      </button>
      {open ? (
        <ChoiceList
          label={label}
          value={value}
          options={options}
          active={active}
          onPick={take}
          listId={id === undefined ? undefined : `${id}-options`}
        />
      ) : null}
    </div>
  );
}
