// The coverage wall's checker: every key the denominator enumerates carries one
// disposition, and a key with none fails BY NAME. The denominator itself comes
// from the source (denominator.ts); this module only judges declarations.
import { servedBy } from './denominator';

/** The manifest entry a page's `coverage.ts` and the baseline both export. */
export type Disposition = {
  readonly kind: 'knob' | 'topology' | 'route' | 'verb';
  readonly name: string;
  readonly disposition: 'editable' | 'read-only' | 'excluded' | 'pending';
  /** Required on a `pending` entry: the row that will cover the name. */
  readonly where?: string;
  /** Required on an `excluded` entry: why the console never shows it. */
  readonly reason?: string;
  /** Required on a `verb` the console covers (editable or read-only): what the operator does on the
   *  declaring page that the CLI verb does (V4-220). */
  readonly action?: string;
  /** Required with `action`: the route that action calls. The daemon must serve it and its own
   *  disposition must cover the verb: editable for an editable verb, either for a read-only one. */
  readonly via?: string;
};

/**
 * One file's declarations. `baseline` marks `src/shared/coverage/baseline.ts`
 * — the file a page declaration overrides, and the only source that may be
 * overridden without a conflict.
 */
export type DispositionSource = {
  readonly source: string;
  readonly baseline?: boolean;
  readonly dispositions: readonly Disposition[];
};

export type CoverageProblem =
  | 'no disposition'
  | 'two page dispositions'
  | 'excluded without reason'
  | 'pending without where'
  | 'pending but served'
  | 'served without disposition'
  | 'verb without action'
  | 'verb via uncovered route';

export type CoverageFinding = {
  readonly name: string;
  readonly problem: CoverageProblem;
};

function isBlank(value: string | undefined): boolean {
  return value === undefined || value.trim() === '';
}

/**
 * A CLI verb the console claims to cover names the action and the route it calls, and that route is
 * one the daemon serves and the console covers at least as far: a verb marked editable through a
 * read-only route claims a write the page cannot make.
 */
function verbProblem(
  declared: Disposition,
  effective: ReadonlyMap<string, Disposition>,
  served: readonly string[],
): CoverageProblem | null {
  if (declared.kind !== 'verb') return null;
  if (declared.disposition !== 'editable' && declared.disposition !== 'read-only') return null;
  if (isBlank(declared.action) || isBlank(declared.via)) return 'verb without action';
  const via = declared.via ?? '';
  const route = effective.get(via);
  const covers =
    route?.kind === 'route' &&
    (route.disposition === 'editable' || (route.disposition === 'read-only' && declared.disposition === 'read-only'));
  const isServed = served.some((registration) => servedBy(registration, via));
  return covers && isServed ? null : 'verb via uncovered route';
}

/**
 * The ways a manifest can fail. Every finding names the key, so the wall reports by name rather
 * than by count.
 *
 * `served` is the routes the daemon registers (denominator.ts parseServedRoutes). Two problems read
 * it: a route the daemon serves that no disposition names, and a disposition still `pending` (the
 * page overrides the baseline) for a route the daemon serves, which is a manifest describing a
 * daemon that no longer exists. A third, for a CLI verb: the route its console action calls must be
 * served (verbProblem).
 */
export function checkCoverage(
  denominator: readonly string[],
  dispositions: readonly DispositionSource[],
  served: readonly string[] = [],
): CoverageFinding[] {
  const findings: CoverageFinding[] = [];
  const baseline = new Set<string>();
  const pages = new Map<string, number>();
  const effective = new Map<string, Disposition>();

  for (const source of dispositions) {
    for (const declared of source.dispositions) {
      if (source.baseline === true) baseline.add(declared.name);
      else pages.set(declared.name, (pages.get(declared.name) ?? 0) + 1);
      if (source.baseline !== true || !effective.has(declared.name)) effective.set(declared.name, declared);

      if (declared.disposition === 'excluded' && isBlank(declared.reason)) {
        findings.push({ name: declared.name, problem: 'excluded without reason' });
      }
      if (declared.disposition === 'pending' && isBlank(declared.where)) {
        findings.push({ name: declared.name, problem: 'pending without where' });
      }
    }
  }

  for (const name of new Set(denominator)) {
    const declaredByPages = pages.get(name) ?? 0;
    if (declaredByPages === 0 && !baseline.has(name)) {
      findings.push({ name, problem: 'no disposition' });
    } else if (declaredByPages > 1) {
      findings.push({ name, problem: 'two page dispositions' });
    }
  }

  const names = [...effective.keys()];
  for (const registration of new Set(served)) {
    if (!names.some((name) => servedBy(registration, name))) {
      findings.push({ name: registration, problem: 'served without disposition' });
    }
  }
  for (const [name, declared] of effective) {
    if (declared.disposition === 'pending' && served.some((registration) => servedBy(registration, name))) {
      findings.push({ name, problem: 'pending but served' });
    }
  }
  for (const [name, declared] of effective) {
    const problem = verbProblem(declared, effective, served);
    if (problem !== null) findings.push({ name, problem });
  }

  return findings.sort((left, right) => {
    if (left.name !== right.name) return left.name < right.name ? -1 : 1;
    return left.problem < right.problem ? -1 : 1;
  });
}
