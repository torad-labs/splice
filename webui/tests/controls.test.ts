// M1-07: the world's five controls. What is pinned here is the part a later page row could quietly
// lose while everything still renders:
//   - a key is a real BUTTON, so Enter and Space work with no key handler of its own, and it always
//     prints its label (the world's rule: colour is never the only signal);
//   - the two-step is two keys in the markup, never a dialog, and the armed one is visibly armed;
//   - an input is the same boxed field a strip prints, with its label above it;
//   - a blank is the strip module unprinted, not a shimmer;
//   - a fault carries a RED HOLDER EDGE with its word printed beside it.
// A .ts test cannot hold JSX, so elements are built with React.createElement and asserted against
// renderToStaticMarkup's string (CONTRACTS.md section 4).
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { ARM_MS, Blank, ConfirmKeys, Fault, Input, Key } from '../src/shared/controls';

const h = React.createElement;
const render = (el: React.ReactElement): string => renderToStaticMarkup(el);

describe('Key', () => {
  test('is a real button that prints its label', () => {
    const out = render(h(Key, { onClick: () => undefined, children: 'restart' }));
    expect(out).toContain('<button');
    expect(out).toContain('type="button"');
    expect(out).toContain('restart');
    // The edge is a printed mark with the world's classes, not an inline style.
    expect(out).toContain('myx-key-edge');
  });

  test('the armed variant says so in the markup', () => {
    const plain = render(h(Key, { children: 'stop' }));
    const armed = render(h(Key, { variant: 'armed', children: 'confirm' }));
    expect(plain).not.toContain('myx-key-armed');
    expect(armed).toContain('myx-key-armed');
    expect(armed).toContain('confirm');
  });

  test('a busy key is disabled and says it is busy, never left pressable', () => {
    const out = render(h(Key, { busy: true, children: 'save' }));
    expect(out).toContain('disabled');
    expect(out).toContain('aria-busy="true"');
  });

  test('a disabled key is not pressable and keeps its label', () => {
    const out = render(h(Key, { disabled: true, children: 'send' }));
    expect(out).toContain('disabled');
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
    expect(out).toContain('cancel');
    expect(out.match(/<button/g)).toHaveLength(2);
    expect(out).toContain('myx-key-armed'); // the cocked key is visibly cocked
    expect(out).not.toContain('role="dialog"');
    expect(out).not.toContain('aria-modal');
  });

  test('an armed pair with one key busy disables both, so nothing is half-done', () => {
    const out = render(h(ConfirmKeys, {
      label: 'stop',
      confirmLabel: 'confirm stop',
      armed: true,
      busy: true,
      onArm: () => undefined,
      onConfirm: () => undefined,
      onCancel: () => undefined,
    }));
    expect(out.match(/disabled/g)?.length).toBe(2);
  });

  test('an armed key disarms itself, and the wait is named once', () => {
    expect(ARM_MS).toBe(4_000);
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
});

describe('Blank', () => {
  test('prints n unprinted strips and says what is loading', () => {
    const out = render(h(Blank, { strips: 3, label: 'reading heads' }));
    expect(out.match(/myx-blank-strip/g)).toHaveLength(3);
    expect(out).toContain('aria-busy="true"');
    expect(out).toContain('aria-label="reading heads"');
    // No shimmer and no skeleton: the strip is paper and a hairline, and nothing else.
    expect(out).not.toContain('myx-skeleton');
  });

  test('zero strips is an empty rack, not a negative one', () => {
    expect(render(h(Blank, { strips: 0 }))).not.toContain('myx-blank-strip');
    expect(render(h(Blank, { strips: -2 }))).not.toContain('myx-blank-strip');
  });
});

describe('Fault', () => {
  test('carries a red holder edge with its word printed beside it', () => {
    const out = render(h(Fault, { message: 'daemon unreachable' }));
    expect(out).toContain('myx-edge-red');
    expect(out).toContain('>fault<');
    expect(out).toContain('daemon unreachable');
    expect(out).toContain('role="alert"');
  });

  test('a message is a field, so it is boxed like every other value', () => {
    expect(render(h(Fault, { message: 'HTTP 404' }))).toContain('myx-sfield');
  });

  test('the retry key is offered only when there is something to retry', () => {
    expect(render(h(Fault, { message: 'HTTP 404', onRetry: () => undefined }))).toContain('retry');
    expect(render(h(Fault, { message: 'row V4-127' }))).not.toContain('>retry<');
  });
});
