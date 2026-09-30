// The rules Settings holds: which choices a control offers, what a save answered, what the Tools switches write.
import { describe, expect, test } from 'vitest';
import { KNOB_META } from '../src/lib/knobs';
import {
  CURATED_KNOBS, controlOf, daysOptions, effortChoice, excludedOf, folderOf, gitRootsOf, gitRootsValue, healthOf, otherKnobs, outcomeOf, sectionOf, toolState, withHeadOverride, withMcpHosting, withServerExcluded,
} from '../src/lib/settings';
import type { KnobDisposition } from '../src/types/config';
import type { PatchResult } from '../src/types/core';
import type { DoctorCheck } from '../src/types/doctor';
import type { McpHostedServer } from '../src/types/mcp';

const result = (over: Partial<PatchResult> = {}): PatchResult =>
  ({ applied: {}, rejected: {}, restart_required: [], targets: [], persisted: '/state', ...over }) as PatchResult;
const check = (status: DoctorCheck['status']): DoctorCheck => ({ id: 'x/y', status, detail: 'd' });

describe('sections', () => {
  test('an address names a section, and anything else is General', () => {
    expect(sectionOf('health')).toBe('health');
    expect(sectionOf(undefined)).toBe('general');
    expect(sectionOf('nonsense')).toBe('general');
  });
});

describe('effort', () => {
  test('unset is the model default, the four rungs are choices, and a rung only Advanced offers is none of them', () => {
    expect(effortChoice(null)).toBe('');
    expect(effortChoice('')).toBe('');
    expect(effortChoice('high')).toBe('high');
    expect(effortChoice('xhigh')).toBeNull();
  });
});

describe('retention days', () => {
  test('the list is kept, and a value the daemon holds that is not on it is added in order', () => {
    expect(daysOptions([1, 7, 30], 7)).toEqual([1, 7, 30]);
    expect(daysOptions([1, 7, 30], 10)).toEqual([1, 7, 10, 30]);
    expect(daysOptions([1, 7, 30], null)).toEqual([1, 7, 30]);
  });
});

describe('git folders', () => {
  test('the colon list splits, drops blanks and joins back', () => {
    expect(gitRootsOf('/a/work:/a/clients')).toEqual(['/a/work', '/a/clients']);
    expect(gitRootsOf(' /a : ')).toEqual(['/a']);
    expect(gitRootsOf(null)).toEqual([]);
    expect(gitRootsValue(['/a', '/b'])).toBe('/a:/b');
  });
  test('a folder with a colon or nothing in it cannot be one', () => {
    expect(folderOf(' /a/work ')).toBe('/a/work');
    expect(folderOf('')).toBeNull();
    expect(folderOf('/a:b')).toBeNull();
  });
});

describe('what a save answered', () => {
  test('a saved hot key is saved; a boot-only key waits for a restart', () => {
    expect(outcomeOf('maxInflight', result())).toEqual({ kind: 'saved' });
    expect(outcomeOf('debug', result({ restart_required: ['debug'] }))).toEqual({ kind: 'waits' });
  });
  test('a 200 that names the key as rejected is a rejection, with the daemon’s reason', () => {
    expect(outcomeOf('effort', result({ rejected: { effort: 'not a rung' } }))).toEqual({ kind: 'rejected', reason: 'not a rung' });
  });
  test('a value that is live but not on disk says so instead of claiming it saved', () => {
    expect(outcomeOf('debug', result({ persisted: null, not_persisted: 'read-only state file' } as Partial<PatchResult>))).toEqual({ kind: 'live-only', why: 'read-only state file' });
  });
});

describe('the Tools switches', () => {
  test('hosting is written into [daemon] and leaves the rest of the topology alone', () => {
    const next = withMcpHosting({ daemon: { control_port: 3096 }, providers: { a: 1 } }, true);
    expect(next).toEqual({ daemon: { control_port: 3096, mcp_hosting: true }, providers: { a: 1 } });
    expect(withMcpHosting({}, false)).toEqual({ daemon: { mcp_hosting: false } });
  });
  test('excluding a server adds it once, sorted; sharing it again removes it, and an empty list is dropped', () => {
    const one = withServerExcluded({ daemon: {} }, 'browser', true);
    expect(excludedOf(one)).toEqual(['browser']);
    const two = withServerExcluded(withServerExcluded(one, 'browser', true), 'ast-grep', true);
    expect(excludedOf(two)).toEqual(['ast-grep', 'browser']);
    const none = withServerExcluded(withServerExcluded(two, 'browser', false), 'ast-grep', false);
    expect(none).toEqual({ daemon: {} });
  });
  test('a server’s word: running, not started, or not shared with the planner’s or the operator’s reason', () => {
    const live: McpHostedServer = { eligible: true, hosted: true, sessions: 1, session_ids: [], streams: 0, restarts: 0 };
    expect(toolState(live, false)).toMatchObject({ word: 'Running', shared: true });
    expect(toolState({ ...live, hosted: false }, false)).toMatchObject({ word: 'Not started', shared: true });
    expect(toolState(live, true)).toMatchObject({ word: 'Not shared', shared: false, why: null });
    expect(toolState({ eligible: false, reason: 'excluded by [daemon] mcp_hosting_exclude' }, true)).toMatchObject({ shared: false, why: null });
    expect(toolState({ eligible: false, reason: 'a reason nobody has worded yet' }, false)).toMatchObject({ word: 'Not shared', shared: false, why: 'a reason nobody has worded yet' });
  });
  test('every reason the planner gives reads as a sentence, never as a config term', () => {
    // The planner's whole list, from McpSharing.kt rejection() and its entry checks.
    const said = (reason: string): string => toolState({ eligible: false, reason }, false).why ?? '';
    expect(said("transport 'http' already serves many clients")).toBe('It is a network server that already serves many sessions, so splice has nothing to share.');
    expect(said('no command')).toBe('It names no program to run.');
    expect(said('has a cwd (session-scoped)')).toBe('It runs in a folder of its own, so one shared copy cannot serve every session.');
    expect(said('a value expands ${VAR} from the client\'s environment')).toBe('It reads a setting from each session’s own environment, so one shared copy cannot serve every session.');
    expect(said("names the relative path './x' (project-scoped)")).toBe('It is tied to one project’s folder (./x), so one shared copy cannot serve every session.');
    expect(said("'--root repo' names a location relative to the client (project-scoped)")).toBe('It is tied to one project’s folder (--root repo), so one shared copy cannot serve every session.');
    expect(said("names the directory '/srv/a' (project-scoped)")).toBe('It is tied to one project’s folder (/srv/a), so one shared copy cannot serve every session.');
    for (const malformed of ['entry is not an object', 'malformed transport type', 'malformed args (expected only strings)', 'malformed env (expected string values)']) {
      expect(said(malformed)).toBe('Its entry in the client’s settings is not a valid tool server, so splice cannot run it.');
    }
  });
});

