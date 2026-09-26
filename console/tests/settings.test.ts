// SETTINGS (row M2-05). The page is forms over three sources, and the things worth proving are
// the ones an operator would be misled by if they were wrong: that every knob the daemon reports
// is on the page, that each one says where its value came from and whether saving it does anything
// now, that a bad topology edit is refused in words, and that the Claude head's two modes are
// distinguished by what they DO and not by a label.
//
// CONTRACTS.md section 4: a .ts test holds no JSX (TS1161), so elements are built with
// createElement and asserted on the markup react-dom/server returns.
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, test, vi } from 'vitest';

import { knobDispositions } from '../src/entities/config';
import { headRows, validateNewHead, withoutHead } from '../src/features/head-edit';
import { KnobRack } from '../src/widgets/knob-form';
import { SOURCE_LABELS } from '../src/widgets/knob-form/strings';
import { dispositions } from '../src/pages/settings/coverage';
import { fixtureConfig, fixtureTopology } from '../src/pages/settings/fixtures/settings';
import { DEFAULT_VIEWS, changedPaths, flattenTopology, knobsForView, parseList, setAtPath, toToml, topologyTables, valueAtPath, withHeadOverride } from '../src/pages/settings/model';
import { draftAfterKnobSave, saveGlobalKnob } from '../src/pages/settings';
import { saveTopology } from '../src/entities/topology';
import { ClaudeModeSection, TopologySection } from '../src/pages/settings/sections';
import { KNOB_SOURCE, TOPOLOGY_MANIFEST, parseKnobNames, parseTopologyManifest, topologyLeaves } from '../src/shared/coverage/denominator';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const readSource = (relative: string): string => readFileSync(path.join(repoRoot, relative), 'utf8');

const h = createElement;
const render = (element: Parameters<typeof renderToStaticMarkup>[0]): string => renderToStaticMarkup(element);

/** The markup of one knob's row: from its own data-knob to the next one. */
function rowOf(html: string, key: string): string {
  const start = html.indexOf(`data-knob="${key}"`);
  if (start === -1) return '';
  const next = html.indexOf('data-knob="', start + 1);
  return html.slice(start, next === -1 ? html.length : next);
}

const knobs = knobDispositions(fixtureConfig, 'claudex');
const rack = render(h(KnobRack, { dispositions: knobs, pending: [], busyKey: null, onSave: () => undefined }));

describe('settings: the knob form', () => {
  test('every knob the daemon reports appears in the markup', () => {
    // The FIXTURE is compared to Knob.kt, not to a number: this test's claim is about what the
    // daemon reports, so a fixture that lags the daemon must fail HERE rather than quietly prove
    // the claim over a stale sample. It read 45 while the daemon had 47 (f9e19d00) and passed.
    expect(knobs).toHaveLength(parseKnobNames(readSource(KNOB_SOURCE)).length);
    expect(rack.split('data-knob="').length - 1).toBe(knobs.length);
    for (const knob of knobs) {
      expect(rack).toContain(`data-knob="${knob.key}"`);
    }
  });

  test('each knob prints where its value came from, in the operator\'s words', () => {
    for (const knob of knobs) {
      const row = rowOf(rack, knob.key);
      expect(row, `${knob.key} row`).toContain(`aria-label="Source: ${SOURCE_LABELS[knob.provenance]}"`);
    }
    // The spread the fixture sets up, asserted by name so a mapping mistake cannot hide. The
    // state file and the runtime layer are both what a save in the console writes, so they read
    // alike; every other layer keeps a word of its own.
    const source = (key: string) => /aria-label="Source: ([^"]*)"/.exec(rowOf(rack, key))?.[1];
    expect(source('maxInflight')).toBe('Head override');
    expect(source('usageWarnPct')).toBe('Console');
    expect(source('debug')).toBe('Environment');
    expect(source('quotaPoll')).toBe('Console');
    // `port` is in the fixture's `[defaults]` layer, so it is the TOML layer and not the enum
    // default; `maxQueued` is in no layer at all, which is the only honest 'default'.
    expect(source('port')).toBe('TOML');
    expect(source('maxQueued')).toBe('Default');
  });

  test('hot and restart-only knobs print different words', () => {
    const live = knobs.filter((knob) => knob.hot).map((knob) => knob.key);
    expect(live).toEqual(['budgetDefaultAction', 'maxInflight', 'maxQueued', 'statuslineGitRoots']);
    expect(rack.split('aria-label="Applies live"').length - 1).toBe(live.length);
    expect(rack.split('aria-label="Applies on restart"').length - 1).toBe(knobs.length - live.length);
    for (const key of live) expect(rowOf(rack, key)).toContain('aria-label="Applies live"');
    expect(rowOf(rack, 'port')).toContain('aria-label="Applies on restart"');
  });

  test('the live view shows the hot knobs and nothing else', () => {
    expect(knobsForView(knobs, DEFAULT_VIEWS[1])).toHaveLength(4);
    expect(knobsForView(knobs, DEFAULT_VIEWS[2])).toHaveLength(knobs.length - 4);
    expect(knobsForView(knobs, DEFAULT_VIEWS[0])).toHaveLength(knobs.length);
  });
});

