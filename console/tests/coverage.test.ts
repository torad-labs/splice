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
import { checkCoverage, type Disposition, type DispositionSource } from '../src/shared/coverage/checks';
import type { PageJob } from '../src/shared/coverage/jobs';
import {
  CLI_SOURCE,
  FEATURES_SOURCE,
  KNOB_SOURCE,
  KOTLIN_MAIN,
  TOPOLOGY_SOURCES,
  parseCliVerbs,
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
// V4-219: the CLI's verbs join the denominator, so a verb the console neither answers nor excludes
// with a reason fails by name (PRODUCT.md principle 2).
const verbs = parseCliVerbs(read(CLI_SOURCE));
const denominator = [...knobs, ...topologyKeys, ...routes, ...verbs];

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
  // The page's job actions, which a verb's `action` names (V4-220).
  const job = module.job as PageJob | undefined;
  return { source: file, dispositions: declared as DispositionSource['dispositions'], ...(job === undefined ? {} : { actions: job.actions }) };
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
    console.log(`coverage verbs: ${verbs.length} from ${CLI_SOURCE}`);
    expect(verbs).toContain('dashboard');
    expect(verbs.length).toBeGreaterThanOrEqual(21);
  });

  test('a CLI verb the pages do not answer fails by name', () => {
    // The fake-verb proof: the real sources against a denominator with one verb nobody declared.
    expect(checkCoverage([...denominator, 'fake-verb'], sources, served)).toEqual([{ name: 'fake-verb', problem: 'no disposition' }]);
    // and a verb the pages exclude without saying why
    const unexplained = sources.map((source) => ({
      ...source,
      dispositions: source.dispositions.map((declared) => (declared.name === 'dashboard' ? { kind: 'verb' as const, name: 'dashboard', disposition: 'excluded' as const } : declared)),
    }));
    expect(checkCoverage(denominator, unexplained, served)).toEqual([{ name: 'dashboard', problem: 'excluded without reason' }]);
  });

  test('a verb names a built page action and a route that covers it, or fails by name (V4-220)', () => {
    const routes: Disposition[] = [
      { kind: 'route', name: '/api/x', disposition: 'editable' },
      { kind: 'route', name: '/api/r', disposition: 'read-only' },
    ];
    const page = (dispositions: Disposition[]): DispositionSource[] => [
      { source: 'pages/x/coverage.ts', dispositions: [...routes, ...dispositions], actions: [{ name: 'Do x' }, { name: 'Do y', row: 'V4-1' }] },
    ];
    const served = ['/api/x', '/api/r'];
    const check = (verb: Disposition, registered: readonly string[] = served) => checkCoverage([], page([verb]), registered);
    // Right: an editable verb through its built action and an editable, served route; a read-only
    // verb through a read-only route; a pending verb whose action is not built yet.
    expect(check({ kind: 'verb', name: 'a', disposition: 'editable', action: 'Do x', via: '/api/x' })).toEqual([]);
    expect(check({ kind: 'verb', name: 'b', disposition: 'read-only', via: '/api/r' })).toEqual([]);
    expect(check({ kind: 'verb', name: 'c', disposition: 'pending', where: 'V4-1', action: 'Do y', via: '/api/x' })).toEqual([]);
    // An editable verb with no action, or one its page does not list.
    expect(check({ kind: 'verb', name: 'd', disposition: 'editable', via: '/api/x' })).toEqual([{ name: 'd', problem: 'verb without action' }]);
    expect(check({ kind: 'verb', name: 'e', disposition: 'editable', action: 'Do z', via: '/api/x' }))
      .toEqual([{ name: 'e', problem: 'verb without action' }]);
    // An editable verb whose page action is still a row's to build.
    expect(check({ kind: 'verb', name: 'f', disposition: 'editable', action: 'Do y', via: '/api/x' }))
      .toEqual([{ name: 'f', problem: 'verb action not built' }]);
    // No route, a route only read, a route no disposition names, and a route the daemon does not serve.
    expect(check({ kind: 'verb', name: 'g', disposition: 'read-only' })).toEqual([{ name: 'g', problem: 'verb via uncovered route' }]);
    expect(check({ kind: 'verb', name: 'h', disposition: 'editable', action: 'Do x', via: '/api/r' }))
      .toEqual([{ name: 'h', problem: 'verb via uncovered route' }]);
    expect(check({ kind: 'verb', name: 'i', disposition: 'read-only', via: '/api/nowhere' }))
      .toEqual([{ name: 'i', problem: 'verb via uncovered route' }]);
    expect(check({ kind: 'verb', name: 'j', disposition: 'editable', action: 'Do x', via: '/api/x' }, ['/api/r']))
      .toEqual([{ name: 'j', problem: 'verb via uncovered route' }]);
    // A pending verb whose page action is built is answered: the manifest must say so.
    expect(check({ kind: 'verb', name: 'k', disposition: 'pending', where: 'V4-1', action: 'Do x' }))
      .toEqual([{ name: 'k', problem: 'pending but built' }]);
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
