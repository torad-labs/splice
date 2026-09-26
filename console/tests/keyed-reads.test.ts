// V4-304: a read made for one key at a time (one head's config, one project, one project's files, one
// session's transcript, one head's log) was held in ONE store with one data and one error, whoever it
// was read for, and every view printed them for the key IT was showing. Each describe opens A, then
// B: B's view prints B's own read or B's own failure, and nothing of A's. The session edges read,
// the row's first, is in session-edges.test.ts; the capture read, keyed first (V4-301), in
// capture.test.ts.
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { fetchConfig } from '../src/entities/config';
import { configStore } from '../src/entities/config/model/store';
import { fetchLogs, setLogHead } from '../src/entities/logs';
import { logsStore } from '../src/entities/logs/model/store';
import { fetchProject, fetchProjectFiles } from '../src/entities/project';
import type { ProjectFilesPayload, ProjectRow } from '../src/entities/project';
import { projectFilesStore, projectStore } from '../src/entities/project/model/store';
import { loadTranscript } from '../src/entities/transcript';
import { transcriptStore } from '../src/entities/transcript/model/store';
import { FleetBoard, overridesOf } from '../src/pages/fleet';
import type { FleetSources } from '../src/pages/fleet';
import { logShown } from '../src/pages/logs';
import { limitsOf, McpBoard } from '../src/pages/mcp';
import { openProjectOf } from '../src/pages/projects/detail';
import { viewedConfig } from '../src/pages/settings';
import { transcriptShown } from '../src/widgets/conversation';
import { filesShown } from '../src/widgets/file-view';
import type { ConfigPayload, HeadStatus } from '../src/shared/api';

type Answer = { status: number; body: unknown } | 'never';

/** A daemon that answers each request by its URL; an unlisted URL fails the test loudly. */
function daemon(answers: Record<string, Answer>): void {
  vi.stubGlobal('fetch', (input: unknown): Promise<Response> => {
    const answer = answers[String(input)];
    if (answer === undefined) throw new Error(`unexpected request ${String(input)}`);
    if (answer === 'never') return new Promise(() => undefined);
    return Promise.resolve(new Response(JSON.stringify(answer.body), { status: answer.status, headers: { 'content-type': 'application/json' } }));
  });
}

const ok = (body: unknown): Answer => ({ status: 200, body });
const failed = (error: string): Answer => ({ status: 500, body: { error } });

afterEach(() => vi.unstubAllGlobals());

// ── one head's config ───────────────────────────────────────────────────────────────────────────

/** A config view as the daemon writes it: `perHead` lists EVERY head's overrides in every view, and
 *  `effective` is the viewed head's own values. */
function view(head: string | undefined, effective: Record<string, number>): ConfigPayload {
  return {
    effective,
    ...(head === undefined ? {} : { head }),
    layers: { defaults: { maxTurns: 1, mcpMaxServers: 8 }, toml: {}, perHead: { alpha: { maxTurns: 5 }, beta: { maxTurns: 9 } }, file: {}, env: {}, runtime: {} },
    restart_required_keys: [],
    source: 'splice.toml',
  };
}

const ALPHA_VIEW = view('alpha', { maxTurns: 5, mcpMaxServers: 8 });
const GLOBAL_VIEW = view(undefined, { maxTurns: 1, mcpMaxServers: 8 });
const BETA_REFUSED = 'head beta is not configured';
const GLOBAL_REFUSED = 'the config file does not parse';