describe('settings: the topology section', () => {
  const draftWithTypo = { ...fixtureTopology, daemon: { ...(fixtureTopology.daemon as object), wibble: 1 } };
  const section = render(
    h(TopologySection, {
      state: { path: '~/.config/splice/splice.toml', topology: draftWithTypo, stale: true },
      loaded: fixtureTopology,
      draft: draftWithTypo,
      onDraft: () => undefined,
      onWrite: () => undefined,
      busy: false,
      result: null,
    }),
  );

  test('every value of the document gets a control, under its own table', () => {
    const tables = topologyTables(fixtureTopology);
    // The denominator comes from the document, not from the list: every leaf is in some table.
    const fields = tables.flatMap((table) => table.fields);
    const leafPaths = flattenTopology(fixtureTopology).map((leaf) => leaf.path.replace(/\[\d+\]$/, ''));
    for (const path of leafPaths) expect(fields.map((field) => field.path), path).toContain(path);
    for (const table of tables.filter((t) => t.path.includes('.'))) {
      expect(section).toContain(table.path.slice(table.path.indexOf('.') + 1));
    }
  });

  test('a list of values is one field, not a box per element', () => {
    // claude.share was ten full-width boxes on the live file.
    const share = topologyTables(fixtureTopology).flatMap((t) => t.fields).find((f) => f.path === 'claude.share');
    expect(share?.kind).toBe('list');
    expect(section).toContain('value="settings, mcps"');
    expect(section).not.toContain('claude.share[0]');
    expect(parseList('settings, mcps,, skills ', ['settings'])).toEqual(['settings', 'mcps', 'skills']);
    expect(parseList('1, 2', [3])).toEqual([1, 2]);
  });

  test('a closed-set value is a picker over the daemon\'s values, and a boolean a switch', () => {
    const fields = topologyTables(fixtureTopology).flatMap((t) => t.fields);
    expect(fields.find((f) => f.path === 'providers.codex.dialect')?.kind).toBe('choice');
    expect(fields.find((f) => f.path === 'providers.codex.auth.kind')?.choices).toContain('chatgpt-oauth');
    expect(fields.find((f) => f.path === 'daemon.mcp_hosting')?.kind).toBe('flag');
    expect(fields.find((f) => f.path === 'heads.claudex.port')?.kind).toBe('number');
    // a head's provider picks from the file's own providers
    expect(fields.find((f) => f.path === 'heads.claudex.provider')?.choices).toEqual(['codex']);
    expect(section).toContain('role="combobox"');
    expect(section).toContain('role="switch"');
  });

  test('a key the daemon does not parse is reported, with its path', () => {
    expect(section).toContain('daemon.wibble');
    expect(section).toContain('unknown key');
  });

  test('the file and its restart are said once for the section, not under every value', () => {
    expect(section.split('~/.config/splice/splice.toml').length - 1).toBe(1);
    expect(section).not.toContain('>defaults table<');
    // once, on the stale-file edge this state sets, never once per value
    expect(section.split('>Restart to apply<').length - 1).toBe(1);
  });

  test('the backup note is on hand before any write, behind the mark beside the file', () => {
    expect(section).toContain('Writing backs the file up first');
    expect(section).toContain('aria-label="About writing"');
  });

  test('a pending topology route names the row that will serve it', () => {
    const pending = render(
      h(TopologySection, {
        state: { pending: 'V4-128' },
        loaded: null,
        draft: null,
        onDraft: () => undefined,
        onWrite: () => undefined,
        busy: false,
        result: null,
      }),
    );
    expect(pending).toContain('does not serve splice.toml editing');
    expect(pending).not.toContain('V4-');
  });
});

