// The Claude head's mode: read it, install or remove the wrap shim. A refusal is a 409 whose sentence is the whole answer, so a
// write rejects with it and the caller prints it. The route is served, so a failed read is a failure and not a pending row.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from './client';
import { read } from './queries';
import { awaitRefetch } from './refetch';
import type { ClaudeHeadActionResult, ClaudeHeadPayload } from '../types/claude-head';

export const claudeHeadKey = ['claude-head'] as const;
export const CLAUDE_HEAD_POLL_MS = 30_000;

export const useClaudeHead = () => useQuery(read<ClaudeHeadPayload>(claudeHeadKey, '/api/claude-head', { refetchInterval: CLAUDE_HEAD_POLL_MS }));

/** Wrap and unwrap are two verbs with different side effects, not a toggle; both read the card again so the mode shown is the daemon's. */
function useClaudeHeadAction(action: 'wrap' | 'unwrap') {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => request<ClaudeHeadActionResult>(`/api/claude-head/${action}`, { method: 'POST' }),
    // Awaited: the card's mode is the daemon's, so the confirm closes on the re-read card.
    onSettled: () => awaitRefetch(client, [claudeHeadKey]),
  });
}
export const useWrapClaudeHead = () => useClaudeHeadAction('wrap');
export const useUnwrapClaudeHead = () => useClaudeHeadAction('unwrap');