describe('one head\'s config', () => {
  test('the fleet prints the opened head\'s failed read, and never the head read before it as its overrides', async () => {
    daemon({ '/api/config?head=alpha': ok(ALPHA_VIEW), '/api/config?head=beta': failed(BETA_REFUSED) });
    await fetchConfig('alpha');
    await fetchConfig('beta');
    // alpha's view carries beta's override key beside alpha's value: read for beta it printed
    // maxTurns 5, alpha's, as beta's override.
    expect(overridesOf(configStore.get(), 'beta')).toEqual({ knobs: [], error: BETA_REFUSED });
    expect(overridesOf(configStore.get(), 'alpha').knobs.map((knob) => [knob.key, knob.value])).toEqual([['maxTurns', 5]]);
    expect(overridesOf(configStore.get(), 'alpha').error).toBeNull();
  });

  test('settings prints a head\'s failed read in its view, and never the global view under the head\'s name', async () => {
    daemon({ '/api/config': ok(GLOBAL_VIEW), '/api/config?head=beta': failed(BETA_REFUSED) });
    await fetchConfig();
    await fetchConfig('beta');
    const beta = viewedConfig(configStore.get(), 'beta');
    expect(beta.data).toBeNull();
    expect(beta.error).toBe(BETA_REFUSED);
    expect(beta.lastUpdated).toBeNull();
    const global = viewedConfig(configStore.get(), 'global');
    expect(global.data).toEqual(GLOBAL_VIEW);
    expect(global.error).toBeNull();
  });

  test('settings never prints a head\'s view as the global one', async () => {
    daemon({ '/api/config?head=alpha': ok(ALPHA_VIEW), '/api/config': 'never' });
    await fetchConfig('alpha');
    void fetchConfig();
    expect(viewedConfig(configStore.get(), 'global').data).toBeNull();
    expect(viewedConfig(configStore.get(), 'alpha').data).toEqual(ALPHA_VIEW);
  });

  test('the MCP page prints the global read\'s failure, and never a head\'s values as the host limits', async () => {
    daemon({ '/api/config?head=alpha': ok(ALPHA_VIEW), '/api/config': failed(GLOBAL_REFUSED) });
    await fetchConfig('alpha');
    await fetchConfig();
    expect(limitsOf(configStore.get())).toEqual({ limits: [], error: GLOBAL_REFUSED });
  });
});

describe('the panels a config read feeds', () => {
  const h = React.createElement;
  const beta: HeadStatus = {
    key: 'beta', label: 'beta', name: 'beta', port: 3099, authKind: 'chatgpt-oauth', wantVersion: '0.4.0', running: true, healthy: true,
    version: '0.4.0', versionMatch: true, mode: null, gate: null, maxInflight: 4, health: { localOriginErrors: 0, providerErrors: 0 }, pids: [1],
  };
  const sources: FleetSources = {
    auth: null, usage: null, accounts: null, topology: null, catalogs: null, fieldsPending: false, topologyStale: false, landed: [], lastTs: new Map(), overrides: [],
  };
  const fleet = (over: Partial<FleetSources>): string => renderToStaticMarkup(
    h(FleetBoard, { heads: [beta], sources: { ...sources, ...over }, openKey: 'beta', onOpen: () => undefined, nowMs: 0 }),
  );
  const knobs = (markup: string): string => markup.slice(markup.indexOf('>Own settings<')).split('</section>')[0] ?? '';

  test('the fleet\'s opened head prints its failed config read, and no count saying it overrides nothing', () => {
    const failed = knobs(fleet({ overridesError: BETA_REFUSED }));
    expect(failed).toContain(BETA_REFUSED);
    expect(failed).not.toContain('myx-sec-count');
    expect(knobs(fleet({}))).toContain('<span class="myx-sec-count">0</span>');
  });

  test('the MCP page prints its failed config read where the limits go', () => {
    const payload = { hosting: true, servers: {} };
    expect(renderToStaticMarkup(h(McpBoard, { payload, limitsError: GLOBAL_REFUSED }))).toContain(GLOBAL_REFUSED);
    expect(renderToStaticMarkup(h(McpBoard, { payload }))).not.toContain(GLOBAL_REFUSED);
  });
});

// ── one project, and its files ──────────────────────────────────────────────────────────────────

const A = '/work/a';
const B = '/work/b';
const NOT_SEEN = `not a project root splice has seen: ${A}`;

function project(id: string): ProjectRow {
  return {
    id,
    root: id,
    live_sessions: 0,
    teams: 0,
    turns_today: 0,
    cost_today_usd: null,
    day_start: 0,
    last_activity: null,
    compaction: [],
    statusline_roots: [],
  };
}

function files(id: string, text: string): ProjectFilesPayload {
  return { id, files: [{ kind: 'instructions', path: `${id}/CLAUDE.md`, head: null, text }], looked_in: [id] };
}

const path = (id: string): string => `/api/projects/${encodeURIComponent(id)}`;

