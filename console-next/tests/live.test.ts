import { describe, expect, test } from 'vitest';
import { backoffMs, parseFrames } from '../src/lib/live';

describe('the event frame parser', () => {
  test('reads a frame, keeps the tail of an unfinished one, and counts nothing dropped', () => {
    const first = parseFrames('', 'id: 4\nevent: head.state\ndata: {"head":"x"}\n\nid: 5\nevent: turn.');
    expect(first.frames).toEqual([{ id: 4, kind: 'head.state', data: { head: 'x' } }]);
    expect(first.dropped).toBe(0);
    const second = parseFrames(first.rest, 'start\ndata: {}\n\n');
    expect(second.frames).toEqual([{ id: 5, kind: 'turn.start', data: {} }]);
  });
  test('a heartbeat is not a frame and not a dropped one', () => {
    expect(parseFrames('', ': heartbeat\n\n')).toEqual({ frames: [], rest: '', dropped: 0 });
  });
  test('a frame with no event, no data or non-object data is dropped and counted', () => {
    const parsed = parseFrames('', 'data: {}\n\nevent: x\n\nevent: y\ndata: 3\n\nevent: z\ndata: {bad\n\n');
    expect(parsed.frames).toEqual([]);
    expect(parsed.dropped).toBe(4);
  });
  test('a frame split between a carriage return and its newline still ends', () => {
    const half = parseFrames('', 'event: a\r\ndata: {}\r\n\r');
    expect(half.frames).toEqual([]);
    expect(parseFrames(half.rest, '\n').frames).toHaveLength(1);
  });
});

describe('the reconnect schedule', () => {
  test('doubles from one second to a thirty second cap', () =>
    expect([0, 1, 2, 3, 4, 5, 9].map((n) => backoffMs(n))).toEqual([1000, 2000, 4000, 8000, 16000, 30000, 30000]));
});
