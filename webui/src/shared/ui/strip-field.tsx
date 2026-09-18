// A fixed-width boxed field inside a strip. Width is a ch count so the field
// grid stays aligned column to column at every breakpoint (the strip scrolls,
// it never reflows). The value is clipped, never wrapped: a wrapped value makes
// one strip taller than its neighbours and the rack stops reading as a rack.
import type { CSSProperties } from 'react';
import { cx } from '../lib';
import type { Basis } from './types';

export function StripField({ w, span, fixed, label, value, basis, mono }: {
  w: number;
  /** THIS CELL DOES NOT TAKE A SHARE OF THE SLACK, so the rack it is in is a FIXED table rather
   *  than a fluid one (M1-107). It exists because flex-grow is set INLINE here, and an inline
   *  declaration beats any stylesheet rule -- so a page-level class CANNOT express "this rack is
   *  fixed" while this file owns the property. That is M1-93's finding read forwards: M1-93 proved
   *  an always-overridden stylesheet rule is dead, and the same fact means the declaration has to
   *  arrive as a value the primitive writes rather than a rule it loses to.
   *  The strip's own class (`myx-strip-fixed`, read in accounts.css) stays as the DECLARATION the
   *  DOM carries -- the M1-92 idiom, where a screenshot cannot carry intent so the markup says
   *  it -- and this prop is that sentence made true. */
  fixed?: boolean;
  /** THE TRACKS THIS FIELD SPANS, DECLARED. A total is one value stated across the whole row; a
   *  reason is one sentence across four cells. Both are spans, and a span is not a first field
   *  that happens to be wide -- so it says so, and anything comparing FIRST-FIELD EDGES across a
   *  rack can exclude it BY DECLARATION rather than by happening not to look (M1-73). Omitted
   *  means one track, which is what almost every field is. */
  span?: number;
  /** Omit inside a bay whose head prints the column names once (m1 design review B9): a rack of
   *  homogeneous rows prints its columns on the rack, not on every slip. The cell is then one
   *  line, which is also what a compact rack (the activity feed) needs. */
  label?: string;
  value: string | number;
  basis?: Basis;
  mono?: boolean;
}) {
  // The figure face is the default: since M1-17 that is the label face with
  // tabular figures, because the comp's own figures advance 0.389em and no
  // monospace is that narrow. mono={false} is the escape hatch for a field whose
  // value is prose rather than a figure.
  const figure = mono !== false;
  return (
    // ---- flex-grow IS THE FIELD'S OWN ch, AND THAT IS WHAT MAKES THE GRID A GRID (M1-73) --------
    // `width` alone is the DECLARED box. Nine page sheets then add M1-39's strip-fills-its-bay
    // rule (`{ width: 100% }` on the strip, `{ flex: 1 1 auto }` on the cells) so the rack reaches
    // the right edge of its bay instead of ending in a slab of bare paper -- and `flex: 1 1 auto`
    // sets flex-grow: 1, which shares the LEFTOVER EQUALLY PER CELL. Equal per cell is only equal
    // per track when every row has the SAME NUMBER OF CELLS, so a rack with more than one row
    // shape cannot hold a grid, by construction.
    //
    // MEASURED at 1536 on the two pages that have more than one shape: compaction renders four
    // shapes in one bay (total 1 field, outcome 2, event 5, opened 3) and its first field edge
    // spans 384px across 14 strips (x 762..1146); mcp renders two (5 and 2) and spans 58px across
    // 18 (x 488..546). Both pages carry the identical two-line rule. It is not their defect.
    //
    // With flex-grow = w, a cell's width becomes ch_i + slack x ch_i/sum(ch): the DECLARED GRID,
    // SCALED, and identical in every row shape. A field that spans four tracks already declares
    // four tracks' ch (mcp's reason is NARROW x 4), so it takes four tracks' share with no extra
    // mechanism -- which is the test that this is the right abstraction rather than a patch.
    // It is set INLINE because a cell's ch count is not knowable from the stylesheet, and inline
    // beats the page rule's shorthand, so no page sheet has to change and the nine redundant
    // `flex: 1 1 auto` lines can be retired as cleanup rather than as part of this fix.
    <div
      className="myx-sfield"
      style={{ width: `${w}ch`, flexGrow: fixed === true ? 0 : w } as CSSProperties}
      {...(span === undefined ? {} : { 'data-span': String(span) })}
    >
      {label !== undefined ? <span className="myx-sfield-label">{label}</span> : null}
      <span className={cx('myx-sfield-value', figure && 'myx-sfield-figure')}>
        <span className="myx-sfield-text">{value}</span>
        {basis && basis !== 'measured' ? <span className="myx-sfield-basis">{basis}</span> : null}
      </span>
    </div>
  );
}
