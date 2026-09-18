// The loading state: a bay whose strips have not printed yet.
//
// NOT A SKELETON. A shimmer, a grey block or a pulsing bar says "something is happening here" in a
// language this world does not speak - the room is paper and ink, and paper has no shimmer. So the
// blank is the strip module itself, unprinted: the same paper, the same hairline, the same height
// as a strip with one field row, and no ink at all. The page keeps its shape while it loads, which
// is what a skeleton is for, without borrowing another product's vocabulary to do it.
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
      {Array.from({ length: count }, (_, index) => (
        <span className="myx-blank-strip" key={index} aria-hidden="true" />
      ))}
    </div>
  );
}