describe('settings: the document model', () => {
  test('a leaf can be read and written back without touching the original', () => {
    const next = setAtPath(fixtureTopology, 'heads.claudex.port', 3999);
    expect(valueAtPath(next, 'heads.claudex.port')).toBe(3999);
    expect(valueAtPath(fixtureTopology, 'heads.claudex.port')).toBe(3099);
    expect(changedPaths(fixtureTopology, next)).toEqual(['heads.claudex.port']);
  });

  test('an array of tables is addressed by index', () => {
    expect(valueAtPath(fixtureTopology, 'compaction.model[0].model')).toBe('gpt-5.6-sol');
    const next = setAtPath(fixtureTopology, 'compaction.model[0].model', 'gpt-5.6-terra');
    expect(valueAtPath(next, 'compaction.model[0].model')).toBe('gpt-5.6-terra');
  });

  test('the TOML view renders the document as tables', () => {
    const text = toToml(fixtureTopology);
    expect(text).toContain('[daemon]');
    expect(text).toContain('control_port = 3096');
    expect(text).toContain('[heads.claudex]');
    expect(text).toContain('discovery_prefix = "claude"');
  });

  test('a head can be added and removed, and a bad one is refused in words', () => {
    const clash = validateNewHead({ ...{ key: 'x', provider: 'codex', port: '3099', discoveryPrefix: 'claude', pinnedModel: 'm' } }, fixtureTopology);
    expect(clash).toContainEqual({ field: 'port', message: 'port 3099 is already claudex' });
    expect(validateNewHead({ key: 'claudex', provider: 'nope', port: '1', discoveryPrefix: '', pinnedModel: '' }, fixtureTopology)).toHaveLength(4);
    expect(headRows(withoutHead(fixtureTopology, 'claudex'))).toHaveLength(0);
  });
});

describe('settings: a knob save and a Write start from one base (V4-303)', () => {
  // Both PUT the whole topology, from different snapshots: a head's knob save wrote the loaded file
  // plus the override, and a draft holding edits of its own was left as it was, seeded before the
  // override existed, so the next Write PUT that draft and reverted the knob the page had just
  // reported saved.
  afterEach(() => vi.unstubAllGlobals());
  /** A daemon that takes every topology PUT, and the documents it was sent. */
  function daemon(): Record<string, unknown>[] {
    const sent: Record<string, unknown>[] = [];
    vi.stubGlobal('fetch', (_input: unknown, init?: RequestInit): Promise<Response> => {
      sent.push((JSON.parse(String(init?.body)) as { topology: Record<string, unknown> }).topology);
      const ok = { ok: true, restart_required: true, findings: [] };
      return Promise.resolve(new Response(JSON.stringify(ok), { status: 200, headers: { 'content-type': 'application/json' } }));
    });
    return sent;
  }
  const loaded = fixtureTopology;
  const edited = setAtPath(loaded, 'daemon.control_port', 3097);

  test('with a topology edit pending, a knob save and then Write send the edit and the override', async () => {
    const sent = daemon();
    await saveTopology(withHeadOverride(loaded, 'claudex', 'stream_idle_ms', 120000));
    const draft = draftAfterKnobSave(loaded, edited, 'claudex', 'stream_idle_ms', 120000, true);
    if (draft === null) throw new Error('the pending edit was dropped');
    await saveTopology(draft);
    const [knob, write] = sent;
    expect(valueAtPath(knob, 'heads.claudex.overrides.stream_idle_ms')).toBe('120000');
    expect(valueAtPath(write, 'daemon.control_port')).toBe(3097);
    expect(valueAtPath(write, 'heads.claudex.overrides.stream_idle_ms')).toBe('120000');
  });

  test('a knob save that drops an override drops it from the pending draft too', () => {
    const draft = draftAfterKnobSave(loaded, edited, 'claudex', 'effort', null, true);
    expect(valueAtPath(draft ?? {}, 'heads.claudex.overrides.effort')).toBeUndefined();
    expect(valueAtPath(draft ?? {}, 'daemon.control_port')).toBe(3097);
  });

  test('a refused knob save leaves the pending draft as it was', () => {
    expect(draftAfterKnobSave(loaded, edited, 'claudex', 'stream_idle_ms', 120000, false)).toEqual(edited);
  });

  test('with no edit pending, the draft re-seeds from the file the save wrote', () => {
    expect(draftAfterKnobSave(loaded, loaded, 'claudex', 'stream_idle_ms', 120000, true)).toBeNull();
    expect(draftAfterKnobSave(loaded, null, 'claudex', 'stream_idle_ms', 120000, true)).toBeNull();
  });
});

