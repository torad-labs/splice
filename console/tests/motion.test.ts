// MOTION (row M3-02). The gestures are fired by a diff and paired by a registry, and those two
// layers are pure on purpose: what a gesture fires ON, and whether two events are one movement, are
// the decisions that go wrong silently. The animation itself is CSS and is checked by looking at
// the thing; these tests check the judgement underneath it.
import { describe, expect, test } from 'vitest';

import { diffKeys, keysOf } from '../src/shared/motion';
import { createHandoffRegistry, handoffTransform } from '../src/shared/motion';
import type { Handoff, Rect } from '../src/shared/motion';

const rect = (x: number, y: number, width = 400, height = 32): Rect => ({ x, y, width, height });

/** A registry with the media query pinned, so the tests never depend on the machine's setting. */
const moving = () => createHandoffRegistry(() => false);
const still = () => createHandoffRegistry(() => true);

function collector(): { handoffs: Handoff[]; apply: (handoff: Handoff) => void } {
  const handoffs: Handoff[] = [];
  return { handoffs, apply: (handoff) => handoffs.push(handoff) };
}

describe('the key diff', () => {
  test('classifies enter, leave and stay between two key lists', () => {
    const diff = diffKeys(['a', 'b', 'c'], ['b', 'c', 'd']);
    expect(diff.entered).toEqual(['d']);
    expect(diff.left).toEqual(['a']);
    expect(diff.stayed).toEqual(['b', 'c']);
  });

  test('a strip that keeps its key is neither an arrival nor a departure', () => {
    // The whole point of diffing KEYS: a poll hands the bay a fresh array of fresh objects every
    // time, and comparing anything else would call every row new on every tick.
    const diff = diffKeys(['a', 'b'], ['a', 'b']);
    expect(diff).toEqual({ entered: [], left: [], stayed: ['a', 'b'] });
  });

  test('the first paint is every row entering at once', () => {
    // Which is exactly why the bay guards the first commit: without the guard, a page would print
    // its whole rack in on load.
    expect(diffKeys([], ['a', 'b']).entered).toEqual(['a', 'b']);
    expect(diffKeys(['a', 'b'], []).left).toEqual(['a', 'b']);
  });

  test('order is kept: entered follows the new list, left follows the old one', () => {
    expect(diffKeys(['a', 'b', 'c'], ['c', 'x', 'a', 'y']).entered).toEqual(['x', 'y']);
    expect(diffKeys(['a', 'b', 'c'], ['c', 'x', 'a', 'y']).left).toEqual(['b']);
  });

  test('a child with no key is positional, and says so', () => {
    expect(keysOf([{ key: 'a' }, { key: null }, { key: null }])).toEqual(['a', '#1', '#2']);
  });
});

describe('the hand-off registry', () => {
  test('pairs a leave and an enter into ONE hand off', () => {
    const registry = moving();
    const { handoffs, apply } = collector();

    registry.leave('strip-1', rect(100, 40));
    registry.enter('strip-1', rect(900, 300), apply);

    expect(handoffs).toHaveLength(1);
    expect(handoffs[0]?.id).toBe('strip-1');
    expect(handoffs[0]?.from).toEqual(rect(100, 40));
    expect(handoffs[0]?.to).toEqual(rect(900, 300));
    expect(registry.pairedCount()).toBe(1);
  });

  test('pairs in either commit order, because which bay commits first is not ours to choose', () => {
    const registry = moving();
    const { handoffs, apply } = collector();

    // The entering bay commits first: its arrival waits for the departure, and the departure
    // completes the pair when it lands.
    registry.enter('strip-1', rect(900, 300), apply);
    expect(handoffs).toHaveLength(0);
    registry.leave('strip-1', rect(100, 40));
    expect(handoffs).toHaveLength(1);
    expect(handoffs[0]?.dx).toBe(100 - 900);
  });

  test('a leave nobody claims is not a hand off', () => {
    const registry = moving();
    const { handoffs, apply } = collector();

    registry.leave('gone', rect(100, 40));
    registry.endFrame();
    registry.enter('arrived', rect(900, 300), apply);

    expect(handoffs).toHaveLength(0);
    expect(registry.pairedCount()).toBe(0);
  });

  test('an enter nobody pairs with is not a hand off either, even after the frame', () => {
    const registry = moving();
    const { handoffs, apply } = collector();

    registry.enter('arrived', rect(900, 300), apply);
    registry.endFrame();
    registry.leave('arrived', rect(100, 40));

    expect(handoffs).toHaveLength(0);
  });

  test('one departure pairs once: two bays cannot both claim the same strip', () => {
    const registry = moving();
    const first = collector();
    const second = collector();

    registry.leave('strip-1', rect(0, 0));
    registry.enter('strip-1', rect(100, 100), first.apply);
    registry.enter('strip-1', rect(200, 200), second.apply);

    expect(first.handoffs).toHaveLength(1);
    expect(second.handoffs).toHaveLength(0);
    expect(registry.pairedCount()).toBe(1);
  });

  test('reduced motion disables the registry entirely', () => {
    const registry = still();
    const { handoffs, apply } = collector();

    registry.leave('strip-1', rect(100, 40));
    registry.enter('strip-1', rect(900, 300), apply);
    // Neither order pairs, and nothing is held for a later frame either.
    registry.enter('strip-2', rect(900, 300), apply);
    registry.leave('strip-2', rect(100, 40));

    expect(handoffs).toHaveLength(0);
    expect(registry.pairedCount()).toBe(0);
  });
});

describe('the flip transform', () => {
  test('translates to where the strip came from and scales to the size it had', () => {
    const moved = handoffTransform(rect(100, 40, 400, 32), rect(900, 300, 200, 64));
    expect(moved).toEqual({ dx: -800, dy: -260, sx: 2, sy: 0.5 });
  });

  test('a strip that lands the same size where it started does not move at all', () => {
    expect(handoffTransform(rect(10, 10), rect(10, 10))).toEqual({ dx: 0, dy: 0, sx: 1, sy: 1 });
  });

  test('a measurement that did not happen is refused rather than divided by', () => {
    // A hidden or not-yet-laid-out node measures zero; scaling by it would be Infinity.
    const nothing = handoffTransform(rect(0, 0), rect(0, 0, 0, 0));
    expect(nothing.sx).toBe(1);
    expect(nothing.sy).toBe(1);
  });
});
