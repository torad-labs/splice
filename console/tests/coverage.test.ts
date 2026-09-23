// COVERAGE WALL (row M1-04). Every knob, topology key and route the daemon
// exposes carries one disposition, and the denominator is PARSED FROM THE
// SOURCE at test time (denominator.ts) rather than hand-listed: a list that
// checks itself cannot fail for anything missing from itself. The fixture test
// is the mutation proof — the wall must fail on a denominator with one
// undispositioned name and an exclusion with no reason.
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, test } from 'vitest';

import { dispositions as baseline } from '../src/shared/coverage/baseline';
import { checkCoverage, type DispositionSource } from '../src/shared/coverage/checks';
import {
  FEATURES_SOURCE,
  KNOB_SOURCE,
  KOTLIN_MAIN,
  TOPOLOGY_SOURCES,
  parseKnobNames,
  parseRouteNames,
  parseRouteSpans,
  parseSerialNames,
  parseServedRoutes,
  servedBy,
} from '../src/shared/coverage/denominator';
import { denominator as missingDenominator, dispositions as missingDispositions } from './fixtures/walls/coverage-missing';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (relative: string): string => readFileSync(path.join(repoRoot, relative), 'utf8');

const knobs = parseKnobNames(read(KNOB_SOURCE));
const topologyKeys = [...new Set(TOPOLOGY_SOURCES.flatMap((file) => parseSerialNames(read(file))))].sort();
const features = read(FEATURES_SOURCE);
const routeSpans = parseRouteSpans(features);
const routes = parseRouteNames(features);
const denominator = [...knobs, ...topologyKeys, ...routes];

// What the daemon SERVES, from every tracked main Kotlin file (git's list, not a named file, so a
// route that moves to another installer is still found).
const kotlinMain = execFileSync('git', ['ls-files', '*.kt'], { cwd: repoRoot, encoding: 'utf8' })
  .split('\n')
  .filter((file) => KOTLIN_MAIN.test(file));
const served = [...new Set(kotlinMain.flatMap((file) => parseServedRoutes(read(file))))].sort();

// Every page's `coverage.ts`, globbed rather than listed, plus the baseline.
const pageModules = import.meta.glob<Record<string, unknown>>('../src/**/coverage.ts', { eager: true });

const pageSources: DispositionSource[] = Object.entries(pageModules).map(([file, module]) => {
  const declared = module.dispositions;
  if (!Array.isArray(declared)) {
    throw new Error(`${file} must export \`dispositions\` as an array (CONTRACTS.md section 4)`);
  }
  return { source: file, dispositions: declared as DispositionSource['dispositions'] };
});

const sources: DispositionSource[] = [
  { source: 'src/shared/coverage/baseline.ts', baseline: true, dispositions: baseline },
  ...pageSources,
];

describe('coverage wall', () => {
  test('the denominator is parsed from the source', () => {
    console.log(
      `coverage denominator: ${knobs.length} knobs (${KNOB_SOURCE}), ` +
        `${topologyKeys.length} topology keys (${TOPOLOGY_SOURCES.length} files), ` +
        `${routes.length} routes from ${routeSpans.length} backticked spans ` +
        `(${FEATURES_SOURCE} sections 2.1 and 6)`,
    );

    console.log(`coverage served: ${served.length} /api routes registered in ${kotlinMain.length} main Kotlin files`);

    // A zero means the parse broke, not that the daemon has no surface.
    expect(knobs.length).toBeGreaterThan(0);
    expect(topologyKeys.length).toBeGreaterThan(0);
    expect(routes.length).toBeGreaterThan(0);
    expect(served).toContain('/api/teams/{id}/economics');
    expect(served).toContain('/api/heads/{head}/{action}');
  });

  test('every enumerated key carries a disposition, and none is pending for a route the daemon serves', () => {
    expect(checkCoverage(denominator, sources, served)).toEqual([]);
  });

  test('a registration parses from both installer shapes, and a placeholder serves any one segment', () => {
    expect(parseServedRoutes([
      '                get("/api/status") { guarded(call) { respond(call, payloads.statusJson()) } }',
      '        route.put("/api/teams/{id}") { guarded(call) { teams.replace(id(call)) } }',
      '        route.put("/api/teams/{id}") {',
      '                post("/launch/{head}") { guarded(call) { launchRoutes.launch(call) } }',
      '    // get("/api/commented") is not a registration',
    ].join('\n'))).toEqual(['/api/status', '/api/teams/{id}']);
    expect(servedBy('/api/heads/{head}/{action}', '/api/heads/{head}/restart')).toBe(true);
    expect(servedBy('/api/heads/{head}/{action}', '/api/heads/{head}/capture/extra')).toBe(false);
    expect(servedBy('/api/teams', '/api/teams/{id}')).toBe(false);
  });

  test('the wall fails by name on a served route left pending or undispositioned', () => {
    const baselineOnly = (dispositions: DispositionSource['dispositions']): DispositionSource[] => [
      { source: 'baseline', baseline: true, dispositions },
    ];
    // The baseline says pending, the daemon serves it: a manifest for a daemon that is gone.
    expect(checkCoverage([], baselineOnly([{ kind: 'route', name: '/api/x', disposition: 'pending', where: 'V4-1' }]), ['/api/x']))
      .toEqual([{ name: '/api/x', problem: 'pending but served' }]);
    // A page's own disposition is the effective one: once the page reads it, the baseline's
    // pending is history.
    expect(checkCoverage([], [
      ...baselineOnly([{ kind: 'route', name: '/api/x', disposition: 'pending', where: 'V4-1' }]),
      { source: 'pages/x/coverage.ts', dispositions: [{ kind: 'route', name: '/api/x', disposition: 'read-only' }] },
    ], ['/api/x'])).toEqual([]);
    // A registration no disposition names is found from the daemon's side.
    expect(checkCoverage([], baselineOnly([]), ['/api/y/{id}']))
      .toEqual([{ name: '/api/y/{id}', problem: 'served without disposition' }]);
  });

  test('the wall fails on its two planted violations', () => {
    expect(
      checkCoverage(missingDenominator, [
        { source: 'fixtures/walls/coverage-missing.ts', baseline: true, dispositions: missingDispositions },
      ]),
    ).toEqual([
      { name: 'GONE_KNOB', problem: 'no disposition' },
      { name: 'PORT', problem: 'excluded without reason' },
    ]);
  });

  // The fixture plants the other two; a page declaring a name twice and a
  // `pending` with no row are the ones a live page hits first.
  test('the wall reports the other two problems by name', () => {
    expect(
      checkCoverage(['/api/events'], [
        {
          source: 'baseline',
          baseline: true,
          dispositions: [{ kind: 'route', name: '/api/events', disposition: 'pending', where: 'M3-01' }],
        },
        { source: 'pages/fleet/coverage.ts', dispositions: [{ kind: 'route', name: '/api/events', disposition: 'read-only' }] },
        { source: 'pages/usage/coverage.ts', dispositions: [{ kind: 'route', name: '/api/events', disposition: 'pending' }] },
      ]),
    ).toEqual([
      { name: '/api/events', problem: 'pending without where' },
      { name: '/api/events', problem: 'two page dispositions' },
    ]);
  });
});