describe('one project', () => {
  test('its detail never prints the failure of the project opened before it', async () => {
    daemon({ [path(A)]: { status: 404, body: { error: NOT_SEEN } }, [path(B)]: 'never' });
    await fetchProject(A);
    void fetchProject(B);
    expect(openProjectOf(projectStore.get(), project(B), false)).toEqual({ row: project(B), error: null, lastRead: null });
    expect(openProjectOf(projectStore.get(), project(A), false).error).toBe(NOT_SEEN);
  });

  test('its detail prints its own failure, over the list\'s row and not the other project\'s read', async () => {
    daemon({ [path(A)]: ok(project(A)), [path(B)]: failed('the registry is unreadable') });
    await fetchProject(A);
    await fetchProject(B);
    expect(openProjectOf(projectStore.get(), project(B), false)).toEqual({ row: project(B), error: 'the registry is unreadable', lastRead: null });
    expect(openProjectOf(projectStore.get(), project(A), false).lastRead).not.toBeNull();
  });
});

describe('one project\'s files', () => {
  test('never print the files of the project opened before it while its own read is out', async () => {
    daemon({ [`${path(A)}/files`]: ok(files(A, 'only in a')), [`${path(B)}/files`]: 'never' });
    await fetchProjectFiles(A);
    void fetchProjectFiles(B);
    expect(filesShown(projectFilesStore.get(), B)).toEqual({ data: null, error: null });
    expect(filesShown(projectFilesStore.get(), A)).toEqual({ data: files(A, 'only in a'), error: null });
  });

  test('never print the failure of the project opened before it', async () => {
    daemon({ [`${path(A)}/files`]: failed('a is unreadable'), [`${path(B)}/files`]: 'never' });
    await fetchProjectFiles(A);
    void fetchProjectFiles(B);
    expect(filesShown(projectFilesStore.get(), B)).toEqual({ data: null, error: null });
    expect(filesShown(projectFilesStore.get(), A).error).toBe('a is unreadable');
  });
});

// ── one session's transcript ────────────────────────────────────────────────────────────────────

const SESSION_A = 'aaaaaaaa-0000-4000-8000-000000000001';
const SESSION_B = 'bbbbbbbb-0000-4000-8000-000000000002';
const transcript = (id: string): string => `/api/sessions/${encodeURIComponent(id)}/transcript`;

describe('one session\'s transcript', () => {
  test('never prints the conversation of the session opened before it while its own read is out', async () => {
    daemon({
      [transcript(SESSION_A)]: ok({ session_id: SESSION_A, path: '/p/a.jsonl', messages: [{ index: 0, role: 'user', text: 'only in a' }], next: null }),
      [transcript(SESSION_B)]: 'never',
    });
    await loadTranscript(SESSION_A);
    void loadTranscript(SESSION_B);
    expect(transcriptShown(transcriptStore.get(), SESSION_B)).toEqual({ data: null, error: null });
    expect(JSON.stringify(transcriptShown(transcriptStore.get(), SESSION_A).data)).toContain('only in a');
  });

  test('never prints the failure of the session opened before it', async () => {
    daemon({ [transcript(SESSION_A)]: failed('a is unreadable'), [transcript(SESSION_B)]: 'never' });
    await loadTranscript(SESSION_A);
    void loadTranscript(SESSION_B);
    expect(transcriptShown(transcriptStore.get(), SESSION_B)).toEqual({ data: null, error: null });
    expect(transcriptShown(transcriptStore.get(), SESSION_A).error).toBe('a is unreadable');
  });
});

// ── one head's log ──────────────────────────────────────────────────────────────────────────────

describe('one head\'s log', () => {
  test('prints the picked head\'s failed read, and never the lines of the head tailed before it', async () => {
    daemon({
      '/api/logs/alpha?tail=200': ok({ key: 'alpha', path: '/logs/alpha.log', lines: ['only in alpha'] }),
      '/api/logs/beta?tail=200': failed(BETA_REFUSED),
    });
    setLogHead('alpha');
    await fetchLogs();
    setLogHead('beta');
    await fetchLogs();
    expect(logShown(logsStore.get(), 'beta', null)).toEqual({ payload: null, error: BETA_REFUSED, lastRead: null });
    expect(logShown(logsStore.get(), 'alpha', null).payload?.lines).toEqual(['only in alpha']);
  });
});
