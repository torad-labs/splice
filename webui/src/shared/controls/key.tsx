// The world's button: a printed key.
//
// WHY THIS EXISTS AT ALL. The operator's ruling of 2026-09-18 is that the console still reads as
// the released Torad plate system, and the measured cause is not any one page: CONTRACTS.md
// section 2 had no button, so every new page reached for the old `Btn`, which renders with the old
// paper, vermilion and display face. A world without a key has to borrow one.
//
// THE KEY IS A STRIP THAT CAN BE PRESSED. Same paper, same hairline, same radius (0), same label
// face as every field on every page; the holder edge on its left is the one place attention is a
// colour, and it says what the key is doing: grey at rest, amber while the pointer is over it or
// while it is held down, and the focus ring is the world's `--focus` rather than a browser outline.
// Nothing here is a div with a click handler: it is a real button, with a real type, so Enter and
// Space work without a key handler of their own.
import type { ReactNode } from 'react';
import { cx } from '@shared/lib';

export type KeyVariant = 'plain' | 'armed';

export function Key({ children, onClick, variant = 'plain', type = 'button', disabled, busy, ariaLabel, className }: {
  children: ReactNode;
  onClick?: () => void;
  /** `armed` is the cocked half of a two-step key: the edge stays amber and the paper darkens one
   *  step, which is how the world says "this one is about to do something". */
  variant?: KeyVariant;
  type?: 'button' | 'submit';
  /** `| undefined` on the optional flags, because this tree runs exactOptionalPropertyTypes and a
   *  composing control must be able to forward its own optional prop through. */
  disabled?: boolean | undefined;
  busy?: boolean | undefined;
  ariaLabel?: string | undefined;
  className?: string | undefined;
}) {
  return (
    <button
      type={type}
      className={cx('myx-key', variant === 'armed' && 'myx-key-armed', className)}
      onClick={onClick}
      disabled={disabled === true || busy === true}
      aria-busy={busy === true ? true : undefined}
      aria-label={ariaLabel}
    >
      <span className="myx-key-edge" aria-hidden="true" />
      <span className="myx-key-label">{children}</span>
    </button>
  );
}
