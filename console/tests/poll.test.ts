// poll(), the one interval every page and the rule read through (S8 of the 2026-09-24 walkthrough):
// a hidden tab kept polling at full rate, 29 requests in 20 s, because poll never looked at the
// document. The contract that stays: the first tick is immediate and the return is a stop. The
// change: a hidden document does not tick, and showing it again ticks at once. The tests run in node,
// so the document is a stub carrying only what poll reads (its visibility and the one event that
// changes it), and time is vitest's fake clock.
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { poll } from '../src/shared/lib';

type Visibility = 'visible' | 'hidden';

/** A document with a visibility the test sets, dispatching `visibilitychange` the way a browser does. */
function stubTab(initial: Visibility = 'visible') {
  const listeners = new Set<() => void>();
  const doc = {
    visibilityState: initial,
    addEventListener: (type: string, fn: () => void) => {
      if (type === 'visibilitychange') listeners.add(fn);
    },
    removeEventListener: (type: string, fn: () => void) => {
      if (type === 'visibilitychange') listeners.delete(fn);
    },
  };
  vi.stubGlobal('document', doc);
  return {
    listeners,
    show: (state: Visibility) => {
      doc.visibilityState = state;
      listeners.forEach((fn) => fn());
    },
  };
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('poll', () => {
  test('without a document it ticks at once, then every interval, until stopped', () => {
    const fn = vi.fn();
    const stop = poll(fn, 1000);
    expect(fn).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(3000);
    expect(fn).toHaveBeenCalledTimes(4);
    stop();
    vi.advanceTimersByTime(5000);
    expect(fn).toHaveBeenCalledTimes(4);
  });

  test('a hidden tab skips its ticks, and ticks at once when it is shown again', () => {
    const tab = stubTab();
    const fn = vi.fn();
    const stop = poll(fn, 2000);
    expect(fn).toHaveBeenCalledTimes(1);

    tab.show('hidden');
    vi.advanceTimersByTime(20_000); // the walkthrough's twenty seconds
    expect(fn).toHaveBeenCalledTimes(1);

    tab.show('visible');
    expect(fn).toHaveBeenCalledTimes(2); // at once, not an interval later
    tab.show('visible'); // a repeated event is not a second catch-up
    expect(fn).toHaveBeenCalledTimes(2);
    vi.advanceTimersByTime(1999);
    expect(fn).toHaveBeenCalledTimes(2); // the interval restarts from the catch-up tick
    vi.advanceTimersByTime(1);
    expect(fn).toHaveBeenCalledTimes(3);

    stop();
    expect(tab.listeners.size).toBe(0);
    tab.show('hidden');
    tab.show('visible');
    vi.advanceTimersByTime(10_000);
    expect(fn).toHaveBeenCalledTimes(3);
  });

  test('a page opened in a hidden tab still loads once, and polls only once it is shown', () => {
    const tab = stubTab('hidden');
    const fn = vi.fn();
    const stop = poll(fn, 2000);
    expect(fn).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(10_000);
    expect(fn).toHaveBeenCalledTimes(1);

    tab.show('visible');
    expect(fn).toHaveBeenCalledTimes(2);
    vi.advanceTimersByTime(2000);
    expect(fn).toHaveBeenCalledTimes(3);
    stop();
  });
});
