// The two walls of row M1-04, as one public surface: a page's `coverage.ts`
// imports `Disposition` from `@shared/coverage`, and the walls themselves import
// the checkers. `baseline.ts` is deliberately NOT re-exported — the coverage
// wall reads it as the overridable baseline, never as a page declaration.
export { checkCoverage } from './checks';
export type { CoverageFinding, CoverageProblem, Disposition, DispositionSource } from './checks';
export { checkLabels } from './labels';
export type { LabelFinding, LabelProblem } from './labels';
