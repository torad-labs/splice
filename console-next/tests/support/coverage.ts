// The wall's inputs, read from the source of record once: the denominator (knobs, splice.toml fields, routes,
// CLI verbs), the routes the daemon serves, and every page's declarations over the baseline.
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { dispositions as baseline } from '../../src/coverage/baseline';
import type { DispositionSource } from '../../src/coverage/checks';
import type { PageJob } from '../../src/coverage/jobs';
import {
  CLI_SOURCE,
  FEATURES_SOURCE,
  KNOB_SOURCE,
  KOTLIN_MAIN,
  TOPOLOGY_MANIFEST,
  parseCliVerbs,
  parseKnobNames,
  parseRouteNames,
  parseRouteSpans,
  parseServedRoutes,
  parseTopologyManifest,
  topologyLeaves,
} from '../../src/coverage/denominator';

export const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
export const read = (relative: string): string => readFileSync(path.join(repoRoot, relative), 'utf8');

export const knobs = parseKnobNames(read(KNOB_SOURCE));
export const topologyFields = parseTopologyManifest(read(TOPOLOGY_MANIFEST));
export const topologyKeys = topologyLeaves(topologyFields);
export const features = read(FEATURES_SOURCE);
export const routeSpans = parseRouteSpans(features);
export const routes = parseRouteNames(features);
export const verbs = parseCliVerbs(read(CLI_SOURCE));
export const denominator = [...knobs, ...topologyKeys, ...routes, ...verbs];

// What the daemon SERVES, from every tracked main Kotlin file (git's list, not a named file, so a route that
// moves to another installer is still found).
export const kotlinMain = execFileSync('git', ['ls-files', '*.kt'], { cwd: repoRoot, encoding: 'utf8' })
  .split('\n')
  .filter((file) => KOTLIN_MAIN.test(file));
export const served = [...new Set(kotlinMain.flatMap((file) => parseServedRoutes(read(file))))].sort();

// Every page's `coverage.ts`, globbed rather than listed, plus the baseline.
export const pageModules = import.meta.glob<Record<string, unknown>>('../../src/pages/*/coverage.ts', { eager: true });

const pageSources: DispositionSource[] = Object.entries(pageModules).map(([file, module]) => {
  const declared = module.dispositions;
  if (!Array.isArray(declared)) throw new Error(`${file} must export \`dispositions\` as an array`);
  // The page's job actions, which a verb's `action` names.
  const job = module.job as PageJob | undefined;
  return { source: file, dispositions: declared as DispositionSource['dispositions'], ...(job === undefined ? {} : { actions: job.actions }) };
});

export const sources: DispositionSource[] = [
  { source: 'src/coverage/baseline.ts', baseline: true, dispositions: baseline },
  ...pageSources,
];

