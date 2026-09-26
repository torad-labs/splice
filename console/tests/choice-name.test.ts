// S10: EVERY CHOICE IS NAMED BY ITS LABEL. The box only pointed at its label when the caller passed
// an `id`, so the logs head and lines boxes, the doctor playground's head picker and team compose's
// head and session pickers were comboboxes with no accessible name: a screen reader announced
// "combobox, codex" and never said what was being chosen. What is pinned here is the name a reader
// would get, resolved from the markup the way the accessibility tree resolves it (aria-labelledby
// to the element's text), for the component in both shapes and for the call sites that rendered
// nameless. The playground's picker sits behind a Reveal, which a static render never opens; it is
// the same component and the id-less case below is its case.
//
// A `.ts` test cannot hold JSX (TS1161), so elements are built with React.createElement and
// asserted against renderToStaticMarkup's string.
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { TeamCompose } from '../src/features/team-compose';
import { LogsBoard } from '../src/pages/logs';
import { Choice } from '../src/shared/controls';

const h = React.createElement;
const render = (el: React.ReactElement): string => renderToStaticMarkup(el);
const escape = (text: string): string => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** The accessible name of every combobox in the markup: its aria-label, or the text of each element
 *  its aria-labelledby names, joined. An id that resolves to nothing contributes nothing, so a
 *  dangling reference reads as the empty name it is. */
function comboboxNames(markup: string): string[] {
  return [...markup.matchAll(/<[a-z]+\b[^>]*\brole="combobox"[^>]*>/g)].map(([tag]) => {
    const label = /\baria-label="([^"]*)"/.exec(tag)?.[1];
    if (label !== undefined) return label.trim();
    const ids = /\baria-labelledby="([^"]*)"/.exec(tag)?.[1]?.split(/\s+/) ?? [];
    return ids
      .map((id) => new RegExp(`<[a-z]+\\b[^>]*\\bid="${escape(id)}"[^>]*>([^<]*)<`).exec(markup)?.[1] ?? '')
      .join(' ')
      .trim();
  });
}

const OPTIONS = [{ value: 'claudex', label: 'claudex' }, { value: 'codex', label: 'codex' }];
const choice = (over: Record<string, unknown> = {}) =>
  h(Choice, { label: 'head', value: 'codex', options: OPTIONS, onChange: () => undefined, ...over });

describe('a Choice is named by its label', () => {
  test('with no id', () => {
    expect(comboboxNames(render(choice()))).toEqual(['head']);
  });

  test('with an id, which the box still carries', () => {
    const out = render(choice({ id: 'knob-mode' }));
    expect(comboboxNames(out)).toEqual(['head']);
    expect(out).toContain('id="knob-mode"');
  });

  test('two id-less choices in one render do not share a label', () => {
    const out = render(h('div', null, choice({ label: 'head' }), choice({ label: 'lines' })));
    expect(comboboxNames(out)).toEqual(['head', 'lines']);
  });

  test('the label stays printed: naming it did not hide it', () => {
    expect(render(choice())).toMatch(/<span class="myx-choice-label"[^>]*>head<\/span>/);
  });
});

describe('the call sites that rendered nameless are named', () => {
  test('the logs rail: head and lines as named groups, tag and level as named choices', () => {
    const out = render(h(LogsBoard, {
      payload: { key: 'claudex', path: '/home/user/.splice/logs/daemon.log', lines: [] },
      filter: { head: null, level: null, substring: '' },
      follow: true,
      appended: 0,
      reset: false,
      tags: ['claudex', 'daemon'],
      levels: ['error'],
      head: 'claudex',
      tail: 200,
      heads: [{ key: 'claudex', label: 'claudex' }],
    }));
    // the head and the tail length are button groups now (a mark per head, a segmented tail), so
    // their name is the group's; the two that stayed choices are named comboboxes
    expect(comboboxNames(out)).toEqual(['Tag', 'Level']);
    expect(out).toMatch(/role="group" aria-label="Head"/);
    expect(out).toMatch(/role="group" aria-label="Lines"/);
  });

  test('team compose: every slot picker', () => {
    const out = render(h(TeamCompose, {
      heads: [{ value: 'claudex', label: 'claudex' }],
      sessions: [{ value: 'sid-1', label: 'gs-backend-builder' }],
    }));
    const names = comboboxNames(out);
    expect(names.length).toBeGreaterThan(0);
    expect(names.every((name) => name.length > 0), names.join(' | ')).toBe(true);
  });
});
