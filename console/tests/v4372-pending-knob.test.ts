import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { knobDispositions } from '../src/entities/config';
import { fixtureConfig } from '../src/pages/settings/fixtures/settings';
import { scopeNoteOf } from '../src/pages/settings';
import { KnobRack } from '../src/widgets/knob-form';

const config = { ...fixtureConfig, head: 'local', layers: {
  ...fixtureConfig.layers,
  defaults: { ...fixtureConfig.layers.defaults, trace: false },
  perHead: { ...fixtureConfig.layers.perHead, local: { trace: false } },
} };
const trace = knobDispositions(config, 'local').find((knob) => knob.key === 'trace');

function row(topology: Record<string, unknown>): string {
  if (trace === undefined) throw new Error('the daemon no longer reports its trace knob');
  const html = renderToStaticMarkup(createElement(KnobRack, {
    dispositions: [trace], pending: [], busyKey: null, onSave: () => undefined, perHead: true,
    scopeNote: (knob) => scopeNoteOf(knob, config, topology, 'local', true),
  }));
  return html.slice(html.indexOf('data-knob="trace"'));
}

describe('a restart-only head override in Settings', () => {
  test('shows the file and running trace values beside one restart link', () => {
    const html = row({ heads: { local: { overrides: { trace: 'true' } } } });
    expect(html).toContain('On in splice.toml, Off running; starts after restart.');
    expect(html).toContain('href="#/needs-you"');
    expect(html).toContain('Restart to apply');
  });

  test('equal file and running values show neither a false pending note nor a restart link', () => {
    const html = row({ heads: { local: { overrides: { trace: 'false' } } } });
    expect(html).not.toContain('Off running');
    expect(html).not.toContain('href="#/needs-you"');
  });
});
