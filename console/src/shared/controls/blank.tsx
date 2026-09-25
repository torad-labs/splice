// The loading state: the rows a list will hold, as quiet bars until they land.
//
// A SHAPE, NEVER A ROW THAT LOST ITS WORDS. The blank used to be the strip module unprinted: the
// strip's hairline box, its fields row, a label line and a value line held open by non-breaking
// spaces. With the strip world retired that shell drew as bordered cards with a dot and a rule and
// nothing in them, and the doctor page caught mid-read looked like four findings with their names
// missing (console baseline 2026-09-25, doctor at all three frames). A bar has no border, no mark
// and no rule, so nothing on it can be read as a row, and it breathes so the page says it is
// reading rather than empty.
//
// ONE BAR PER ROW, AT A ROW'S HEIGHT (--row-board), so the page keeps its shape and does not jump
// when the rows land.
import { cx } from '@shared/lib';

export function Blank({ strips, label, className }: {
  strips: number;
  /** What is loading, for a reader who cannot see the shape. */
  label?: string;
  className?: string;
}) {
  const count = Math.max(0, Math.floor(strips));
  return (
    <div className={cx('myx-blank', className)} aria-busy="true" aria-label={label} role="status">
      {Array.from({ length: count }, (_, index) => <span className="myx-blank-row" key={index} aria-hidden="true" />)}
    </div>
  );
}