describe('health', () => {
  test('one word for the report: a failure needs you, a warning is mostly good, nothing is all good', () => {
    expect(healthOf([check('ok'), check('info')])).toMatchObject({ word: 'All good', wanting: 0 });
    expect(healthOf([check('ok'), check('warn')])).toMatchObject({ word: 'Mostly good', wanting: 1 });
    expect(healthOf([check('warn'), check('fail')])).toMatchObject({ word: 'Needs you', wanting: 2 });
    expect(healthOf([])).toMatchObject({ word: 'All good' });
  });
});

const knob = (key: string, value: KnobDisposition['value'] = 1): KnobDisposition => ({ key, value, provenance: 'default', hot: true, defaultValue: value, overriddenBy: [] });

describe('every other setting', () => {
  test('every knob the table places, less the curated ones, is in exactly one group', () => {
    const all = Object.keys(KNOB_META).map((key) => knob(key));
    const shown = otherKnobs(all).flatMap((group) => group.knobs.map((entry) => entry.key));
    expect(shown.sort()).toEqual(Object.keys(KNOB_META).filter((key) => !CURATED_KNOBS.has(key)).sort());
    expect(new Set(shown).size).toBe(shown.length);
  });
  test('a knob the table does not place goes to the last group, never nowhere', () => {
    const groups = otherKnobs([knob('brandNewKnob')]);
    expect(groups.map((group) => group.group)).toEqual(['daemon']);
    expect(groups[0]?.knobs[0]?.key).toBe('brandNewKnob');
  });
  test('the control follows what the daemon says the knob is', () => {
    expect(controlOf(true, undefined, null)).toEqual({ kind: 'switch' });
    expect(controlOf(3, undefined, null)).toEqual({ kind: 'number' });
    expect(controlOf('x', undefined, null)).toEqual({ kind: 'text' });
    expect(controlOf('warn', { group: 'usage', choices: ['warn', 'block'] }, null)).toEqual({ kind: 'choice', choices: ['warn', 'block'] });
    expect(controlOf(1, { group: 'reasoning', locked: true }, null)).toEqual({ kind: 'locked' });
  });
  test('a plan-only knob is printed for all plans and edited for one plan', () => {
    const meta = { group: 'usage', headOnly: true, choices: ['warn', 'block'] } as const;
    expect(controlOf('warn', meta, null)).toEqual({ kind: 'head-only' });
    expect(controlOf('warn', meta, 'claudex')).toEqual({ kind: 'choice', choices: ['warn', 'block'] });
  });
});

describe('a plan’s own value', () => {
  test('it lands in that plan’s overrides as a string and leaves the other plans and tables alone', () => {
    const next = withHeadOverride({ daemon: { control_port: 1 }, heads: { b: { overrides: { x: '1' } } } }, 'a', 'maxQueued', 8);
    expect(next).toEqual({ daemon: { control_port: 1 }, heads: { a: { overrides: { maxQueued: '8' } }, b: { overrides: { x: '1' } } } });
  });
  test('a second knob joins the first, and setting the same knob again replaces it', () => {
    const one = withHeadOverride({}, 'a', 'k1', 1);
    const two = withHeadOverride(one, 'a', 'k2', true);
    expect(two).toEqual({ heads: { a: { overrides: { k1: '1', k2: 'true' } } } });
    expect(withHeadOverride(two, 'a', 'k1', 2)).toEqual({ heads: { a: { overrides: { k2: 'true', k1: '2' } } } });
  });
  test('removing the last override drops the table and keeps the plan’s other keys', () => {
    const start = { heads: { a: { url: 'x', overrides: { k: '1' } } } };
    expect(withHeadOverride(start, 'a', 'k', null)).toEqual({ heads: { a: { url: 'x' } } });
    expect(withHeadOverride({}, 'a', 'k', null)).toEqual({ heads: { a: {} } });
  });
});
