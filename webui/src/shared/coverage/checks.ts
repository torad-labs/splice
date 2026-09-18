// The coverage wall's checker: every key the denominator enumerates carries one
// disposition, and a key with none fails BY NAME. The denominator itself comes
// from the source (denominator.ts); this module only judges declarations.

/** The manifest entry a page's `coverage.ts` and the baseline both export. */
export type Disposition = {
  readonly kind: 'knob' | 'topology' | 'route';
  readonly name: string;
  readonly disposition: 'editable' | 'read-only' | 'excluded' | 'pending';
  /** Required on a `pending` entry: the row that will cover the name. */
  readonly where?: string;
  /** Required on an `excluded` entry: why the console never shows it. */
  readonly reason?: string;
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
  | 'pending without where';

export type CoverageFinding = {
  readonly name: string;
  readonly problem: CoverageProblem;
};

function isBlank(value: string | undefined): boolean {
  return value === undefined || value.trim() === '';
}

/**
 * The four ways a manifest can fail. Every finding names the key, so the wall
 * reports by name rather than by count.
 */
export function checkCoverage(
  denominator: readonly string[],
  dispositions: readonly DispositionSource[],
): CoverageFinding[] {
  const findings: CoverageFinding[] = [];
  const baseline = new Set<string>();
  const pages = new Map<string, number>();

  for (const source of dispositions) {
    for (const declared of source.dispositions) {
      if (source.baseline === true) baseline.add(declared.name);
      else pages.set(declared.name, (pages.get(declared.name) ?? 0) + 1);

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

  return findings.sort((left, right) => {
    if (left.name !== right.name) return left.name < right.name ? -1 : 1;
    return left.problem < right.problem ? -1 : 1;
  });
}
