// M1-07 built the world's five controls; M1-13 corrected them and added the two the set was
// missing. What is pinned here is the part a later page row could quietly lose while everything
// still renders:
//   - a key is a real BUTTON, so Enter and Space work with no key handler of its own, and it always
//     prints its label (the world's rule: colour is never the only signal);
//   - an ARMED key and a HOVERED key are not the same thing (m1 design review D1 - the defect this
//     row exists for), asserted on the declarations themselves rather than by eye;
//   - a busy key stays focusable and prints that it is working (D4);
//   - the two-step is two keys in the markup, never a dialog, and it disarms when the operator
//     moves on rather than on a clock (D5);
//   - a fault prints the daemon's whole sentence and wraps (D2);
//   - a choice owns a printed rack of options instead of the OS's popup, a flag is a switch with a
//     printed state word, a blank is the strip module's own structure, and a field label on paper
//     wears the paper's ink (D6, D7).
// A .ts test cannot hold JSX, so elements are built with React.createElement and asserted against
// renderToStaticMarkup's string (CONTRACTS.md section 4). The CSS is read as source and its rules
// are parsed, which is how a declaration-level claim ("these two states differ") can be checked
// without a browser.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import {
  Blank,
  Choice,
  ChoiceList,
  Confirm,
  ConfirmKeys,
  CONTROL_LABELS,
  Fault,
  Flag,
  Input,
  Key,
} from '../src/shared/controls';
import * as controls from '../src/shared/controls';

const h = React.createElement;
const render = (el: React.ReactElement): string => renderToStaticMarkup(el);

const here = fileURLToPath(new URL('../src/shared/controls/', import.meta.url));
const css = readFileSync(`${here}controls.css`, 'utf8');
const source = (file: string): string => readFileSync(`${here}${file}`, 'utf8');

/** Every `selector { declarations }` in a stylesheet, flattened to one space per rule. Comments go
 *  first: a comment above a rule would otherwise ride into the selector, and one inside it into the
 *  property name. */