describe('settings: a global knob save says when it failed or was refused (V4-305)', () => {
  // saveGlobal was `void applyConfigPatch(...).finally(...)`, the shape V4-175 removed from the page's
  // other writes: a daemon down or answering non-2xx left an unhandled rejection and a spinner that
  // cleared as if saved, and a 200 whose `rejected` named the key was dropped, so a value the daemon
  // refused read as saved.
  afterEach(() => vi.unstubAllGlobals());
  /** A daemon whose PATCH /api/config answers `patch`, and whose GET answers the sample config. */
  function daemon(patch: { status: number; body: unknown } | 'down'): void {
    vi.stubGlobal('fetch', (_input: unknown, init?: RequestInit): Promise<Response> => {
      if (init?.method === 'PATCH' && patch === 'down') return Promise.reject(new TypeError('Failed to fetch'));
      const answer = init?.method === 'PATCH' && patch !== 'down' ? patch : { status: 200, body: fixtureConfig };
      return Promise.resolve(new Response(JSON.stringify(answer.body), { status: answer.status, headers: { 'content-type': 'application/json' } }));
    });
  }
  const applied = { applied: {}, restart_required: [], targets: [], persisted: 'state', rejected: {} };

  test('a PATCH that fails says so in its own words', async () => {
    daemon({ status: 503, body: { error: 'the daemon is shutting down' } });
    expect(await saveGlobalKnob('streamIdleMs', 120000)).toBe('the daemon is shutting down');
  });

  test('a daemon that does not answer says so', async () => {
    daemon('down');
    expect(await saveGlobalKnob('streamIdleMs', 120000)).toMatch(/not answering/i);
  });

  test("a 200 whose rejected names the key prints the daemon's sentence", async () => {
    daemon({ status: 200, body: { ...applied, rejected: { streamIdleMs: 'streamIdleMs must be at least 1000' } } });
    expect(await saveGlobalKnob('streamIdleMs', 12)).toBe('streamIdleMs must be at least 1000');
  });

  test("the rack prints a knob's refusal under that knob as a fault, and under no other", () => {
    const reason = 'usageWarnPct must be between 1 and 100';
    const knobs = knobDispositions(fixtureConfig);
    const html = render(h(KnobRack, {
      dispositions: knobs, pending: [], busyKey: null, onSave: () => undefined,
      faultOf: (knob: { key: string }) => (knob.key === 'usageWarnPct' ? reason : null),
    }));
    expect(rowOf(html, 'usageWarnPct')).toMatch(new RegExp(`role="alert"[^>]*aria-label="${reason}"`));
    expect(html.split(reason)).toHaveLength(3); // the fault's label and its text, in that one row
  });

  test('a save the daemon applied prints nothing', async () => {
    daemon({ status: 200, body: applied });
    expect(await saveGlobalKnob('streamIdleMs', 120000)).toBeNull();
  });
});

