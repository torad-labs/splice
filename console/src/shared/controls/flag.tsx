// A two-state printed mark: the world's checkbox.
//
// WHY IT EXISTS. The control set covered pressing, confirming, typing, waiting and failing, and not
// choosing or flagging - which is why the log tail still rendered a system-blue
// `<input type="checkbox">`, the browser's own chrome showing through the world (m1 design review
// B14 and D6). The world already has the shape a flag needs: the holder edge, printed word and all.
// "follow" becomes a key whose edge is green while it follows.
//
// THE PRINTED WORD IS THE STATE, not the action - "following" while it follows, "paused" while it
// does not - so the mark survives a grayscale screenshot and a colorblind reader. The green edge is
// the second signal, never the only one.
//
// It is a real button with `role="switch"` and `aria-checked`, so Enter and Space toggle it and a
// screen reader announces a switch rather than "button"; the native checkbox's job, in this world's
// body.
import { cx } from '@shared/lib';
import { HolderEdge } from '@shared/ui';

export function Flag({ on, onLabel, offLabel, onChange, disabled, ariaLabel, className }: {
  on: boolean;
  /** The word printed while the flag is on. */
  onLabel: string;
  /** The word printed while it is off. Never the same string as `onLabel`: a flag whose two states
   *  print the same word has no state a reader can see. */
  offLabel: string;
  onChange: (next: boolean) => void;
  disabled?: boolean | undefined;
  /** What the switch controls, when the printed state word alone does not say it ("on" names no
   *  thing). The state still reaches a screen reader through aria-checked. */
  ariaLabel?: string | undefined;
  className?: string;
}) {
  return (
    <button
      type="button"
      className={cx('myx-key', 'myx-flag', className)}
      role="switch"
      aria-checked={on}
      aria-label={ariaLabel}
      disabled={disabled === true}
      onClick={() => onChange(!on)}
    >
      <HolderEdge state={on ? 'green' : 'grey'} label={on ? onLabel : offLabel} />
    </button>
  );
}
