// The world's button: a printed key.
//
// WHY THIS EXISTS AT ALL. The operator's ruling of 2026-09-18 is that the console still reads as
// the released Torad plate system, and the measured cause is not any one page: CONTRACTS.md
// section 2 had no button, so every new page reached for the old `Btn`, which renders with the old
// paper, vermilion and display face. A world without a key has to borrow one.
//
// THE KEY IS A STRIP THAT CAN BE PRESSED. Same paper, same hairline, same radius (0), same label
// face as every field on every page, and the focus ring is the world's `--focus` rather than a
// browser outline. Nothing here is a div with a click handler: it is a real button, with a real
// type, so Enter and Space work without a key handler of their own.
//
// NO BARE COLOURED EDGE. This key used to render an aria-hidden `<span class="myx-key-edge">` whose
// only content was a colour - a second implementation of the holder edge, printing no word, on the
// one control whose whole job is to be pressed (m1 design review D3). Attention in this world is a
// holder edge WITH a printed label, so the mark now belongs to the armed state alone and it arrives
// with the word: a plain key lifts its box line on hover and press (the same move a hovered strip
// makes), and only a cocked key carries an edge, which is what makes the two states tellable apart
// under the pointer that just clicked one (D1).
import type { ReactNode } from 'react';
import { cx } from '@shared/lib';
import { HolderEdge } from '@shared/ui';
import { S } from './strings';

export type KeyVariant = 'plain' | 'armed';

export function Key({ children, onClick, variant = 'plain', type = 'button', disabled, busy, ariaLabel, className }: {
  children: ReactNode;
  onClick?: () => void;
  /** `armed` is the cocked half of a two-step key: the lock is amber, it prints its own word, and
   *  the paper darkens one step, which is how the world says "this one is about to do something". */
  variant?: KeyVariant;
  type?: 'button' | 'submit';
  /** `| undefined` on the optional flags, because this tree runs exactOptionalPropertyTypes and a
   *  composing control must be able to forward its own optional prop through. */
  disabled?: boolean | undefined;
  busy?: boolean | undefined;
  ariaLabel?: string | undefined;
  className?: string | undefined;
}) {
  const cocked = variant === 'armed';
  // Busy is NOT disabled (D4): a disabled control leaves the tab order, so a key that was working
  // when the operator tabbed onto it would drop their focus to the body mid-interaction. A busy key
  // keeps focus, announces itself, ignores the click, and PRINTS that it is working - the old one
  // drew nothing at all, so a working key and a dead key looked identical.
  const working = busy === true;
  return (
    <button
      type={type}
      className={cx('myx-key', cocked && 'myx-key-armed', working && 'myx-key-working', className)}
      onClick={working ? undefined : onClick}
      disabled={disabled === true}
      aria-disabled={working ? true : undefined}
      aria-busy={working ? true : undefined}
      aria-label={ariaLabel}
    >
      {cocked ? <HolderEdge state="amber" label={S.armed} /> : null}
      <span className="myx-key-label">{children}</span>
      {working ? <span className="myx-key-busy">{S.busy}</span> : null}
    </button>
  );
}
