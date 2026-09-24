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
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
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
  // A measured basis prints nothing: "measured" is the default reading of any figure, so the word
  // is noise on every row that carries it (design review B5). StripField has read this way since
  // M2-10; Figure matches it here. An unavailable or estimated basis still prints, below.
  test('prints value and unit, and stays silent about a measured basis', () => {
    const out = render(h(Figure, { value: 74, unit: '%', basis: 'measured' }));
    expect(out).toContain('>74<');
    expect(out).toContain('>%<');
    expect(out).not.toContain('>measured<');
  });

  test('names an unavailable basis rather than printing a zero', () => {
    const out = render(h(Figure, { value: 'not reported by provider', basis: 'unavailable' }));
    expect(out).toContain('not reported by provider');
    expect(out).toContain('unavailable');
  });
});

// ---- THE HOLDER EDGE'S WORD READS ON EVERY GROUND IT STANDS ON (M2R-01) ----------------------
//
// The edge label's base rule printed the ROOM's ink, three consumers overrode it for paper, and
// the fourth -- the log tail's Flag, a key on --strip paper -- did not: 1.24:1 in the dark room,
// invisible, and green in every capture because the light theme reads 15.08:1. The fix moved the
// default (the label inherits its ground's ink); this wall resolves the cascade from the sheets
// themselves, for the two grounds a bare HolderEdge stands on -- a key on paper and the room --
// in BOTH themes, and fails when either falls under 4.5:1. The planted arm feeds it the unfixed
// rule and must see the 1.24, so a wall that could not fail would fail here first.
const webui = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const sheet = (rel: string): string => readFileSync(path.join(webui, rel), 'utf8');

/** The last `prop:` declared in the rule whose selector list is exactly `selector`, at a line start. */
function declared(css: string, selector: string, prop: string): string | null {
  const at = css.search(new RegExp(`^${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{`, 'm'));
  if (at < 0) return null;
  const body = css.slice(css.indexOf('{', at) + 1, css.indexOf('}', at));
  const hits = [...body.matchAll(new RegExp(`(?:^|[;\\s])${prop}\\s*:\\s*([^;]+);`, 'g'))];
  return hits.length === 0 ? null : hits[hits.length - 1][1].trim();
}

/** A token's hex in one theme block of tokens.css. */
function token(theme: 'dark' | 'light', name: string): string {
  const tokens = sheet('src/shared/tokens.css');
  const open = theme === 'dark' ? ':root[data-theme="dark"] {' : ':root[data-theme="light"] {';
  const start = tokens.indexOf(open);
  const block = tokens.slice(start, tokens.indexOf('\n}', start));
  const hit = new RegExp(`--${name}:\\s*(#[0-9A-Fa-f]{6})`).exec(block);
  if (hit === null) throw new Error(`--${name} has no hex in the ${theme} block`);
  return hit[1];
}

