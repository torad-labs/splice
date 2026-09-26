// The coverage wall's checker: every key the denominator enumerates carries one
// disposition, and a key with none fails BY NAME. The denominator itself comes
// from the source (denominator.ts); this module only judges declarations.
import { servedBy } from './denominator';
import type { PageAction } from './jobs';

/** The manifest entry a page's `coverage.ts` and the baseline both export. */
export type Disposition = {
  /** `verb`: a `splice` CLI verb (CommandParser.kt), V4-219: every CLI capability has a console answer. */
  readonly kind: 'knob' | 'topology' | 'route' | 'verb';
  readonly name: string;
  readonly disposition: 'editable' | 'read-only' | 'excluded' | 'pending';
  /** Required on a `pending` entry: the row that will cover the name. */
  readonly where?: string;
  /** Required on an `excluded` entry: why the console never shows it. */
  readonly reason?: string;
  /** On a `verb`, V4-220: the declaring page's job action that does what the verb does, by its name.
   *  Required when the verb is editable; a pending verb names the action it waits for. */
  readonly action?: string;
  /** On a `verb`: the route that action calls. A covered verb's route must be served, and its own
   *  disposition must cover the verb (editable for an editable verb). */
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
  /** The page's job actions (jobs.ts), which a verb's `action` names. */
  readonly actions?: readonly PageAction[];
};

export type CoverageProblem =
  | 'no disposition'
  | 'two page dispositions'
  | 'excluded without reason'
  | 'pending without where'
  | 'pending but served'
  | 'served without disposition'
  | 'verb without action'
  | 'verb action not built'
  | 'verb via uncovered route'
  | 'pending but built';

export type CoverageFinding = {
  readonly name: string;
  readonly problem: CoverageProblem;
};

function isBlank(value: string | undefined): boolean {
  return value === undefined || value.trim() === '';
}

/** Whether a verb's `via` is a route the daemon serves and the console covers at least as far as the
 *  verb: a verb marked editable through a read-only route claims a write the page cannot make. */
function viaCovers(declared: Disposition, effective: ReadonlyMap<string, Disposition>, served: readonly string[]): boolean {
  if (isBlank(declared.via)) return false;
  const via = declared.via ?? '';
  const route = effective.get(via);
  const covers =
    route?.kind === 'route' &&
    (route.disposition === 'editable' || (route.disposition === 'read-only' && declared.disposition !== 'editable'));
  return covers && served.some((registration) => servedBy(registration, via));
}

/**
 * A CLI verb's answer, V4-220. An editable verb names a page action that is built (its job action
 * carries no row) and a route that covers it; a read-only verb names the route its page reads, and
 * when it names an action too, that action must be built (V4-239): a covered verb whose page still
 * waits on a row claims an answer nobody can reach. A pending verb whose page action is built is
 * answered, and is marked so, as a served route is.
 */
function verbProblems(
  declared: Disposition,
  actions: readonly PageAction[] | undefined,
  effective: ReadonlyMap<string, Disposition>,
  served: readonly string[],
): CoverageProblem[] {
  const action = actions?.find((candidate) => candidate.name === declared.action);
  if (declared.disposition === 'pending') return action !== undefined && action.row === undefined ? ['pending but built'] : [];
  if (declared.disposition !== 'editable' && declared.disposition !== 'read-only') return [];
  const problems: CoverageProblem[] = [];
  const needsAction = declared.disposition === 'editable' || declared.action !== undefined;
  if (needsAction && action === undefined) problems.push('verb without action');
  if (action?.row !== undefined) problems.push('verb action not built');
  if (!viaCovers(declared, effective, served)) problems.push('verb via uncovered route');
  return problems;
}

/**
 * The ways a manifest can fail. Every finding names the key, so the wall reports by name rather
 * than by count.
 *
 * `served` is the routes the daemon registers (denominator.ts parseServedRoutes). Two problems read
 * it: a route the daemon serves that no disposition names, and a disposition still `pending` (the
 * page overrides the baseline) for a route the daemon serves, which is a manifest describing a
 * daemon that no longer exists. A verb's `via` must be served too (verbProblems).
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
  const actionsOf = new Map<string, readonly PageAction[] | undefined>();

  for (const source of dispositions) {
    for (const declared of source.dispositions) {
      if (source.baseline === true) baseline.add(declared.name);
      else pages.set(declared.name, (pages.get(declared.name) ?? 0) + 1);
      if (source.baseline !== true || !effective.has(declared.name)) {
        effective.set(declared.name, declared);
        actionsOf.set(declared.name, source.actions);
      }

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
    if (declared.kind === 'verb') {
      for (const problem of verbProblems(declared, actionsOf.get(name), effective, served)) findings.push({ name, problem });
    }
  }

  return findings.sort((left, right) => {
    if (left.name !== right.name) return left.name < right.name ? -1 : 1;
    return left.problem < right.problem ? -1 : 1;
  });
}
