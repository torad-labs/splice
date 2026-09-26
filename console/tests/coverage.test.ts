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

import { RUNTIME_KNOBS, TOPOLOGY_CHOICES, TOPOLOGY_SCHEMA, type SchemaNode } from '../src/entities/topology';
import { dispositions as baseline } from '../src/shared/coverage/baseline';
import { checkCoverage, type Disposition, type DispositionSource } from '../src/shared/coverage/checks';
import type { PageJob } from '../src/shared/coverage/jobs';
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
  parseTopologyManifest,
  parseServedRoutes,
  servedBy,
  topologyLeaves,
} from '../src/shared/coverage/denominator';
import { denominator as missingDenominator, dispositions as missingDispositions } from './fixtures/walls/coverage-missing';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (relative: string): string => readFileSync(path.join(repoRoot, relative), 'utf8');

const knobs = parseKnobNames(read(KNOB_SOURCE));
const topologyFields = parseTopologyManifest(read(TOPOLOGY_MANIFEST));
const topologyKeys = topologyLeaves(topologyFields);
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
    // V4-239: a read-only verb that names its action claims the page shows it, so an action still a
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

// V4-312: splice.toml's fields come from the manifest Topology's own serializer is walked into, so the
// console's two hand-written views of the file are held to it as well: the pickers' values and the
// validator's key set. Each was written from FEATURES 2.3 and drifted from the daemon without a sound.
describe('the console reads splice.toml as the daemon parses it', () => {
  test('every enum field\'s picker offers exactly the values the daemon accepts', () => {
    const enums = topologyFields.filter((field) => field.values.length > 0);
    expect(enums.length, 'the manifest lists an enum field').toBeGreaterThan(0);
    for (const field of enums) {
      // model.ts's fieldOf picks a field's choices by its key, the path's last segment.
      const offered = TOPOLOGY_CHOICES[field.path.split('.').at(-1) ?? ''] ?? [];
      expect([...offered].sort(), field.path).toEqual([...field.values].sort());
    }
  });

  test('the validator accepts every field the daemon parses and no key it does not', () => {
    // TOPOLOGY_SCHEMA in the manifest's grammar: `.key`, `.*` for `each`, `[]` for `array`; an `open`
    // bag's keys are never checked, so anything under it passes both ways.
    const accepted: string[] = [];
    const open: string[] = [];
    const flatten = (node: SchemaNode, at: string): void => {
      if (node.open === true) open.push(at);
      for (const [key, child] of Object.entries(node.keys ?? {})) {
        const path = at === '' ? key : `${at}.${key}`;
        accepted.push(path);
        flatten(child, path);
      }
      if (node.each !== undefined) flatten(node.each, `${at}.*`);
      if (node.array !== undefined) flatten(node.array, `${at}[]`);
    };
    flatten(TOPOLOGY_SCHEMA, '');
    const parsed = new Set(topologyFields.map((field) => field.path));
    const known = new Set(accepted);
    const inOpen = (path: string): boolean => open.some((bag) => path.startsWith(`${bag}.`) || path.startsWith(`${bag}[`));
    const parent = (path: string): string => path.slice(0, path.lastIndexOf('.'));
    // A free-keyed map the schema narrows to named keys ([defaults] to the runtime knobs) is the one
    // place the two may differ: `defaults.*` in the file, `defaults.port` in the schema.
    const narrowed = (path: string): boolean => path.endsWith('.*') && accepted.some((key) => key.startsWith(`${path.slice(0, -1)}`));

    const refused = [...parsed].filter((path) => !known.has(path) && !inOpen(path) && !narrowed(path));
    const phantom = accepted.filter((path) => !parsed.has(path) && !inOpen(path) && !parsed.has(`${parent(path)}.*`));
    expect({ refused, phantom }).toEqual({ refused: [], phantom: [] });
  });

  test('[defaults] takes every knob Knob.kt lets the global TOML set, and no other (V4-316)', () => {
    // Knob.kt read here, never copied: each entry from `NAME(` to the next, its key the first string
    // it passes, and head-only when it says `headOnly = true`, which ConfigService drops from every
    // global layer ([defaults] among them) and takes from [heads.KEY.overrides] alone.
    const knobs = knobScopes(read(KNOB_SOURCE));
    expect(knobs.length, 'every Knob entry parsed').toBe(parseKnobNames(read(KNOB_SOURCE)).length);
    const global = knobs.filter((knob) => !knob.headOnly).map((knob) => knob.key);
    const listed = new Set<string>(RUNTIME_KNOBS);
    const missing = global.filter((key) => !listed.has(key));
    const extra = [...listed].filter((key) => !global.includes(key));
    expect({ missing, extra }).toEqual({ missing: [], extra: [] });
  });
});

/** Each Knob entry's key and whether only [heads.KEY.overrides] may set it, from Knob.kt's source. */
function knobScopes(kotlin: string): { key: string; headOnly: boolean }[] {
  const body = kotlin.replace(/\/\/.*$/gm, '');
  const starts = [...body.matchAll(/^ {4}[A-Z][A-Z0-9_]*\(/gm)].map((match) => match.index);
  return starts.map((start, at) => {
    const entry = body.slice(start, starts[at + 1] ?? body.lastIndexOf('}'));
    return { key: /"([A-Za-z0-9]+)"/.exec(entry)?.[1] ?? '', headOnly: /\bheadOnly = true\b/.test(entry) };
  });
}