describe('settings: the Claude head modes', () => {
  // V4-175: these arms used to build their own payload and assert against it, which is why they
  // were green while the page rendered blanks — the fixture WAS the denominator. Every field below
  // is now the daemon's, pinned against it by WebuiContractTest's "claude-head payload matches
  // ClaudeHeadPayload" arm; if the two drift again, the Kotlin build goes red first.
  const logins = {
    count: 2,
    selected: 'work',
    labels: ['personal', 'work'],
    constraint: 'one login per Claude head at a time, chosen at session launch; no mid-session switch',
  };

  test('wrapped is the daemon mode string, and the card prints the binary an unwrap restores', () => {
    const markup = render(
      h(ClaudeModeSection, {
        state: {
          mode: 'wrapped',
          resolves_to: '~/.local/share/splice/splice-launch',
          shim_path: '~/.local/share/splice/splice-launch',
          real_binary_path: '~/.local/share/claude/versions/2.1.257',
          claude_logins: logins,
        },
        result: null,
        onWrap: () => undefined,
        onUnwrap: () => undefined,
        busy: false,
      }),
    );
    // The mutant this arm exists for: `wrap` instead of `wrapped` reads as `separate` on screen,
    // which told the operator their claude command was untouched while the shim was installed.
    expect(markup).toContain('>Wrapped<');
    expect(markup).not.toContain('Leaves claude on PATH alone');
    expect(markup).toContain('~/.local/share/claude/versions/2.1.257');
    expect(markup).toContain('shims claude');
    expect(markup).toContain('>Unwrap<');
  });

  test('a wrapped head with no readable state says so rather than hiding the row', () => {
    const markup = render(
      h(ClaudeModeSection, {
        state: {
          mode: 'wrapped',
          resolves_to: '~/.local/share/splice/splice-launch',
          shim_path: '~/.local/share/splice/splice-launch',
          real_binary_path: null,
          claude_logins: logins,
        },
        result: null,
        onWrap: () => undefined,
        onUnwrap: () => undefined,
        busy: false,
      }),
    );
    expect(markup).toContain('>Real binary<');
    expect(markup).toContain('>Unknown<');
  });

  test('separate says what it does not touch, and prints the stored logins with the constraint', () => {
    const separate = render(
      h(ClaudeModeSection, {
        state: {
          mode: 'separate',
          resolves_to: null,
          shim_path: '~/.local/share/splice/splice-launch',
          real_binary_path: null,
          claude_logins: { count: 0, selected: null, labels: [], constraint: logins.constraint },
        },
        result: null,
        onWrap: () => undefined,
        onUnwrap: () => undefined,
        busy: false,
      }),
    );
    // what separate leaves alone, and what it still shares by default (Marlin, 2026-09-25: it was
    // said to touch nothing, while ten items and its sessions are shared with ~/.claude)
    expect(separate).toContain('Leaves claude on PATH alone; shares ~/.claude setup and sessions by default.');
    expect(separate).not.toContain('touches nothing');
    expect(separate).toContain('>Not found<');
    expect(separate).toContain('no mid-session switch');
    // A client-auth head holds no account, so `None` here is an answer, never a missing pool.
    expect(separate).toContain('>None<');
    // real_binary_path is separate mode's null BY DEFINITION; printing it would be printing a
    // question nobody asked.
    expect(separate).not.toContain('Real binary');
  });

  test("wrap's two backup paths are printed, and only wrap carries them", () => {
    const base = {
      mode: 'wrapped' as const,
      resolves_to: '~/.local/share/splice/splice-launch',
      shim_path: '~/.local/share/splice/splice-launch',
      real_binary_path: '~/.local/share/claude/versions/2.1.257',
      claude_logins: logins,
    };
    const wrapped = render(
      h(ClaudeModeSection, {
        state: base,
        result: {
          ok: true,
          ...base,
          settings_backup_path: '~/.claude/settings.json.splice-wrap-backup-1000',
          claude_json_backup_path: '~/.claude/.claude.json.splice-wrap-backup-1000',
        },
        onWrap: () => undefined,
        onUnwrap: () => undefined,
        busy: false,
      }),
    );
    expect(wrapped).toContain('~/.claude/settings.json.splice-wrap-backup-1000');
    expect(wrapped).toContain('~/.claude/.claude.json.splice-wrap-backup-1000');

    // Unwrap consumes the backups and its answer carries none, so no empty backup rows appear.
    const unwrapped = render(
      h(ClaudeModeSection, {
        state: { ...base, mode: 'separate', real_binary_path: null },
        result: { ok: true, ...base, mode: 'separate', real_binary_path: null },
        onWrap: () => undefined,
        onUnwrap: () => undefined,
        busy: false,
      }),
    );
    expect(unwrapped).not.toContain('backup files');
  });

  test('before the first poll answers, the empty says it is waiting, naming no route and no row', () => {
    const unread = render(
      h(ClaudeModeSection, { state: null, result: null, onWrap: () => undefined, onUnwrap: () => undefined, busy: false }),
    );
    expect(unread).toContain('Waiting for the daemon to answer');
    expect(unread).not.toContain('/api/');
    expect(unread).not.toContain('V4-129');
  });
});

