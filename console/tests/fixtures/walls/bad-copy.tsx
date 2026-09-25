// Planted violations for the half-2 bare-copy scanner (tests/copy.test.ts). Lives outside src/, so
// it is not part of the real denominator: this file exists only to prove `scanFileForBareCopy` can
// fail, and that its exclusions do not swallow the same shape of text they are meant to skip.
// Every excluded form below is real, type-checked syntax (this file is still compiled by tsc), not
// a comment describing what a real one would look like.
import { ABSENT } from '../../../src/shared/lib';

// An ordinary import: a real module specifier never contains whitespace, so this checks the
// scanner does not misfire on the surrounding import syntax rather than on word count.
export const REEXPORTED_ABSENT = ABSENT;

// A console argument: a developer message, not product copy.
console.log('This console line has plenty of words in it');

// A string-literal TYPE, not a runtime value.
export type Direction = 'left' | 'right' | 'a type literal with several words';

// A bare sentence literal with no exclusion: the planted "half 2" finding.
export const MESSAGE = 'Restart the daemon before trying again';

export function BadCopy() {
  return (
    // className: a class list, never prose, even at four words.
    <div className="btn primary large today">Restart the daemon now</div>
  );
}
