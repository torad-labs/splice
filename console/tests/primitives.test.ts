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

  // S9: every strip used to be a tab stop and a "button", so 6 of the 8 stops on turns pressed
  // nothing. The markup is one half; the handlers are read off the element Strip returns (it holds no
  // hooks, so calling it is rendering it), which is the half a static render cannot show.
  const element = (over: Partial<React.ComponentProps<typeof Strip>> = {}) =>
    Strip({ edge: 'green', edgeLabel: 'ok', ariaLabel: 'head claude', children: null, ...over }) as React.ReactElement<{
      onClick?: () => void;
      onKeyDown?: (event: { key: string; preventDefault: () => void }) => void;
    }>;

  test('a strip that opens is keyboard reachable and opens as a button', () => {
    const out = strip({ onOpen: () => undefined });
    expect(out).toContain('tabindex="0"');
    expect(out).toContain('role="button"');
  });

  test('a strip with nothing to open is no tab stop and no button, and keeps its name', () => {
    const out = strip();
    expect(out).not.toContain('tabindex');
    expect(out).not.toContain('role="button"');
    expect(out).toContain('role="group"');
    expect(out).toContain('aria-label="head claude"');
    expect(element().props.onKeyDown).toBeUndefined();
    expect(element().props.onClick).toBeUndefined();
  });

  test('Enter and Space open an openable strip, and other keys do not', () => {
    let opened = 0;
    const { onKeyDown } = element({ onOpen: () => { opened += 1; } }).props;
    const press = (key: string) => onKeyDown?.({ key, preventDefault: () => undefined });
    press('Enter');
    press(' ');
    press('a');
    expect(opened).toBe(2);
  });

  test('a struck strip keeps its stop but does not open', () => {
    let opened = 0;
    const struck = element({ struck: true, onOpen: () => { opened += 1; } }).props;
    struck.onKeyDown?.({ key: 'Enter', preventDefault: () => undefined });
    expect(struck.onClick).toBeUndefined();
    expect(opened).toBe(0);
    expect(strip({ struck: true, onOpen: () => undefined })).toContain('tabindex="0"');
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

  // The ring used to stand 1px outside the strip, where the rack's scroller cut both sides and the
  // next strip covered the bottom: on turns only the top survived. Measured in a browser (S9); what
  // is pinned here is where it is drawn, inside the strip on a positioned last child.
  test('the focus ring is drawn inside the strip, over its positioned mark', () => {
    const ui = sheet('src/shared/ui/ui.css');
    expect(declared(ui, '.myx-strip:focus-visible', 'outline')).toBe('none');
    const ring = '.myx-strip:focus-visible::after';
    expect(declared(ui, ring, 'position')).toBe('absolute');
    expect(declared(ui, ring, 'outline')).toContain('var(--focus)');
    expect(parseFloat(declared(ui, ring, 'outline-offset') ?? '0')).toBeLessThan(0);
    expect(declared(ui, ring, 'pointer-events')).toBe('none');
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
      .toContain('>Estimated<');
    expect(render(h(StripField, { w: 8, label: 'cost', value: 12, basis: 'measured' })))
      .not.toContain('myx-sfield-basis');
    expect(render(h(StripField, { w: 8, label: 'cost', value: 12 })))
      .not.toContain('myx-sfield-basis');
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
    expect(out).toContain('>Stale<');
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
    expect(box({ hot: true })).not.toContain('applies on restart');
    expect(box()).toContain('applies on restart');
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
    expect(out).toContain('>Unavailable<');
  });
});

// ---- THE HOLDER EDGE'S WORD READS ON EVERY GROUND IT STANDS ON (M2R-01) ----------------------
//
// The edge label's base rule printed the ROOM's ink, three consumers overrode it for paper, and
// the fourth -- the log tail's Flag, a key on --strip paper -- did not: 1.24:1 in the dark room,
// invisible, and green in every capture because the light theme reads 15.08:1. The fix moved the
// default (the label inherits its ground's ink); this wall resolves the cascade from the sheets
// themselves, for the grounds a bare HolderEdge stands on -- a key, the room, a bay's plate -- in
// BOTH themes, and fails when any falls under 4.5:1.
//
// The redesign (DESIGN.md section 8) put one ink family on every ground, so the paper-versus-room
// split this wall was written for cannot recur; the wall stays because the cascade can still hand
// a word a ground's colour. The planted arm does exactly that and must see it vanish.
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

/** A token's hex in one theme block of tokens.css. A name the theme block does not hold is read
 *  through the legacy block (`--strip-ink: var(--fg)`), which maps the old world's names onto the
 *  new tokens until the last old primitive is gone. */
