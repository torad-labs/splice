// The holder edge: the one place attention is allowed to be a color. It always
// prints its label beside the mark, so the state survives a grayscale screenshot
// and a colorblind reader. Used standalone in rail tabs and rule cells, and as
// the left column of every Strip.
import { cx } from '../lib';
import type { Edge } from './types';

export function HolderEdge({ state, label }: { state: Edge; label: string }) {
  return (
    <span className={cx('myx-edge', `myx-edge-${state}`)}>
      <span className="myx-edge-mark" aria-hidden="true" />
      <span className="myx-edge-label">{label}</span>
    </span>
  );
}
