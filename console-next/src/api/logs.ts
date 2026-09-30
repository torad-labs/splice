// The log tail. GET /api/logs/{head}?tail=N answers the last N complete lines of the head's log, oldest first,
// and every poll re-sends a window that mostly repeats itself. `useLogFollow` therefore hands the page a
// TailAdvance (lib/logs `advance`): the lines that are new since the last read, and whether this window still
// continues the one on screen.
import { useQuery } from '@tanstack/react-query';
import { useRef } from 'react';
import { request } from './client';
import { advance } from '../lib/logs';
import type { LogsPayload, LogTail, TailAdvance } from '../types/logs';

export const LOG_TAIL_MIN = 10;
export const LOG_TAIL_MAX = 2000;
export const LOG_TAIL_DEFAULT = 200;
export const LOGS_POLL_MS = 5000;

/** The tail the daemon is asked for: never fewer than 10 lines, never more than 2000. */
export const clampTail = (tail: number): number => Math.min(LOG_TAIL_MAX, Math.max(LOG_TAIL_MIN, tail));

export const logsPath = (head: string, tail: number): string => `/api/logs/${encodeURIComponent(head)}?tail=${clampTail(tail)}`;

/**
 * Follow one head's log. No head, no read: the page names one from the daemon's own registry (a guessed head
 * is a read nobody asked for). The data is the latest TailAdvance; the page appends `appended` to what it
 * shows when `dataUpdatedAt` moves, and starts over when `reset` is true. The cursor is held per
 * (head, tail) stream: the first read of a new stream is a first read, not a reset.
 */
export function useLogFollow(head: string | null, tail: number = LOG_TAIL_DEFAULT) {
  const size = clampTail(tail);
  const stream = `${head ?? ''}\n${size}`;
  const held = useRef<{ stream: string; tail: LogTail } | null>(null);
  return useQuery({
    queryKey: ['logs', head ?? '', size],
    enabled: head !== null,
    refetchInterval: LOGS_POLL_MS,
    queryFn: async (): Promise<TailAdvance> => {
      const payload = await request<LogsPayload>(logsPath(head ?? '', size));
      const previous = held.current?.stream === stream ? held.current.tail : null;
      const next = advance(previous, payload);
      held.current = { stream, tail: next.tail };
      return next;
    },
  });
}
