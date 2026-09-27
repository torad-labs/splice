import { useEffect, useState } from 'react';
import { fetchTranscriptPolicy } from './api';
import type { Resource } from '@shared/lib';
import { boardEdgesStore, registryForView, sessionEdgesStore, sessionHistoryStore, sessionRegistryStore, sessionStore } from './model/store';
import type { SessionsPayload } from './model/types';

export {
  initSession,
  unlock,
  fetchBoardEdges,
  fetchSessions,
  fetchSessionHistory,
  fetchSessionEdges,
  fetchResumeRecipe,
  startBoardEdgesPolling,
  startSessionsPolling,
} from './api';
export {
  availabilityCounts,
  groupKeyOf,
  groupSessions,
  latestPeer,
  nameForAddress,
  peerLabel,
  sessionKey,
  sessionLabel,
  timeline,
  UNATTRIBUTED,
} from './model/derive';
export type { AvailabilityCounts, GroupBy, SessionGroup, SessionTimeField, Timeline, TimelineBucket, TimelineOptions } from './model/derive';
export { UNKNOWN_HEAD } from './model/types';
export type {
  BoardEdgesPayload,
  EdgeDirection,
  ResumeRecipe,
  SessionAvailability,
  SessionEdge,
  SessionEdgesPayload,
  SessionHistoryPayload,
  SessionHistoryRead,
  SessionRepo,
  SessionRoute,
  SessionRow,
  SessionsPayload,
} from './model/types';
export { LIVE_KINDS } from './model/live';
export const useSession = sessionStore;
export function useSessionRegistry<U>(selector: (state: Resource<SessionsPayload>) => U): U {
  const registry = sessionRegistryStore.use((state) => state);
  const [verdict, setVerdict] = useState<'pending' | 'off' | 'on'>('pending');
  useEffect(() => {
    let active = true;
    void fetchTranscriptPolicy()
      .then((read) => { if (active) setVerdict(read); })
      .catch(() => { if (active) setVerdict('pending'); });
    return () => { active = false; };
  }, []);
  return selector(registryForView(registry, verdict));
}
export const useSessionHistory = sessionHistoryStore.use;
export const useSessionEdges = sessionEdgesStore.use;
export const useBoardEdges = boardEdgesStore.use;