function contrast(a: string, b: string): number {
  const lum = (hex: string) => {
    const [r, g, bl] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
      .map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
    return 0.2126 * r + 0.7152 * g + 0.0722 * bl;
  };
  const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

/** The label's ink and its ground, for a HolderEdge standing on `host` (a key, or the body). */
function edgeOn(ui: string, theme: 'dark' | 'light', host: { css: string; selector: string }): number {
  const base = declared(ui, '.myx-edge-label', 'color');
  // a host that declares no ink passes body's down (the bay plate did, M3-04), and a host whose
  // fill is `background-color` (the plate keeps its pins in background-image) is read from that
  const hostInk = declared(host.css, host.selector, 'color') ?? declared(sheet('src/app/app.css'), 'body', 'color');
  const hostGround = declared(host.css, host.selector, 'background') ?? declared(host.css, host.selector, 'background-color');
  if (base === null || hostInk === null || hostGround === null) throw new Error('a rule the wall reads is gone');
  const ink = base === 'inherit' ? hostInk : base;
  const name = (value: string) => /var\(--([a-z-]+)\)/.exec(value)?.[1] ?? '';
  return contrast(token(theme, name(ink)), token(theme, name(hostGround)));
}

describe('the holder edge label', () => {
  const KEY = { css: sheet('src/shared/controls/controls.css'), selector: '.myx-key' };
  const ROOM = { css: sheet('src/app/app.css'), selector: 'body' };
  const ui = sheet('src/shared/ui/ui.css');

  for (const theme of ['dark', 'light'] as const) {
    test(`a Flag on its key reads in the ${theme} room`, () => {
      expect(edgeOn(ui, theme, KEY)).toBeGreaterThanOrEqual(4.5);
    });
    test(`a HolderEdge standing in the ${theme} room reads`, () => {
      expect(edgeOn(ui, theme, ROOM)).toBeGreaterThanOrEqual(4.5);
    });
  }

  test('the wall can fail: the unfixed rule puts the Flag at 1.24:1 in the dark room', () => {
    const unfixed = ui.replace(/(^\.myx-edge-label\s*\{[^}]*?)color:\s*inherit;/m, '$1color: var(--ink);');
    expect(unfixed).not.toBe(ui);
    expect(edgeOn(unfixed, 'dark', KEY)).toBeLessThan(1.3);
  });

  // THE BAY'S HEAD PLATE (M3-04). The cascade detector's scan found the third paper ground an edge
  // stands on: the compaction bay's grey `sample data` edge on the plate, which declared no ink, so
  // the label inherited body's --ink at 1.14:1 in the dark room.
  const PLATE = { css: ui, selector: '.myx-bay-head' };
  for (const theme of ['dark', 'light'] as const) {
    test(`a HolderEdge on a bay's head plate reads in the ${theme} room`, () => {
      expect(edgeOn(ui, theme, PLATE)).toBeGreaterThanOrEqual(4.5);
    });
  }
  test('the wall can fail: a plate that declares no ink puts the edge at 1.14:1 in the dark room', () => {
    const unfixed = ui.replace(/(^\.myx-bay-head\s*\{[^}]*?)\n\s*color:\s*var\(--strip-ink\);/m, '$1');
    expect(unfixed).not.toBe(ui);
    expect(edgeOn(unfixed, 'dark', { css: unfixed, selector: '.myx-bay-head' })).toBeLessThan(1.2);
  });
});

// THE CHOICE'S LABEL READS ON THE ROOM IT STANDS ON (M3-04). The edge defect run the other way:
// `.myx-choice-label` printed --strip-ink-mute, a PAPER ink, while the label is a sibling of the paper
// box in the choice's column, so on the logs head (Choice's one consumer) it stood on the room at
// 2.67:1 in the dark. It now inherits; resolved from the sheets, both themes, and the planted arm
// feeds it the paper ink back and must see the 2.67.
describe("the choice's label", () => {
  const controls = sheet('src/shared/controls/controls.css');
  const app = sheet('src/app/app.css');
  const onRoom = (css: string, theme: 'dark' | 'light'): number => {
    const own = declared(css, '.myx-choice-label', 'color');
    const room = declared(app, 'body', 'color');
    const ground = declared(app, 'body', 'background') ?? declared(app, 'body', 'background-color');
    if (own === null || room === null || ground === null) throw new Error('a rule the wall reads is gone');
    const ink = own === 'inherit' ? room : own;
    const name = (value: string) => /var\(--([a-z-]+)\)/.exec(value)?.[1] ?? '';
    return contrast(token(theme, name(ink)), token(theme, name(ground)));
  };
  for (const theme of ['dark', 'light'] as const) {
    test(`reads on the ${theme} room`, () => {
      expect(onRoom(controls, theme)).toBeGreaterThanOrEqual(4.5);
    });
  }
  test('the wall can fail: the paper ink puts it at 2.67:1 in the dark room', () => {
    const unfixed = controls.replace(/(^\.myx-choice-label\s*\{[^}]*?)color:\s*inherit;/m, '$1color: var(--strip-ink-mute);');
    expect(unfixed).not.toBe(controls);
    expect(onRoom(unfixed, 'dark')).toBeLessThan(2.8);
  });
});

// The log tail's head is strip paper without being a .myx-strip, so the Figure beside the Flag
// took ui.css's room ink: `15 new lines` measured 1.15:1 (value) and 1.98:1 (unit) in the dark room,
// in the same capture that showed the Flag. Resolved from the sheets like the edge above.
describe('the log tail head prints its count in paper ink', () => {
  const ui = sheet('src/shared/ui/ui.css');
  const lt = sheet('src/widgets/log-tail/log-tail.css');
  const name = (value: string) => /var\(--([a-z-]+)\)/.exec(value)?.[1] ?? '';
  const onHead = (css: string, theme: 'dark' | 'light', part: 'value' | 'unit') => {
    const ink = declared(css, `.myx-lt-head .myx-fig-${part}`, 'color') ?? declared(ui, `.myx-fig-${part}`, 'color');
    const ground = declared(css, '.myx-lt-head', 'background');
    if (ink === null || ground === null) throw new Error('a rule the wall reads is gone');
    return contrast(token(theme, name(ink)), token(theme, name(ground)));
  };

  for (const theme of ['dark', 'light'] as const) {
    for (const part of ['value', 'unit'] as const) {
      test(`the count's ${part} reads in the ${theme} room`, () => {
        expect(onHead(lt, theme, part)).toBeGreaterThanOrEqual(4.5);
      });
    }
  }

  test('the wall can fail: without the head rule the count falls back to room ink, 1.15:1', () => {
    const unfixed = lt.replace(/^\.myx-lt-head \.myx-fig-value[^\n]*\n/m, '');
    expect(unfixed).not.toBe(lt);
    expect(onHead(unfixed, 'dark', 'value')).toBeLessThan(1.2);
  });
});

// HEALTH'S SIZE REACHES ITS WORD (M3-04). The health cell's only text is its holder edge's label, and
// `.myx-edge-label` sets its own --text-1, so the cell's --text-4 sized an empty box and the hero gate
// read the cap at 9.3px against the comp's 12.4. The word's size resolves from the sheets here.
describe("the rule's health word takes the cell's size", () => {
  const ui = sheet('src/shared/ui/ui.css');
  const rule = sheet('src/widgets/rule/rule.css');
  const wordSize = (css: string) => {
    const own = declared(css, '.myx-rule-health .myx-edge-label', 'font-size') ?? declared(ui, '.myx-edge-label', 'font-size');
    // an inherited size resolves up the tree: the cell, then the band the cells share
    return own === 'inherit'
      ? declared(css, '.myx-rule-health', 'font-size') ?? declared(css, '.myx-rule', 'font-size')
      : own;
  };

  test('the word prints at the cell size, --text-4', () => {
    expect(wordSize(rule)).toBe('var(--text-4)');
  });

  test('the wall can fail: without the rule the edge label keeps --text-1', () => {
    const unfixed = rule.replace(/^\.myx-rule-health \.myx-edge-label[^\n]*\n/m, '');
    expect(unfixed).not.toBe(rule);
    expect(wordSize(unfixed)).toBe('var(--text-1)');
  });
});

// The rule's connection and pending cells declared --ink-mute for the whole cell, written for the
// age beside the word. Once the edge label took its ground's ink, the STATE WORD inherited that
// mute (11.03 -> 6.93:1 dark): passing AA, and still wrong, because the word is the colorblind
// fallback. The word's ink must resolve to the room's full --ink, whatever the cell declares.
describe('the rule prints its state words in the room ink', () => {
  const ui = sheet('src/shared/ui/ui.css');
  const rule = sheet('src/widgets/rule/rule.css');
  const labelInk = (css: string) => {
    const base = declared(ui, '.myx-edge-label', 'color');
    if (base !== 'inherit') return base;
    // the two cells share one rule, whose selector list starts with the connection cell
    return declared(css, '.myx-rule-connection,\n.myx-rule-pending', 'color') ?? declared(sheet('src/app/app.css'), 'body', 'color');
  };

  test('reconnecting, live, off and restart pending print in --ink', () => {
    expect(labelInk(rule)).toBe('var(--ink)');
  });

  test('the wall can fail: a muted cell mutes the word', () => {
    const muted = rule.replace(/(\.myx-rule-pending \{[^}]*gap: var\(--space-2\);)/, '$1\n  color: var(--ink-mute);');
    expect(muted).not.toBe(rule);
    expect(labelInk(muted)).toBe('var(--ink-mute)');
  });
});

