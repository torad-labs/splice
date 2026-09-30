// What the session pages read, and the two things they do to a session: copy how to resume it, stop its turn.
import { infiniteQueryOptions, useInfiniteQuery, useQueries, useQuery } from '@tanstack/react-query';
import { MgmtError, request } from './client';
import { keys, read, useLiveTurns } from './queries';
import type { TurnOf } from '../lib/sessions';
import type { LiveTurnsPayload } from '../types/turns';
import type {
  BoardEdgesPayload,
  ResumeRecipe,
  SessionEdgesPayload,
  SessionHistoryRead,
  TranscriptMessage,
  TranscriptRead,
} from '../types/sessions';

/** The directories the daemon searched, when a 404 is its own answer for a session with no transcript on
 *  disk (the route writes `searched` beside the error); null for any other failure. Read from the
 *  envelope's structure, never by matching its sentence. */
export function searchedOf(err: unknown): string[] | null {
  if (!(err instanceof MgmtError) || err.status !== 404) return null;
  const searched = (err.body as { searched?: unknown } | null)?.searched;
  return Array.isArray(searched) ? searched.filter((dir): dir is string => typeof dir === 'string') : null;
}

const id = encodeURIComponent;

/** One session's hand-offs, oldest first, each with what its sender handed over. Read when the session
 *  opens: a hand-off is history once it happened. */
export const useSessionEdges = (session: string) =>
  useQuery(read<SessionEdgesPayload>(keys.edges, `/api/sessions/${id(session)}/edges`, { refetchInterval: false }));

/** Every live session's hand-offs in one read, keyed by session id. */
export const useBoardEdges = () => useQuery(read<BoardEdgesPayload>(keys.edges, '/api/sessions/edges'));

/** Every durable session by name or repository, a bounded page at a time. A new query is a new list. */
export function useSessionHistory(query: string, enabled: boolean) {
  return useInfiniteQuery({
    queryKey: [...keys.sessions, 'history', query],
    enabled,
    initialPageParam: null as string | null,
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams({ limit: '50', query });
      if (pageParam !== null) params.set('cursor', pageParam);
      return request<SessionHistoryRead>(`/api/sessions/history?${params}`);
    },
    getNextPageParam: (last) => ('state' in last ? undefined : (last.next ?? undefined)),
  });
}

export type TranscriptView =
  | { kind: 'messages'; path: string; messages: TranscriptMessage[] }
  | { kind: 'off'; reason: string }
  | { kind: 'missing'; searched: string[] };

type TranscriptAnswer = TranscriptRead | { missing: string[] };

/** The messages one read holds. */
export const TRANSCRIPT_PAGE = 100;

/** A session's conversation, newest first: the first read is the end of it (`before=end`) and each next read is the cursor the
 *  page before named, so a session opens at what was said last. The transcript view can be off (a state, not an empty
 *  conversation), and a session with no file on disk is the daemon's own 404. */
export const transcriptOptions = (session: string) =>
  infiniteQueryOptions({
    queryKey: [...keys.transcript, session],
    initialPageParam: 'end',
    queryFn: async ({ pageParam }): Promise<TranscriptAnswer> => {
      try {
        return await request<TranscriptRead>(`/api/sessions/${id(session)}/transcript?before=${id(pageParam)}&limit=${TRANSCRIPT_PAGE}`);
      } catch (err) {
        const searched = searchedOf(err);
        if (searched !== null) return { missing: searched };
        throw err;
      }
    },
    getNextPageParam: (last: TranscriptAnswer) => ('state' in last || 'missing' in last ? undefined : (last.earlier ?? undefined)),
    refetchInterval: false,
  });

/** The pages as one conversation, oldest first: the newest page is first in [pages], and each page is oldest first within. */
export function transcriptView(pages: readonly TranscriptAnswer[]): TranscriptView | null {
  const first = pages[0];
  if (first === undefined) return null;
  if ('missing' in first) return { kind: 'missing', searched: first.missing };
  if ('state' in first) return { kind: 'off', reason: first.reason };
  return {
    kind: 'messages',
    path: first.path,
    messages: [...pages].reverse().flatMap((page) => ('messages' in page ? page.messages : [])),
  };
}

export function useTranscript(session: string) {
  const query = useInfiniteQuery(transcriptOptions(session));
  return { ...query, view: transcriptView(query.data?.pages ?? []) };
}

/** What resuming a session on a head does, read when the operator picks the head and never polled. It
 *  rejects with the daemon's sentence, which for a head whose command is not linked names the install
 *  that fixes it. */
export const fetchResume = (session: string, head: string): Promise<ResumeRecipe> =>
  request<ResumeRecipe>(`/api/sessions/${id(session)}/resume?head=${id(head)}`);

/** The daemon's id for the turn a session is running on [head], the one a stop names; null when it
 *  runs none. */
export function useLiveTurnOf(head: string | null, session: string | null): string | null {
  const live = useLiveTurns(head ?? '', head !== null && session !== null);
  return live.data?.turns.find((turn) => turn.session === session && !turn.stopped)?.id ?? null;
}

/** The live turn each session runs, read from its head's live turns: one read per head that has a busy session.
 *  A head not read yet (or none) gives undefined = unknown; a head that runs none for the session gives null. */
export function useTurnOf(heads: readonly string[]): TurnOf {
  const unique = [...new Set(heads)];
  const results = useQueries({
    queries: unique.map((head) => read<LiveTurnsPayload>(keys.liveTurns, `/api/heads/${id(head)}/turns/live`)),
  });
  const byHead = new Map(unique.map((head, i) => [head, results[i]?.data] as const));
  return (row) => {
    const payload = byHead.get(row.head);
    if (payload === undefined) return undefined;
    return payload.turns.find((turn) => turn.session === row.session_id && !turn.stopped) ?? null;
  };
}
