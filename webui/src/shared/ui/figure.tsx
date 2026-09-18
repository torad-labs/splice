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
      <span className="myx-fig-basis">{basis}</span>
    </span>
  );
}