function token(theme: 'dark' | 'light', name: string): string {
  const tokens = sheet('src/shared/tokens.css');
  const open = theme === 'dark' ? ':root[data-theme="dark"] {' : ':root[data-theme="light"] {';
  const start = tokens.indexOf(open);
  const block = tokens.slice(start, tokens.indexOf('\n}', start));
  const hit = new RegExp(`--${name}:\\s*(#[0-9A-Fa-f]{6})`).exec(block);
  if (hit !== null) return hit[1];
  const alias = new RegExp(`^\\s*--${name}:\\s*var\\(--([a-z0-9-]+)\\);`, 'm').exec(tokens);
  if (alias === null) throw new Error(`--${name} has no hex in the ${theme} block and no alias`);
  return token(theme, alias[1]);
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

const name = (value: string) => /var\(--([a-z0-9-]+)\)/.exec(value)?.[1] ?? '';

/** The label's ink and its ground, for a HolderEdge standing on `host` (a key, or the body). */
function edgeOn(ui: string, theme: 'dark' | 'light', host: { css: string; selector: string }): number {
  const base = declared(ui, '.myx-edge-label', 'color');
  // a host that declares no ink passes body's down (the bay plate did, M3-04), and a host whose
  // fill is `background-color` (the plate keeps its pins in background-image) is read from that
  const hostInk = declared(host.css, host.selector, 'color') ?? declared(sheet('src/app/app.css'), 'body', 'color');
  const hostGround = declared(host.css, host.selector, 'background') ?? declared(host.css, host.selector, 'background-color');
  if (base === null || hostInk === null || hostGround === null) throw new Error('a rule the wall reads is gone');
  const ink = base === 'inherit' ? hostInk : base;
  return contrast(token(theme, name(ink)), token(theme, name(hostGround)));
}

describe('the holder edge label', () => {
  const KEY = { css: sheet('src/shared/controls/controls.css'), selector: '.myx-key' };
  const ROOM = { css: sheet('src/app/app.css'), selector: 'body' };
  const ui = sheet('src/shared/ui/ui.css');
  // THE BAY'S HEAD PLATE (M3-04): the compaction bay's grey `sample data` edge stands on it.
  const PLATE = { css: ui, selector: '.myx-bay-head' };

  for (const theme of ['dark', 'light'] as const) {
    test(`a Flag on its key reads in the ${theme} room`, () => {
      expect(edgeOn(ui, theme, KEY)).toBeGreaterThanOrEqual(4.5);
    });
    test(`a HolderEdge standing in the ${theme} room reads`, () => {
      expect(edgeOn(ui, theme, ROOM)).toBeGreaterThanOrEqual(4.5);
    });
    test(`a HolderEdge on a bay's head plate reads in the ${theme} room`, () => {
      expect(edgeOn(ui, theme, PLATE)).toBeGreaterThanOrEqual(4.5);
    });
  }

  test('the wall can fail: a label handed a ground\'s colour vanishes on its key', () => {
    const planted = ui.replace(/(^\.myx-edge-label\s*\{[^}]*?)color:\s*inherit;/m, '$1color: var(--bg-active);');
    expect(planted).not.toBe(ui);
    expect(edgeOn(planted, 'dark', KEY)).toBeLessThan(1.5);
  });
});

// THE CHOICE'S LABEL READS ON THE ROOM IT STANDS ON (M3-04). `.myx-choice-label` once printed a PAPER
// ink while it stood on the room, 2.67:1 in the dark. It now names the room's muted ink; resolved from
// the sheets, both themes, and the planted arm hands it a ground's colour and must see it vanish.
describe("the choice's label", () => {
  const controls = sheet('src/shared/controls/controls.css');
  const app = sheet('src/app/app.css');
  const onRoom = (css: string, theme: 'dark' | 'light'): number => {
    const own = declared(css, '.myx-choice-label', 'color');
    const room = declared(app, 'body', 'color');
    const ground = declared(app, 'body', 'background') ?? declared(app, 'body', 'background-color');
    if (own === null || room === null || ground === null) throw new Error('a rule the wall reads is gone');
    const ink = own === 'inherit' ? room : own;
    return contrast(token(theme, name(ink)), token(theme, name(ground)));
  };
  for (const theme of ['dark', 'light'] as const) {
    test(`reads on the ${theme} room`, () => {
      expect(onRoom(controls, theme)).toBeGreaterThanOrEqual(4.5);
    });
  }
  test('the wall can fail: a ground\'s colour puts it under 1.5:1 in the dark room', () => {
    const planted = controls.replace(/(^\.myx-choice-label\s*\{[^}]*?)color:\s*var\(--fg-muted\);/m, '$1color: var(--bg-active);');
    expect(planted).not.toBe(controls);
    expect(onRoom(planted, 'dark')).toBeLessThan(1.5);
  });
});

// THE LOG TAIL'S BAR READS (M2R-01's second defect: `15 new lines` measured 1.15:1 on the old paper
// head). The bar prints the file's path and, while paused, a neutral badge with the count of lines
// that arrived; both are resolved from the sheets on the grounds they stand on.
describe('the log tail bar prints its path and its count in inks that read', () => {
  const lt = sheet('src/widgets/log-tail/log-tail.css');
  const kit = sheet('src/shared/ui/kit.css');
  const onBar = (css: string, theme: 'dark' | 'light') => {
    const ink = declared(css, '.myx-lt-path', 'color');
    const ground = declared(css, '.myx-lt-bar', 'background');
    if (ink === null || ground === null) throw new Error('a rule the wall reads is gone');
    return contrast(token(theme, name(ink)), token(theme, name(ground)));
  };
  const count = (theme: 'dark' | 'light') => {
    const ink = declared(kit, '.myx-badge-neutral', 'color');
    const ground = declared(kit, '.myx-badge-neutral', 'background');
    if (ink === null || ground === null) throw new Error('a rule the wall reads is gone');
    return contrast(token(theme, name(ink)), token(theme, name(ground)));
  };

  for (const theme of ['dark', 'light'] as const) {
    test(`the path reads on the bar in the ${theme} room`, () => {
      expect(onBar(lt, theme)).toBeGreaterThanOrEqual(4.5);
    });
    test(`the count reads on its badge in the ${theme} room`, () => {
      expect(count(theme)).toBeGreaterThanOrEqual(4.5);
    });
  }

  test('the wall can fail: a path in the bar\'s own ground reads 1:1', () => {
    const planted = lt.replace(/(^\.myx-lt-path \{[^}]*?)color: var\(--fg-muted\);/m, '$1color: var(--bg-raised);');
    expect(planted).not.toBe(lt);
    expect(onBar(planted, 'dark')).toBeLessThan(1.1);
  });
});

// HEALTH'S SIZE REACHES ITS WORD (M3-04). The health cell's only text is its state word, and the
// badge sets its own --text-1, so a cell could size an empty box while the word stayed small. A quiet
// badge inherits its row's size, so the word prints at the strip's --text-2.
describe("the rule's health word takes the cell's size", () => {
  const kit = sheet('src/shared/ui/kit.css');
  const rule = sheet('src/widgets/rule/rule.css');
  const wordSize = (sheetKit: string) => {
    const own = declared(sheetKit, '.myx-badge-quiet', 'font-size') ?? declared(sheetKit, '.myx-badge', 'font-size');
    // an inherited size resolves up the tree to the band the cells share
    return own === 'inherit' ? declared(rule, '.myx-rule', 'font-size') : own;
  };

  test('the word prints at the strip size, --text-2', () => {
    expect(wordSize(kit)).toBe('var(--text-2)');
  });

  test('the wall can fail: without the quiet rule\'s inherit the word keeps the badge\'s --text-1', () => {
    const planted = kit.replace(/(^\.myx-badge-quiet \{[^}]*?) font-size: inherit;/m, '$1');
    expect(planted).not.toBe(kit);
    expect(wordSize(planted)).toBe('var(--text-1)');
  });
});

// The rule's cells print their labels ("last event", "used") in the quiet inks, and the STATE WORD
// beside its dot must not inherit that quiet: the word is the colorblind fallback for the dot, so in
// the strip it prints in the full ink, whatever the quiet badge declares for a table row.
describe('the rule prints its state words in the full ink', () => {
  const kit = sheet('src/shared/ui/kit.css');
  const rule = sheet('src/widgets/rule/rule.css');
  const wordInk = (css: string) => declared(css, '.myx-rule .myx-badge-quiet', 'color') ?? declared(kit, '.myx-badge-quiet', 'color');

  test('reconnecting, live, off and restart pending print in --fg', () => {
    expect(wordInk(rule)).toBe('var(--fg)');
  });

  test('the wall can fail: without the strip\'s rule the word takes the quiet badge\'s muted ink', () => {
    const planted = rule.replace(/^\.myx-rule \.myx-badge-quiet \{[^\n]*\n/m, '');
    expect(planted).not.toBe(rule);
    expect(wordInk(planted)).toBe('var(--fg-muted)');
  });
});
