// COVERAGE WALL. Every knob, topology field, route and CLI verb the daemon exposes carries one disposition in the
// replacement console, and the denominator is PARSED FROM THE SOURCE at test time (src/coverage/denominator.ts)
// rather than hand-listed: a list that checks itself cannot fail for anything missing from itself. The planted
// violations below are the mutation proof: the wall must fail on a denominator with one undispositioned name and
// on an exclusion with no reason.
import { describe, expect, test } from 'vitest';

import { checkCoverage, type Disposition, type DispositionSource } from '../src/coverage/checks';
import { CLI_SOURCE, KNOB_SOURCE, FEATURES_SOURCE, TOPOLOGY_MANIFEST, parseServedRoutes, servedBy } from '../src/coverage/denominator';
import { denominator, kotlinMain, knobs, routeSpans, routes, served, sources, topologyKeys, verbs } from './support/coverage';

describe('coverage wall', () => {
  test('the denominator is parsed from the source', () => {
    console.log(
      `coverage denominator: ${knobs.length} knobs (${KNOB_SOURCE}), ` +
        `${topologyKeys.length} topology fields (${TOPOLOGY_MANIFEST}), ` +
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

  test('a verb names a built page action and a route that covers it, or fails by name ()', () => {
    const routes: Disposition[] = [
      { kind: 'route', name: '/api/x', disposition: 'editable' },
      { kind: 'route', name: '/api/r', disposition: 'read-only' },
    ];
    const page = (dispositions: Disposition[]): DispositionSource[] => [
      { source: 'pages/x/coverage.ts', dispositions: [...routes, ...dispositions], actions: [{ name: 'Do x' }, { name: 'Do y', row: 'ROW-1' }] },
    ];
    const served = ['/api/x', '/api/r'];
    const check = (verb: Disposition, registered: readonly string[] = served) => checkCoverage([], page([verb]), registered);
    // Right: an editable verb through its built action and an editable, served route; a read-only
    // verb through a read-only route; a pending verb whose action is not built yet.
    expect(check({ kind: 'verb', name: 'a', disposition: 'editable', action: 'Do x', via: '/api/x' })).toEqual([]);
    expect(check({ kind: 'verb', name: 'b', disposition: 'read-only', via: '/api/r' })).toEqual([]);
    expect(check({ kind: 'verb', name: 'c', disposition: 'pending', where: 'ROW-1', action: 'Do y', via: '/api/x' })).toEqual([]);
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
    expect(check({ kind: 'verb', name: 'k', disposition: 'pending', where: 'ROW-1', action: 'Do x' }))
      .toEqual([{ name: 'k', problem: 'pending but built' }]);
    // a read-only verb that names its action claims the page shows it, so an action still a
    // row's to build fails as the editable one does; a read-only verb through a built action passes.
    expect(check({ kind: 'verb', name: 'l', disposition: 'read-only', action: 'Do y', via: '/api/r' }))
      .toEqual([{ name: 'l', problem: 'verb action not built' }]);
    expect(check({ kind: 'verb', name: 'm', disposition: 'read-only', action: 'Do x', via: '/api/r' })).toEqual([]);
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

  test('a name still pending is allowed while the replacement is built; the parity check is what refuses it', () => {
    expect(checkCoverage([], [{ source: 'baseline', baseline: true, dispositions: [{ kind: 'route', name: '/api/x', disposition: 'pending', where: 'ROW-1' }] }], ['/api/x'])).toEqual([]);
  });

  test('the wall fails by name on a served route left undispositioned', () => {
    const baselineOnly = (dispositions: DispositionSource['dispositions']): DispositionSource[] => [
      { source: 'baseline', baseline: true, dispositions },
    ];
    // A page's own disposition is the effective one: once the page reads it, the baseline's
    // pending is history.
    expect(checkCoverage([], [
      ...baselineOnly([{ kind: 'route', name: '/api/x', disposition: 'pending', where: 'ROW-1' }]),
      { source: 'pages/x/coverage.ts', dispositions: [{ kind: 'route', name: '/api/x', disposition: 'read-only' }] },
    ], ['/api/x'])).toEqual([]);
    // A registration no disposition names is found from the daemon's side.
    expect(checkCoverage([], baselineOnly([]), ['/api/y/{id}']))
      .toEqual([{ name: '/api/y/{id}', problem: 'served without disposition' }]);
  });

  test('the wall fails on its two planted violations', () => {
    expect(
      checkCoverage(['GONE_KNOB', 'PORT'], [
        { source: 'planted', baseline: true, dispositions: [{ kind: 'knob', name: 'PORT', disposition: 'excluded' }] },
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
          dispositions: [{ kind: 'route', name: '/api/events', disposition: 'pending', where: 'ROW-3' }],
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