function rules(sheet: string): Map<string, string> {
  const bare = sheet.replace(/\/\*[\s\S]*?\*\//g, '');
  const found = new Map<string, string>();
  for (const match of bare.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    const body = match[2].trim().replace(/\s+/g, ' ');
    // A grouped rule (`a, b { ... }`) declares its body for each selector in the group, and a later
    // rule for the same selector adds to it, so `declared` reads what the cascade would apply.
    for (const part of match[1].split(',')) {
      const selector = part.trim().replace(/\s+/g, ' ');
      if (selector.startsWith('@') || selector === '') continue;
      const held = found.get(selector);
      found.set(selector, held === undefined ? body : `${held}; ${body}`);
    }
  }
  return found;
}

/** The declarations that apply to an element carrying every one of these selectors. */
function declared(sheet: string, ...selectors: string[]): Record<string, string> {
  const all = rules(sheet);
  const out: Record<string, string> = {};
  for (const selector of selectors) {
    const body = all.get(selector);
    if (body === undefined) continue;
    for (const part of body.split(';')) {
      const [property, ...rest] = part.split(':');
      if (rest.length === 0 || property === undefined) continue;
      out[property.trim()] = rest.join(':').trim();
    }
  }
  return out;
}

describe('Key', () => {
  test('is a real button that prints its label', () => {
    const out = render(h(Key, { onClick: () => undefined, children: 'restart' }));
    expect(out).toContain('<button');
    expect(out).toContain('type="button"');
    expect(out).toContain('restart');
  });

  test('carries no bare coloured edge of its own', () => {
    // D3: the key used to render an aria-hidden span whose whole content was a colour, a second
    // implementation of the holder edge that printed no word.
    expect(render(h(Key, { children: 'stop' }))).not.toContain('myx-key-edge');
    expect(rules(css).has('.myx-key-edge')).toBe(false);
  });

  test('the armed variant carries the holder edge WITH its word', () => {
    const plain = render(h(Key, { children: 'stop' }));
    const armed = render(h(Key, { variant: 'armed', children: 'confirm' }));
    expect(plain).not.toContain('myx-key-armed');
    expect(plain).not.toContain('myx-edge-mark');
    expect(armed).toContain('myx-key-armed');
    expect(armed).toContain('myx-edge-mark');
    expect(armed).toContain(CONTROL_LABELS.armed);
  });

  test('D1: hover and armed are different declarations, not the same two', () => {
    const base = declared(css, '.myx-key');
    const hovered = { ...base, ...declared(css, '.myx-key:hover') };
    const armed = { ...base, ...declared(css, '.myx-key-armed') };
    const properties = new Set([...Object.keys(hovered), ...Object.keys(armed)]);
    const differing = [...properties].filter((property) => hovered[property] !== armed[property]);
    expect(differing.length).toBeGreaterThan(0);
    // The defect was the reverse: every armed declaration was also a hover declaration.
    const armedOnly = Object.entries(armed).filter(([property, value]) => hovered[property] !== value);
    expect(armedOnly.length).toBeGreaterThan(0);
    // And the armed line is not what hover uses: hover touches only the ground, armed the line too.
    expect(Object.keys(declared(css, '.myx-key:hover'))).toEqual(['background']);
    expect(declared(css, '.myx-key-armed')['border-color']).toBe('var(--warn)');
  });

  test('D4: a busy key is not disabled, keeps focus, and prints that it is working', () => {
    const out = render(h(Key, { busy: true, children: 'save' }));
    expect(out).toContain('aria-disabled="true"');
    expect(out).toContain('aria-busy="true"');
    expect(out).not.toMatch(/<button[^>]*\sdisabled/);
    expect(out).toContain(CONTROL_LABELS.busy);
    expect(out).toContain('save');
  });

  test('a disabled key is not pressable and keeps its label', () => {
    const out = render(h(Key, { disabled: true, children: 'send' }));
    expect(out).toMatch(/<button[^>]*\sdisabled/);
    expect(out).toContain('send');
  });

  test('a submit key inside a form keeps its type', () => {
    expect(render(h(Key, { type: 'submit', children: 'apply' }))).toContain('type="submit"');
  });
});

describe('Confirm', () => {
  const keys = (armed: boolean) =>
    render(h(ConfirmKeys, {
      label: 'stop',
      confirmLabel: 'confirm stop',
      armed,
      onArm: () => undefined,
      onConfirm: () => undefined,
      onCancel: () => undefined,
    }));

  test('unarmed it is ONE key with the first label', () => {
    const out = keys(false);
    expect(out).toContain('stop');
    expect(out).not.toContain('confirm stop');
    expect(out).not.toContain('myx-confirm');
    expect(out.match(/<button/g)).toHaveLength(1);
  });

  test('armed it is TWO keys: the second label and a cancel, never a dialog', () => {
    const out = keys(true);
    expect(out).toContain('confirm stop');
    expect(out).toContain('>Cancel<');
    expect(out.match(/<button/g)).toHaveLength(2);
    expect(out).toContain('myx-key-armed'); // the cocked key is visibly cocked
    expect(out).not.toContain('role="dialog"');
    expect(out).not.toContain('aria-modal');
  });

  test('D5: it disarms when the operator moves on, not on a clock', () => {
    const out = render(h(Confirm, { label: 'stop', confirmLabel: 'confirm stop', onConfirm: () => undefined }));
    expect(out).toContain('myx-confirm-hold'); // the wrapper any "focus left" test needs
    expect('ARM_MS' in controls).toBe(false);
    const sheet = source('confirm.tsx');
    expect(sheet).not.toContain('setTimeout');
    expect(sheet).toContain('Escape');
    expect(sheet).toContain('pointerdown');
    expect(sheet).toContain('onBlur');
  });

  test('an armed pair with one key busy is inert, and the pressed key keeps focus', () => {
    const out = render(h(ConfirmKeys, {
      label: 'stop',
      confirmLabel: 'confirm stop',
      armed: true,
      busy: true,
      onArm: () => undefined,
      onConfirm: () => undefined,
      onCancel: () => undefined,
    }));
    const buttons = out.match(/<button[^>]*>/g) ?? [];
    expect(buttons).toHaveLength(2);
    // The key the operator pressed stays in the tab order and says it is working (D4).
    expect(buttons[0]).toContain('aria-disabled="true"');
    expect(buttons[0]).not.toMatch(/\sdisabled/);
    expect(out).toContain(CONTROL_LABELS.busy);
    // The cancel key is genuinely disabled: the action is already in flight, so there is nothing
    // to cancel, and it is not the key the operator's focus is on.
    expect(buttons[1]).toMatch(/\sdisabled/);
  });
});

describe('Input', () => {
  test('is a boxed field with its label above it', () => {
    const out = render(h(Input, { label: 'port', value: '3096', onChange: () => undefined, w: 8 }));
    expect(out).toContain('myx-input-box');
    expect(out).toContain('value="3096"');
    expect(out).toContain('>port<');
    expect(out).toContain('width:8ch');
  });

  test('a numeric field takes the numeric keypad and no spinner', () => {
    const out = render(h(Input, { label: 'effort', value: '3', onChange: () => undefined, numeric: true }));
    // React renders the prop name as written in server markup; HTML attribute names are
    // case-insensitive, so the browser reads this as inputmode either way.
    expect(out.toLowerCase()).toContain('inputmode="numeric"');
    expect(out).not.toContain('type="number"');
  });

  test('an invalid field is marked, and the marking is not a colour alone', () => {
    const out = render(h(Input, { label: 'port', value: 'x', onChange: () => undefined, invalid: true }));
    expect(out).toContain('myx-input-invalid');
  });

  test('D7: its label wears an ink the contrast wall measures on its ground', () => {
    // Measured 1.98:1 for the old --ink-mute over --strip against a 4.5:1 bar. The label now sits on
    // the page ground in --fg-muted, a pair tests/contrast.test.ts holds at 4.5:1 in both themes.
    expect(declared(css, '.myx-input-label').color).toBe('var(--fg-muted)');
  });
});

describe('Blank', () => {
  test('prints n unprinted strips and says what is loading', () => {
    const out = render(h(Blank, { strips: 3, label: 'reading heads' }));
    expect(out.match(/myx-blank-strip/g)).toHaveLength(3);
    expect(out).toContain('aria-busy="true"');
    expect(out).toContain('aria-label="reading heads"');
    expect(out).not.toContain('myx-skeleton');
  });

  test('D7: its height is the strip module itself, not a sum of font sizes', () => {
    const out = render(h(Blank, { strips: 1 }));
    // The same structure a real strip has, so the two cannot disagree.
    expect(out).toContain('myx-strip');
    expect(out).toContain('myx-strip-fields');
    expect(out).toContain('myx-sfield');
    expect(out).toContain('myx-sfield-label');
    expect(out).toContain('myx-sfield-value');
    // And nothing printed on it: strip the two non-breaking spaces and not one glyph is left.
    expect(out.replace(/&nbsp;|\u00a0/g, '')).toBe(
      '<div class="myx-blank" aria-busy="true" role="status">'
      + '<span class="myx-strip myx-blank-strip" aria-hidden="true">'
      + '<span class="myx-strip-fields"><span class="myx-sfield">'
      + '<span class="myx-sfield-label"></span>'
      + '<span class="myx-sfield-value"><span class="myx-sfield-text"></span></span>'
      + '</span></span></span></div>',
    );
    // The old derivation summed font sizes and came up 27% short of the strip it stood in for.
    expect(declared(css, '.myx-blank-strip').height).toBeUndefined();
  });

  test('zero strips is an empty rack, not a negative one', () => {
    expect(render(h(Blank, { strips: 0 }))).not.toContain('myx-blank-strip');
    expect(render(h(Blank, { strips: -2 }))).not.toContain('myx-blank-strip');
  });
});

describe('Fault', () => {
  const long = 'the daemon closed the connection while a turn was streaming: upstream returned 502 after 43s of held bytes, and the head has been marked retryable';

  test('is one alert line: a danger mark that is an icon as well as a colour, then the words', () => {
    const out = render(h(Fault, { message: 'daemon unreachable' }));
    expect(out).toContain('role="alert"');
    expect(out).toMatch(/<svg[^>]*class="myx-fault-icon"/);
    expect(out).toContain('>daemon unreachable<');
    expect(declared(css, '.myx-fault-icon').color).toBe('var(--danger)');
  });

  test('D2: a long message prints whole and wraps, and is never clipped', () => {
    const out = render(h(Fault, { message: long }));
    expect(out).toContain(long); // the whole sentence, not the first 60 characters
    expect(out).not.toContain('myx-sfield-value'); // the ellipsis-clipping cell
    expect(out).toContain('myx-fault-message');
    const message = declared(css, '.myx-fault-message');
    expect(message['white-space']).toBe('normal');
    expect(message['overflow-wrap']).toBe('anywhere');
    expect(message['text-overflow']).toBeUndefined();
    expect(message['overflow']).toBeUndefined();
  });

  test('the retry key is offered only when there is something to retry', () => {
    expect(render(h(Fault, { message: 'HTTP 404', onRetry: () => undefined }))).toContain('>Retry<');
    expect(render(h(Fault, { message: 'row V4-127' }))).not.toContain('>Retry<');
  });
});

describe('Choice', () => {
  const options = [
    { value: '', label: 'all' },
    { value: 'claude', label: 'claude' },
    { value: 'codex', label: 'codex' },
  ];
  const box = (over: Record<string, unknown> = {}) =>
    render(h(Choice, { label: 'tag', value: 'codex', options, onChange: () => undefined, id: 'tag', ...over }));

  test('the shut box prints the current option and says it is shut', () => {
    const out = box();
    expect(out).toContain('role="combobox"');
    expect(out).toContain('aria-expanded="false"');
    expect(out).toContain('>codex<');
    expect(out).toContain(CONTROL_LABELS.open);
  });

  test('D6: no option list is in the markup while shut, and no chevron is drawn', () => {
    const out = box();
    expect(out).not.toContain('role="option"');
    expect(out).not.toContain('myx-choice-options');
    expect(out).not.toContain('<svg');
    expect(out).not.toMatch(/[▲▼▾↓]/); // no glyph standing in for an icon
  });

  test('the open rack prints every option, marks the chosen one with a word, and is in flow', () => {
    const out = render(h(ChoiceList, {
      label: 'tag',
      value: 'codex',
      options,
      active: 1,
      onPick: () => undefined,
    }));
    expect(out.match(/role="option"/g)).toHaveLength(3);
    expect(out.match(/aria-selected="true"/g)).toHaveLength(1);
    expect(out).toContain(CONTROL_LABELS.chosen);
    expect(out).toContain('myx-choice-chosen');
    // Printed in place: no layer, no ladder, nothing to dismiss.
    expect(declared(css, '.myx-choice-options').position).toBeUndefined();
    expect(declared(css, '.myx-choice-options')['z-index']).toBeUndefined();
  });

  test('the chosen option is marked in ink as well as in words', () => {
    // the other options print in the muted ink; the chosen one in full ink, and heavier
    expect(declared(css, '.myx-choice-option').color).toBe('var(--fg-muted)');
    expect(declared(css, '.myx-choice-chosen').color).toBe('var(--fg)');
    expect(declared(css, '.myx-choice-chosen')['font-weight']).toBe('500');
  });
});

// S9: a control flush against a rack's scroller lost the side of its ring drawn past its edge (the
// logs body capture key kept 0 of 24 rows of its left side). Measured in a browser; pinned here is
// that every control that can stand in a rack draws its ring inside its own box.
describe('the focus ring', () => {
  for (const selector of ['.myx-key', '.myx-input-box', '.myx-choice-box', '.myx-choice-option']) {
    test(`${selector} draws its ring inside the box`, () => {
      const ring = declared(css, `${selector}:focus-visible`);
      expect(ring.outline).toContain('var(--focus)');
      // inward by the ring's own width, or any negative length: either keeps the ring in the box
      expect(ring['outline-offset'] ?? '0').toMatch(/^(-\d[\d.]*[a-z]*|calc\(var\(--focus-width\) \* -1\))$/);
    });
  }
});

describe('Flag', () => {
  test('is a switch whose two states print different words', () => {
    const on = render(h(Flag, { on: true, onLabel: 'following', offLabel: 'paused', onChange: () => undefined }));
    const off = render(h(Flag, { on: false, onLabel: 'following', offLabel: 'paused', onChange: () => undefined }));
    expect(on).toContain('role="switch"');
    expect(on).toContain('aria-checked="true"');
    expect(on).toContain('following');
    expect(on).toContain('myx-edge-green');
    expect(off).toContain('aria-checked="false"');
    expect(off).toContain('paused');
    expect(off).toContain('myx-edge-grey');
    // The state survives without its colour: the two words differ.
    expect(on).not.toContain('paused');
    expect(off).not.toContain('>following<');
  });

  test('it is a real button, so Enter and Space toggle it', () => {
    const out = render(h(Flag, { on: false, onLabel: 'on', offLabel: 'off', onChange: () => undefined }));
    expect(out).toContain('<button');
    expect(out).toContain('type="button"');
  });
});

describe('the copy key', () => {
  test('an answer holds only for the value it was given for', () => {
    const copied = { outcome: 'copied' as const, value: 'splice key set A_KEY' };
    expect(controls.copyLabel(copied, 'splice key set A_KEY', 'copy')).toBe('Copied');
    // the detail column opened another head: its command was never copied
    expect(controls.copyLabel(copied, 'splice key set B_KEY', 'copy')).toBe('copy');
    expect(controls.copyLabel({ outcome: 'refused', value: 'x' }, 'x', 'copy')).toBe('Copy by hand');
    expect(controls.copyLabel(null, 'x', 'copy')).toBe('copy');
  });
});
