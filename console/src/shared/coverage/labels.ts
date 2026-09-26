// The copy wall's checker (half 1: copy modules). The 2026-09-25 voice ruling: a label is a
// standard product term of one to three words, in SENTENCE case (this reverses the previous
// lowercase-everywhere rule) — "Sessions", "Last seen", "⌘K". A help sentence (export `H`) is at
// most twelve words, one sentence, starting uppercase. A unit fragment (export `U`, printed only
// beside a figure, like "ago" or "/h") is at most two words, any case. Every string exported from
// a `copy.ts` reads by help rules, whatever it is named, because a copy.ts carries no labels.
//
// `src/shared/coverage/index.ts` (not owned by this wall) re-exports `checkLabels`, `LabelFinding`
// and `LabelProblem` BY NAME ONLY — it does not call or otherwise depend on their shape, so both
// are free to change here.

export type LabelProblem = 'too long' | 'em-dash' | 'lowercase start' | 'trailing period' | 'more than one sentence';

export type LabelFinding = {
  readonly key: string;
  readonly value: string;
  readonly problems: readonly LabelProblem[];
};

const EM_DASH = '—';
const STARTS_UPPERCASE = /^\p{Lu}/u;
const STARTS_LETTER = /^\p{L}/u;

/** Every string value in the table, by its dotted path. A nested table is covered and a new
 *  export is too, because the denominator is globbed rather than hand-listed (tests/copy.test.ts). */
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

function wordCount(value: string): number {
  const trimmed = value.trim();
  return trimmed === '' ? 0 : trimmed.split(/\s+/).length;
}

/** How many sentences a help string reads as: split on a sentence-ending mark followed by
 *  whitespace, so a single trailing period is one sentence, not two. */
function sentenceCount(value: string): number {
  const trimmed = value.trim();
  if (trimmed === '') return 0;
  return trimmed.split(/(?<=[.!?])\s+/).filter((part) => part !== '').length;
}

type Role = 'help' | 'units' | 'label';

/** `H` and `U` are the two escape hatches from label rules; every other export name in a
 *  strings.ts is a label, and every export in a copy.ts reads by help rules regardless of name. */
function roleOf(exportName: string, isCopyFile: boolean): Role {
  if (isCopyFile) return 'help';
  if (exportName === 'H') return 'help';
  if (exportName === 'U') return 'units';
  return 'label';
}

function problemsOf(role: Role, value: string): LabelProblem[] {
  if (value === '') return []; // a deliberate blank is allowed
  const problems: LabelProblem[] = [];
  const wc = wordCount(value);
  if (role === 'help') {
    if (wc > 12) problems.push('too long');
    if (value.includes(EM_DASH)) problems.push('em-dash');
    if (!STARTS_UPPERCASE.test(value)) problems.push('lowercase start');
    if (sentenceCount(value) > 1) problems.push('more than one sentence');
  } else if (role === 'units') {
    if (wc > 2) problems.push('too long');
    if (value.includes(EM_DASH)) problems.push('em-dash');
  } else {
    if (wc > 3) problems.push('too long');
    if (value.includes(EM_DASH)) problems.push('em-dash');
    // A label starting with a digit, symbol or ⌘ passes the case rule untouched.
    if (STARTS_LETTER.test(value) && !STARTS_UPPERCASE.test(value)) problems.push('lowercase start');
    if (value.endsWith('.')) problems.push('trailing period');
  }
  return problems;
}

/**
 * Every string exported from one copy module (a `strings.ts` or `copy.ts`), checked against the
 * role its export name (or the file's kind) gives it. `isCopyFile` selects help rules for every
 * export in the module, since a copy.ts carries no labels.
 */
export function checkLabels(table: unknown, isCopyFile: boolean): LabelFinding[] {
  if (typeof table !== 'object' || table === null) return [];
  const findings: LabelFinding[] = [];
  for (const [exportName, exportValue] of Object.entries(table)) {
    const role = roleOf(exportName, isCopyFile);
    const strings = new Map<string, string>();
    collectStrings(exportValue, exportName, strings);
    for (const [key, value] of strings) {
      const problems = problemsOf(role, value);
      if (problems.length > 0) findings.push({ key, value, problems });
    }
  }
  return findings.sort((left, right) => (left.key < right.key ? -1 : 1));
}
