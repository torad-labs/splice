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
import { describe, expect, test } from 'vitest';

import { knobDispositions } from '../src/entities/config';
import { headRows, validateNewHead, withoutHead } from '../src/features/head-edit';
import { KnobRack } from '../src/widgets/knob-form';
import { SOURCE_LABELS } from '../src/widgets/knob-form/strings';
import { dispositions } from '../src/pages/settings/coverage';
import { fixtureConfig, fixtureTopology } from '../src/pages/settings/fixtures/settings';
import { DEFAULT_VIEWS, changedPaths, flattenTopology, knobsForView, setAtPath, toToml, valueAtPath } from '../src/pages/settings/model';
import { ClaudeModeSection, TopologySection } from '../src/pages/settings/sections';
import { KNOB_SOURCE, TOPOLOGY_SOURCES, parseKnobNames, parseSerialNames } from '../src/shared/coverage/denominator';

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
      expect(row, `${knob.key} row`).toContain(`>${SOURCE_LABELS[knob.provenance]}<`);
    }
    // The spread the fixture sets up, asserted by name so a mapping mistake cannot hide. The
    // state file and the runtime layer are both what a save in the console writes, so they read
    // alike; every other layer keeps a word of its own.
    expect(rowOf(rack, 'maxInflight')).toContain('>head override<');
    expect(rowOf(rack, 'usageWarnPct')).toContain('>set in console<');
    expect(rowOf(rack, 'debug')).toContain('>environment<');
    expect(rowOf(rack, 'quotaPoll')).toContain('>set in console<');
    // `port` is in the fixture's `[defaults]` layer, so it is the TOML layer and not the enum
    // default; `maxQueued` is in no layer at all, which is the only honest 'default'.
    expect(rowOf(rack, 'port')).toContain('>splice.toml<');
    expect(rowOf(rack, 'maxQueued')).toContain('>default<');
  });

  test('hot and restart-only knobs print different words', () => {
    const live = knobs.filter((knob) => knob.hot).map((knob) => knob.key);
    expect(live).toEqual(['budgetDefaultAction', 'maxInflight', 'maxQueued', 'statuslineGitRoots']);
    expect(rack.split('applies live').length - 1).toBe(live.length);
    expect(rack.split('restart to apply').length - 1).toBe(knobs.length - live.length);
    for (const key of live) expect(rowOf(rack, key)).toContain('applies live');
    expect(rowOf(rack, 'port')).toContain('restart to apply');
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

  test('every scalar of the document gets a field box', () => {
    const leaves = flattenTopology(fixtureTopology);
    expect(leaves.length).toBeGreaterThan(10);
    for (const leaf of leaves) expect(section).toContain(leaf.path);
  });

  test('a key the daemon does not parse is reported, with its path', () => {
    expect(section).toContain('daemon.wibble');
    expect(section).toContain('unknown key');
  });

  test('a topology row prints the file as its provenance, not a config layer', () => {
    // splice.toml is the seventh provenance name (CONTRACTS.md section 2). Before it existed these
    // rows borrowed `defaults table`, which names the [defaults] layer of the runtime config — a
    // different thing from a [heads.<key>] field read out of the topology file.
    expect(section).toContain('splice.toml');
    expect(section).not.toContain('>defaults table<');
  });

  test('the backup note is printed before any write', () => {
    expect(section).toContain('the daemon backs the file up first');
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
    expect(pending).toContain('V4-128 serves /api/topology');
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
    expect(markup).toContain('wrapped');
    expect(markup).not.toContain('separate leaves the vanilla setup untouched');
    expect(markup).toContain('~/.local/share/claude/versions/2.1.257');
    expect(markup).toContain('shadowed the claude command');
    expect(markup).toContain('unwrap');
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
    expect(markup).toContain('real binary');
    expect(markup).toContain('unknown');
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
    expect(separate).toContain('nothing outside');
    expect(separate).toContain('nothing named claude');
    expect(separate).toContain('no mid-session switch');
    // A client-auth head holds no account, so `none` here is an answer, never a missing pool.
    expect(separate).toContain('none');
    // real_binary_path is separate mode's null BY DEFINITION; printing it would be printing a
    // question nobody asked.
    expect(separate).not.toContain('real binary');
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

  test('before the first poll answers, the empty names the route and not a closed row', () => {
    const unread = render(
      h(ClaudeModeSection, { state: null, result: null, onWrap: () => undefined, onUnwrap: () => undefined, busy: false }),
    );
    expect(unread).toContain('GET /api/claude-head');
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
    const topologyNames = new Set(TOPOLOGY_SOURCES.flatMap((file) => parseSerialNames(readSource(file))));
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
    // refuses them in PATCH and the page shows them read-only.
    const readOnly = dispositions
      .filter((entry) => entry.kind !== 'route' && entry.disposition === 'read-only')
      .map((entry) => entry.name);
    expect(readOnly).toHaveLength(9);
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
