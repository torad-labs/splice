// The label wall's checker. CONTRACTS.md section 4: every user-visible label of
// a directory lives in that directory's `strings.ts`, lowercase, three words or
// fewer, no em-dash. The label wall globs `src/**/strings.ts` and flattens every
// string value it finds, so a nested table is covered and a new export is too.

export type LabelProblem = 'too long' | 'em-dash' | 'capitalised';

export type LabelFinding = {
  readonly key: string;
  readonly problems: readonly LabelProblem[];
};

const MAX_WORDS = 3;
const EM_DASH = '—';
const STARTS_UPPERCASE = /^\p{Lu}/u;

/** Every string value in the table, by its dotted path. */
function collectStrings(value: unknown, key: string, found: Map<string, string>): void {
  if (typeof value === 'string') {
    found.set(key, value);
    return;
  }
  if (Array.isArray(value)) {
    value.forEach((item, index) => collectStrings(item, `${key}[${index}]`, found));
    return;
  }
  if (typeof value !== 'object' || value === null) return;
  for (const [name, item] of Object.entries(value)) {
    collectStrings(item, key === '' ? name : `${key}.${name}`, found);
  }
}

/** Every label that is not a label, by key. */
export function checkLabels(table: unknown): LabelFinding[] {
  const labels = new Map<string, string>();
  collectStrings(table, '', labels);

  const findings: LabelFinding[] = [];
  for (const [key, label] of labels) {
    const problems: LabelProblem[] = [];
    if (label.trim().split(/\s+/).length > MAX_WORDS) problems.push('too long');
    if (label.includes(EM_DASH)) problems.push('em-dash');
    if (STARTS_UPPERCASE.test(label)) problems.push('capitalised');
    if (problems.length > 0) findings.push({ key, problems });
  }

  return findings.sort((left, right) => (left.key < right.key ? -1 : 1));
}
