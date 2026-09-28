import { afterEach, expect, test, vi } from 'vitest';
import { pollDuringLogin } from '../src/features/account-login';
import { poll } from '../src/shared/lib';

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

test('an OAuth login keeps polling after its popup hides the console tab', async () => {
  vi.useFakeTimers();
  vi.stubGlobal('document', { visibilityState: 'hidden', addEventListener: vi.fn(), removeEventListener: vi.fn() });
  const pausedRead = vi.fn();
  const stopOrdinary = poll(pausedRead, 2500);
  await vi.advanceTimersByTimeAsync(5000);
  expect(pausedRead).toHaveBeenCalledTimes(1);
  stopOrdinary();

  const read = vi.fn();
  const stop = pollDuringLogin(read, 2500);
  expect(read).toHaveBeenCalledTimes(1);
  await vi.advanceTimersByTimeAsync(5000);
  expect(read).toHaveBeenCalledTimes(3);
  stop();
  await vi.advanceTimersByTimeAsync(2500);
  expect(read).toHaveBeenCalledTimes(3);
});
