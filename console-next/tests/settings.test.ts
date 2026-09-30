// The rules Settings holds: which choices a control offers, what a save answered, what the Tools switches write.
import { describe, expect, test } from 'vitest';
import {
  daysOptions, effortChoice, excludedOf, folderOf, gitRootsOf, gitRootsValue, healthOf, outcomeOf, sectionOf, toolState, withMcpHosting, withServerExcluded,
} from '../src/lib/settings';
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
    expect(toolState({ eligible: false, reason: 'transport http' }, false)).toMatchObject({ word: 'Not shared', shared: false, why: 'transport http' });
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
