// M1-20: the capture marker, and the rule it rests on.
//
// A capture is an instrument, and law 23 says an instrument must be able to distinguish PASSED,
// FAILED and DID NOT RUN. Before this row a capture could only be checked by a human READING it:
// a fixture name that does not exist fails a guarded dynamic import SILENTLY, so the page renders
// live data, the frame looks exactly like a working capture, and nothing anywhere says otherwise
// (measured on this tree — the first turns and sessions captures were live data for that reason).
//
// The marker is `data-sample`, on the page root, carrying the fixture's own FILE name. These tests
// pin the half that a static render cannot: THE SECOND ASSERTION, which is the point of the row —
// a name that does not exist ends with NO marker, never a stale one.
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { loadFixture as loadTurns, TurnsBoard } from '../src/pages/turns';
import { loadFixture as loadSessions } from '../src/pages/sessions';
import { loadFixture as loadProjects } from '../src/pages/projects';
import { loadFixture as loadLogs } from '../src/pages/logs';
import { wantsFixture as usageWants } from '../src/pages/usage';
import { wantsFixture as settingsWants } from '../src/pages/settings';
import { wantsFixture as compactionWants } from '../src/pages/compaction';
import { wantsFixture as teamsWants } from '../src/pages/teams';
import { fixtureModels } from '../src/pages/models';
import { fixtureAccounts } from '../src/pages/accounts/fixtures/accounts';
import { fixtureDoctor } from '../src/pages/doctor/fixtures/doctor';

const h = React.createElement;

describe('a fixture name that does not exist resolves to nothing', () => {
  // The loaders are the rule's front door: they return ONE value holding the name and the bytes
  // together, so there is no state a name could reach without its payload. A name with no module
  // rejects the import, which resolves to null — the page then holds no fixture and sets no marker.
  test('the generic loaders return null for a name with no module', async () => {
    for (const load of [loadTurns, loadSessions, loadProjects, loadLogs]) {
      expect(await load('no-such-fixture')).toBeNull();
      expect(await load('')).toBeNull();
    }
  });

  test('the generic loaders return the name AND the bytes for a name that exists', async () => {
    const loaded = await loadSessions('board');
    expect(loaded?.name).toBe('board');
    expect(loaded?.payload).toBeTypeOf('object');
  });

  test('the name-scoped predicates refuse a name they do not carry', () => {
    expect(usageWants('?fixture=usage')).toBe(true);
    expect(usageWants('?fixture=nope')).toBe(false);
    expect(settingsWants('?fixture=settings')).toBe(true);
    expect(settingsWants('?fixture=nope')).toBe(false);
    expect(compactionWants('?fixture=compaction')).toBe(true);
    expect(compactionWants('?fixture=nope')).toBe(false);
    expect(teamsWants('?fixture=hero')).toBe(true);
    expect(teamsWants('?fixture=nope')).toBe(false);
  });

  test('the payload lookups resolve only their own file name', () => {
    expect(fixtureModels('models')).toBe('models');
    expect(fixtureModels('nope')).toBeNull();
    expect(fixtureModels(null)).toBeNull();
    expect(fixtureAccounts('accounts')).not.toBeNull();
    expect(fixtureAccounts('nope')).toBeNull();
    expect(fixtureDoctor('doctor')).not.toBeNull();
    expect(fixtureDoctor('nope')).toBeNull();
  });
});

describe('the marker on the page root', () => {
  test('a board fed by a fixture carries the fixture own file name', () => {
    const out = renderToStaticMarkup(h(TurnsBoard, {
      inflight: [], landed: null, summary: null, capture: null, sample: 'board',
    }));
    expect(out).toContain('data-sample="board"');
  });

  test('a board with no fixture carries no marker at all', () => {
    const out = renderToStaticMarkup(h(TurnsBoard, {
      inflight: [], landed: null, summary: null, capture: null,
    }));
    expect(out).not.toContain('data-sample');
  });

  test('the sample chrome and the marker are the same value, so they cannot disagree', () => {
    const withFixture = renderToStaticMarkup(h(TurnsBoard, {
      inflight: [], landed: null, summary: null, capture: null, sample: 'board',
    }));
    const without = renderToStaticMarkup(h(TurnsBoard, {
      inflight: [], landed: null, summary: null, capture: null,
    }));
    expect(withFixture).toContain('sample data');
    expect(without).not.toContain('sample data');
    expect(without).not.toContain('data-sample');
  });
});
