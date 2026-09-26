// The motion layer: what a gesture IS, decided in one place.
//
// Three modules, three jobs. `diff` says what changed (pure, no DOM). `registry` pairs a departure
// in one bay with an arrival in another and computes the FLIP (no React). `react` runs a bay's
// side of it (hooks, measurement, timers). The CSS owns the timing; nothing here draws.
export { diffKeys, keysOf } from './diff';
export type { KeyDiff } from './diff';
export { createHandoffRegistry, handoffs, handoffTransform, prefersReducedMotion } from './registry';
export type { Handoff, HandoffRegistry, Rect } from './registry';
export { MOTION_MS, useBayGestures } from './react';
export type { BayGestures, MotionRow } from './react';
