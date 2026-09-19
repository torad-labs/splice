// The loading state: a bay whose strips have not printed yet.
//
// NOT A SKELETON. A shimmer, a grey block or a pulsing bar says "something is happening here" in a
// language this world does not speak - the room is paper and ink, and paper has no shimmer. So the
// blank is the strip module itself, unprinted: the same paper, the same hairline, the same height
// as a strip, and no ink at all. The page keeps its shape while it loads, which is what a skeleton
// is for, without borrowing another product's vocabulary to do it.
//
// THE HEIGHT IS THE CELL'S OWN, NOT A SUM OF FONT SIZES (m1 design review D7). It used to be
// `calc(4 + 4 + 12 + 2 + 16)`, which describes the field's font sizes and misses the fact that a
// line box is taller than the text in it: the blank rack measured 38px against the 52px strip it
// stood in for, so every page jumped when data landed. It now renders the same structure the strip
// renders - the strip's box, its fields row, one cell, an unprinted label line and an unprinted
// value line - so the two are the same height by construction and can never drift apart again. The
// characters that hold those two lines open are non-breaking spaces: they print nothing.
import { cx } from '@shared/lib';

/** Holds a line box open without printing anything: a non-breaking space is not collapsible,
 *  so the line box survives, and it is invisible, so nothing is printed. */
const BLANK_LINE = '\u00a0';

export function Blank({ strips, label, className }: {
  strips: number;
  /** What is loading, for a reader who cannot see the shape. */
  label?: string;
  className?: string;
}) {
  const count = Math.max(0, Math.floor(strips));
  return (
    <div className={cx('myx-blank', className)} aria-busy="true" aria-label={label} role="status">
      {Array.from({ length: count }, (_, index) => (
        <span className="myx-strip myx-blank-strip" key={index} aria-hidden="true">
          <span className="myx-strip-fields">
            <span className="myx-sfield">
              <span className="myx-sfield-label">{BLANK_LINE}</span>
              <span className="myx-sfield-value">
                <span className="myx-sfield-text">{BLANK_LINE}</span>
              </span>
            </span>
          </span>
        </span>
      ))}
    </div>
  );
}
