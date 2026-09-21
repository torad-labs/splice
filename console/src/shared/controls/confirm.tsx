// The inline two-step, for the actions the brief does not allow a dialog for: "destructive or
// restart actions confirm inline on the strip, never in a dialog".
//
// THE SHAPE IS TWO KEYS, NOT A MODAL. The first press cocks the key into its armed state and the
// second does the thing; a cancel key sits beside it while armed.
//
// WHY IT DISARMS, WHICH IS THE WHOLE SAFETY. It used to disarm on a four-second clock. The failure
// that hid inside that: an operator who reads the second label first, then clicks deliberately at
// 4.1s, lands on a key that has quietly re-armed - "I clicked confirm and nothing happened", on a
// daemon restart (m1 design review D5). A clock is not a statement about the operator's intent; the
// gestures that ARE are the ones this control now listens for, and every one of them means "moved
// on": focus left the pair, Escape, or a click anywhere outside it. Nothing is left armed by
// inaction, nothing re-arms under a considered second click, and there is no timer to tune.
//
// WHY THE ARMED STATE IS VISIBLE UNDER THE POINTER. Hover and armed used to be the same two
// declarations - amber edge, box line in the strip's ink - so the operator whose pointer was still
// on the key they had just clicked could not see that it armed (D1, and the reason this row exists).
// Hover now lifts the box line alone and the armed state is the only one carrying a holder edge,
// with its word and the cancel key beside it: three channels hover does not use.
import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Key } from './key';
import { S } from './strings';

export function ConfirmKeys({ label, confirmLabel, armed, busy, onArm, onConfirm, onCancel }: {
  label: ReactNode;
  /** What the key prints once it is armed. The second label is the whole point of the gesture: the
   *  confirmation is a word, not a dialog. */
  confirmLabel: ReactNode;
  armed: boolean;
  busy?: boolean | undefined;
  onArm: () => void;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  if (!armed) {
    return (
      <Key variant="plain" onClick={onArm} busy={busy}>
        {label}
      </Key>
    );
  }
  return (
    <span className="myx-confirm">
      <Key variant="armed" onClick={onConfirm} busy={busy} ariaLabel={typeof confirmLabel === 'string' ? confirmLabel : undefined}>
        {confirmLabel}
      </Key>
      <Key variant="plain" onClick={onCancel} disabled={busy} ariaLabel={S.cancel}>
        {S.cancel}
      </Key>
    </span>
  );
}

export function Confirm({ label, confirmLabel, onConfirm, busy }: {
  label: ReactNode;
  confirmLabel: ReactNode;
  onConfirm: () => void;
  busy?: boolean | undefined;
}) {
  const [armed, setArmed] = useState(false);
  const root = useRef<HTMLSpanElement>(null);

  useEffect(() => {
    if (!armed) return;
    const away = (event: PointerEvent) => {
      if (root.current !== null && !root.current.contains(event.target as Node)) setArmed(false);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setArmed(false);
    };
    document.addEventListener('pointerdown', away);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('pointerdown', away);
      document.removeEventListener('keydown', escape);
    };
  }, [armed]);

  return (
    // The wrapper is always rendered, armed or not, so the control's own DOM does not change shape
    // under the operator's pointer; it draws nothing (inline-flex, no box of its own).
    <span
      className="myx-confirm-hold"
      ref={root}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) setArmed(false);
      }}
    >
      <ConfirmKeys
        label={label}
        confirmLabel={confirmLabel}
        armed={armed}
        busy={busy}
        onArm={() => setArmed(true)}
        onConfirm={() => {
          setArmed(false);
          onConfirm();
        }}
        onCancel={() => setArmed(false)}
      />
    </span>
  );
}
