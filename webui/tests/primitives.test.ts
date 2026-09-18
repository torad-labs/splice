// WALLS for the strip world's vocabulary. These primitives are the whole
// console's grammar, so what is pinned here is the part a later page row could
// silently lose: every state keeps a PRINTED label.
//
// The load-bearing one is `cocked and struck still print their label`. This
// world's central claim (brief section 3) is "holder edge color means attention
// state, ALWAYS with a text label on the strip" and "color is never the only
// signal". A refactor that expresses cocked as a tint alone still renders, still
// looks right in a screenshot, and drops the one property that makes the strip
// readable to a colorblind operator — so it is asserted on the markup, not
// eyeballed.
//
// A `.ts` test cannot hold JSX (TS1161), so elements are built with
// React.createElement and asserted against renderToStaticMarkup's string.
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import {
  Bay, Empty, FieldBox, Figure, HolderEdge, Reveal, ScopeInset, Strip, StripField,
} from '../src/shared/ui';

const h = React.createElement;
const render = (el: React.ReactElement): string => renderToStaticMarkup(el);

describe('Strip', () => {
  const strip = (over: Partial<React.ComponentProps<typeof Strip>> = {}) =>
    render(h(Strip, {
      edge: 'green',
      edgeLabel: 'ok',
      ariaLabel: 'head claude',
      children: h(StripField, { w: 12, label: 'name', value: 'claude' }),
      ...over,
    }));

  test('prints its edge label and its aria-label', () => {
    const out = strip();
    expect(out).toContain('>ok<');
    expect(out).toContain('aria-label="head claude"');
  });

  test('is keyboard reachable and opens as a button', () => {
    const out = strip();
    expect(out).toContain('tabindex="0"');
    expect(out).toContain('role="button"');
  });

  test('cocked lifts the edge into attention and STILL prints the label', () => {
    const out = strip({ cocked: true });
    expect(out).toContain('myx-edge-amber');
    expect(out).toContain('>ok<'); // the label is the signal, the amber is the emphasis
  });

  test('cocked never cools a red edge', () => {
    expect(strip({ edge: 'red', cocked: true })).toContain('myx-edge-red');
  });

  test('struck draws the line, greys the edge, and STILL prints the label', () => {
    const out = strip({ struck: true });
    expect(out).toContain('myx-strip-strike');
    expect(out).toContain('myx-edge-grey');
    expect(out).toContain('aria-disabled="true"');
    expect(out).toContain('>ok<');
  });

  test('struck outranks cocked: a disabled strip is never also needs-me', () => {
    const out = strip({ struck: true, cocked: true });
    expect(out).toContain('myx-edge-grey');
    expect(out).not.toContain('myx-edge-amber');
  });

  test('unset states leave no state class behind', () => {
    const out = strip();
    expect(out).not.toContain('aria-disabled');
    expect(out).not.toContain('myx-strip-strike');
    expect(out).not.toContain('myx-strip-selected');
  });
});

describe('HolderEdge', () => {
  test('always prints its label, in every state', () => {
    for (const state of ['green', 'amber', 'red', 'grey'] as const) {
      const out = render(h(HolderEdge, { state, label: 'warn 74%' }));
      expect(out).toContain('warn 74%');
      expect(out).toContain(`myx-edge-${state}`);
    }
  });
});

describe('StripField', () => {
  test('sets a fixed width in ch and prints label and value', () => {
    const out = render(h(StripField, { w: 12, label: 'account', value: 'acct-a' }));
    expect(out).toContain('width:12ch');
    expect(out).toContain('>account<');
    expect(out).toContain('>acct-a<');
  });

  test('prints the basis only when the value is not measured', () => {
    expect(render(h(StripField, { w: 8, label: 'cost', value: 12, basis: 'estimated' })))
      .toContain('estimated');
    expect(render(h(StripField, { w: 8, label: 'cost', value: 12, basis: 'measured' })))
      .not.toContain('measured');
    expect(render(h(StripField, { w: 8, label: 'cost', value: 12 })))
      .not.toContain('measured');
  });
});

describe('Bay', () => {
  test('prints its label and its count', () => {
    const out = render(h(Bay, { label: 'head claude', count: 3 }, h('div', null, 'row')));
    expect(out).toContain('head claude');
    expect(out).toContain('>3<');
  });

  test('renders the honest empty, source and all, when it has no rows', () => {
    const out = render(h(Bay, { label: 'head claude', empty: { text: 'no sessions', source: 'row M2-02' } }));
    expect(out).toContain('no sessions');
    expect(out).toContain('row M2-02');
  });

  test('a bay with rows does not print the empty', () => {
    const out = render(h(Bay, { label: 'head claude', empty: { text: 'no sessions', source: 'row M2-02' } },
      h('div', null, 'row')));
    expect(out).not.toContain('no sessions');
  });
});

describe('ScopeInset', () => {
  test('prints its title and its basis beside the chart', () => {
    const out = render(h(ScopeInset, { title: 'tokens per turn', basis: 'stale', children: h('svg') }));
    expect(out).toContain('tokens per turn');
    expect(out).toContain('stale');
    expect(out).toContain('<svg');
  });
});

describe('FieldBox', () => {
  const box = (props: Record<string, unknown> = {}) =>
    render(h(FieldBox, { label: 'maxInflight', value: '5', provenance: 'defaults table', ...props }));

  test('prints label, value and provenance', () => {
    const out = box();
    expect(out).toContain('maxInflight');
    expect(out).toContain('value="5"');
    expect(out).toContain('defaults table');
  });

  test('the seventh provenance name is the file itself, and prints as written', () => {
    // A topology key does not come from any layer of the runtime config; it comes from splice.toml.
    // The name exists so the topology forms never have to borrow a config layer's word for it.
    const out = box({ provenance: 'splice.toml' });
    expect(out).toContain('splice.toml');
    expect(out).not.toContain('defaults table');
  });

  test('says whether the change needs a restart, in words', () => {
    expect(box({ hot: true })).toContain('applies live');
    expect(box({ hot: true })).not.toContain('restart to apply');
    expect(box()).toContain('restart to apply');
  });

  test('is read-only until it is given somewhere to write', () => {
    // React 19 emits the attribute verbatim (readOnly=""), not the HTML
    // lowercased spelling.
    expect(box()).toContain('readOnly=""');
    expect(box({ onChange: () => {} })).not.toContain('readOnly');
  });
});

describe('Reveal', () => {
  test('prints its label and keeps the children out of the DOM', () => {
    const out = render(h(Reveal, { label: 'show system prompt', children: h('pre', null, 'you are splice') }));
    expect(out).toContain('show system prompt');
    expect(out).not.toContain('you are splice');
  });
});

describe('Empty', () => {
  test('prints what is missing and which source says so', () => {
    const out = render(h(Empty, { text: 'no perf yet', source: 'perf files' }));
    expect(out).toContain('no perf yet');
    expect(out).toContain('perf files');
  });
});

describe('Figure', () => {
  test('prints value, unit and basis as text', () => {
    const out = render(h(Figure, { value: 74, unit: '%', basis: 'measured' }));
    expect(out).toContain('>74<');
    expect(out).toContain('>%<');
    expect(out).toContain('>measured<');
  });

  test('names an unavailable basis rather than printing a zero', () => {
    const out = render(h(Figure, { value: 'not reported by provider', basis: 'unavailable' }));
    expect(out).toContain('not reported by provider');
    expect(out).toContain('unavailable');
  });
});
