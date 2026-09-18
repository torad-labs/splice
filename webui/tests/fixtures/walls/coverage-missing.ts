// The coverage wall's synthetic violations: a denominator carrying one name no
// declaration covers, and an `excluded` entry with an empty reason. The wall
// must report both BY NAME. `PORT` and `EFFORT` are real Knob.kt entries; the
// names are irrelevant to the checker, which is why they read as plausibly
// dispositioned and `GONE_KNOB` reads as plausibly forgotten.
import type { Disposition } from '@shared/coverage';

export const denominator: readonly string[] = ['PORT', 'EFFORT', 'GONE_KNOB'];

export const dispositions: readonly Disposition[] = [
  { kind: 'knob', name: 'PORT', disposition: 'excluded', reason: '' },
  { kind: 'knob', name: 'EFFORT', disposition: 'pending', where: 'M2-05' },
];
