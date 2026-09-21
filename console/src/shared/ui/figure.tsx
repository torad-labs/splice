// A number that says what it is. value, unit and basis are all printed text:
// the basis is never implied by a color or a tooltip, because the operator's
// whole reason to be on this page is knowing whether a number can be trusted.
import type { Basis } from './types';

export function Figure({ value, unit, basis }: {
  value: string | number;
  unit?: string;
  basis: Basis;
}) {
  return (
    <span className="myx-fig">
      <span className="myx-fig-value">{value}</span>
      {unit ? <span className="myx-fig-unit">{unit}</span> : null}
      {/* A measured basis is the default and says nothing: printing it stamped the word "measured"
          beside every trustworthy number in the console and put it BETWEEN a value and its noun
          ("6 measured heads report none", m1 design review B5). StripField has suppressed it since
          M2-10's finding; the two primitives in one set agreed about the rule everywhere except
          here. A basis that is not the default is the thing worth printing. */}
      {basis !== 'measured' ? <span className="myx-fig-basis">{basis}</span> : null}
    </span>
  );
}
