// The inline two-step, for the actions the brief does not allow a dialog for: "destructive or
// restart actions confirm inline on the strip, never in a dialog".
//
// THE SHAPE IS TWO KEYS, NOT A MODAL. The first press cocks the key into its armed state and the
// second does the thing; a cancel key sits beside it while armed. Arming times out on its own,
// because a key left armed is a hazard the operator did not choose to keep.
//
// The state is split from the markup on purpose (ConfirmKeys takes `armed` as a prop): a static
// render cannot click, and an armed key's markup is exactly what a reviewer needs to see.
import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Key } from './key';
import { S } from './strings';

/** How long an armed key stays armed before it disarms itself. */
export const ARM_MS = 4_000;

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
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const disarm = () => {
    setArmed(false);
    if (timer.current !== null) {
      clearTimeout(timer.current);
      timer.current = null;
    }
  };

  useEffect(() => () => {
    if (timer.current !== null) clearTimeout(timer.current);
  }, []);

  return (
    <ConfirmKeys
      label={label}
      confirmLabel={confirmLabel}
      armed={armed}
      busy={busy}
      onArm={() => {
        setArmed(true);
        timer.current = setTimeout(() => setArmed(false), ARM_MS);
      }}
      onConfirm={() => {
        disarm();
        onConfirm();
      }}
      onCancel={disarm}
    />
  );
}
