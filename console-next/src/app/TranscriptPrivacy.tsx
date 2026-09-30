import { useQuery, useQueryClient } from '@tanstack/react-query';
import type { QueryClient } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';
import { keys } from '../api/queries';
import { request } from '../api/client';
import { teamPanelsKey } from '../api/teams';
import type { ConfigPayload } from '../types/core';

/** How often the switch is read again, so a page in another window follows it: the daemon sends no event for a setting. */
const WATCH_MS = 5_000;

/** The reads that carry what a transcript says. The conversation of a turn, a session's own conversation, what a hand-off handed
 *  over and what a team's messages say are all read from Claude Code's saved transcript, which the Transcript view setting gates. */
export const TRANSCRIPT_READS: readonly (readonly string[])[] = [['conversation'], keys.transcript, keys.edges, teamPanelsKey];

/** The setting changed: a read already on screen must not outlive it. Turning it off empties them at once (what they held is gone
 *  before the daemon answers again), and either way they are read again, so each says what the daemon now says. */
export function followTranscriptView(client: QueryClient, on: boolean): void {
  for (const key of TRANSCRIPT_READS) {
    if (!on) void client.resetQueries({ queryKey: [...key] });
    else void client.invalidateQueries({ queryKey: [...key] });
  }
  void client.invalidateQueries({ queryKey: [...keys.sessions] });
}

/** Watches the Transcript view setting for the whole console and clears what it gates the moment it turns off, in this window
 *  (a save invalidates config, which reads it again now) and in every other one (the poll). Draws nothing. */
export function TranscriptPrivacy() {
  const client = useQueryClient();
  const view = useQuery({
    queryKey: [...keys.config, 'transcript-view'],
    queryFn: () => request<ConfigPayload>('/api/config'),
    select: (config) => config.effective['transcriptView'] === true,
    refetchInterval: WATCH_MS,
    refetchIntervalInBackground: true,
  });
  const seen = useRef<boolean | undefined>(undefined);
  useEffect(() => {
    if (view.data === undefined) return;
    if (seen.current !== undefined && seen.current !== view.data) followTranscriptView(client, view.data);
    seen.current = view.data;
  }, [client, view.data]);
  return null;
}
