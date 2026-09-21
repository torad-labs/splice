// The verb -> file table of the console CLI, in its own module so the dispatcher (index.ts) and the
// coverage census (lib/coverage.ts) read ONE table without importing each other: the census is
// itself a verb the dispatcher imports, and an ESM cycle through two top-level awaits is a deadlock
// (measured 2026-09-21: `bun console/tools coverage` hung on exactly that cycle).
export const VERBS: Record<string, string> = {
  look: 'src/commands/look.ts',
  gate: 'src/commands/gate.ts',
  capture: 'src/commands/capture.ts',
  snapshot: 'src/commands/snapshot.ts',
  comp: 'src/commands/comp.ts',
  exit: 'src/commands/exit.ts',
  leak: 'src/commands/leak.ts',
  scan: 'src/commands/scan.ts',
  scale: 'src/commands/scale.ts',
  coverage: 'src/lib/coverage.ts',
  theme: 'src/lib/theme.ts',
  typography: 'src/lib/typography.ts',
  fixtures: 'src/lib/fixtures.ts',
};
