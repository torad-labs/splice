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

  test('each knob prints where its value came from', () => {
    for (const knob of knobs) {
      const row = rowOf(rack, knob.key);
      expect(row, `${knob.key} row`).toContain(`>${knob.provenance}<`);
    }
    // The spread the fixture sets up, asserted by name so a mapping mistake cannot hide.
    expect(rowOf(rack, 'maxInflight')).toContain('>head override<');
    expect(rowOf(rack, 'usageWarnPct')).toContain('>state file<');
    expect(rowOf(rack, 'debug')).toContain('>env<');
    expect(rowOf(rack, 'quotaPoll')).toContain('>patch<');
    // `port` is in the fixture's `[defaults]` layer, so it is the TOML layer and not the enum
    // default; `maxQueued` is in no layer at all, which is the only honest 'default'.
    expect(rowOf(rack, 'port')).toContain('>defaults table<');
    expect(rowOf(rack, 'maxQueued')).toContain('>default<');
  });

  test('hot and restart-only knobs print different words', () => {
    const live = knobs.filter((knob) => knob.hot).map((knob) => knob.key);
    expect(live).toEqual(['maxInflight', 'maxQueued', 'statuslineGitRoots']);
    expect(rack.split('applies live').length - 1).toBe(live.length);
    expect(rack.split('restart to apply').length - 1).toBe(knobs.length - live.length);
    for (const key of live) expect(rowOf(rack, key)).toContain('applies live');
    expect(rowOf(rack, 'port')).toContain('restart to apply');
  });

  test('the live view shows the hot knobs and nothing else', () => {
    expect(knobsForView(knobs, DEFAULT_VIEWS[1])).toHaveLength(3);
    expect(knobsForView(knobs, DEFAULT_VIEWS[2])).toHaveLength(knobs.length - 3);
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
  test('wrap prints the files it rewrites and the shim it installs', () => {
    const markup = render(
      h(ClaudeModeSection, {
        state: {
          mode: 'wrap',
          head: 'claude-splice',
          config_dir: '~/.config/splice/claude-splice',
          auth_kind: 'client',
          claude_on_path: '~/.local/share/claude/versions/2.1.257',
          shim_path: '~/.local/bin/claude',
          rewritten_files: ['~/.claude/settings.json', '~/.claude/.claude.json'],
          backup_paths: ['~/.claude/settings.json.bak'],
          wrap_supported: true,
        },
        onWrap: () => undefined,
        onUnwrap: () => undefined,
        busy: false,
      }),
    );
    expect(markup).toContain('~/.claude/settings.json');
    expect(markup).toContain('~/.local/bin/claude');
    expect(markup).toContain('~/.local/share/claude/versions/2.1.257');
    expect(markup).toContain('shadowing the claude command');
    expect(markup).toContain('unwrap');
  });

  test('separate says what it does not touch, and a pending route names its row', () => {
    const separate = render(
      h(ClaudeModeSection, {
        state: {
          mode: 'separate',
          head: 'claude-splice',
          config_dir: '~/.config/splice/claude-splice',
          auth_kind: 'client',
          claude_on_path: null,
          wrap_supported: false,
        },
        onWrap: () => undefined,
        onUnwrap: () => undefined,
        busy: false,
      }),
    );
    expect(separate).toContain('nothing outside');
    expect(separate).toContain('will not route around that guard');

    const pending = render(
      h(ClaudeModeSection, { state: { pending: 'V4-129' }, onWrap: () => undefined, onUnwrap: () => undefined, busy: false }),
    );
    expect(pending).toContain('V4-129 serves /api/claude-head');
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

    // The eight names the two FEATURES sections call read-only, and no others.
    const readOnly = dispositions.filter((entry) => entry.disposition === 'read-only').map((entry) => entry.name);
    expect(readOnly).toHaveLength(14);
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