describe('settings: the coverage manifest', () => {
  test('the page dispositions every knob and topology key it owns', () => {
    const knobsDeclared = dispositions.filter((entry) => entry.kind === 'knob');
    const topologyDeclared = dispositions.filter((entry) => entry.kind === 'topology');
    // The expectation is the PARSED denominator, not a number. These read 45 and 58 until
    // 2026-09-18, and when the daemon grew two activity knobs (f9e19d00) the pin did not catch
    // the gap — it WAS the gap, failing on arithmetic beside a wall built precisely so no count
    // is hand-held. A set also catches the other direction, which a length cannot: a name
    // declared here that the source never declared is how this manifest once carried 60 topology
    // keys for a 58-key source, found by hand-diffing at the time.
    const knobNames = new Set(parseKnobNames(readSource(KNOB_SOURCE)));
    // V4-312: every field splice.toml parses, from the manifest Topology's serializer is walked into.
    const topologyNames = new Set(topologyLeaves(parseTopologyManifest(readSource(TOPOLOGY_MANIFEST))));
    expect(new Set(knobsDeclared.map((entry) => entry.name))).toEqual(knobNames);
    expect(new Set(topologyDeclared.map((entry) => entry.name))).toEqual(topologyNames);
    expect(new Set(dispositions.map((entry) => entry.name)).size).toBe(dispositions.length);

    // The names the two FEATURES sections call read-only, and no others: 7 since the settings copy
    // pass (2026-09-24) found the four MCP host limits and the four ChatGPT/Grok login knobs were
    // declared read-only while the rack edited them, and the daemon reads every one of them after a
    // restart (15 before that). KNOBS AND TOPOLOGY KEYS ONLY: the sentence this pin
    // enforces is about those two vocabularies, and counting routes into it made the number answer
    // a different question than the one it is named for — V4-175 moved /api/claude-head off
    // `pending` onto a read-only status read and the arithmetic went red for a correct manifest.
    // 17 since v0.4.0 prompt-review: WIRE_TAP and TRACE are head-only (Knob.headOnly), so the daemon
    // refuses them in PATCH and the page shows them read-only. 3 since V4-312: the three dialect and
    // three prompt-mode values were names only because the old parse counted every @SerialName; they
    // are a field's values now, held to the pickers by coverage.test.ts, which leaves the two head-only
    // knobs and the retired quirk.
    const readOnly = dispositions
      .filter((entry) => entry.kind !== 'route' && entry.disposition === 'read-only')
      .map((entry) => entry.name);
    expect(readOnly).toHaveLength(3);
    for (const entry of dispositions) {
      if (entry.disposition === 'read-only' || entry.disposition === 'excluded') expect(entry.reason).toBeTruthy();
      if (entry.disposition === 'pending') expect(entry.where).toBeTruthy();
    }
  });

  test('every knob the daemon reports has a disposition here', () => {
    const declared = new Set(dispositions.filter((entry) => entry.kind === 'knob').map((entry) => entry.name));
    // The console's form is keyed by the config key; the manifest by the enum entry name, so the
    // two are compared through the fixture's own keys rather than by string equality.
    expect(declared.has('PORT')).toBe(true);
    expect(Object.keys(fixtureConfig.effective)).toHaveLength(knobs.length);
  });
});
