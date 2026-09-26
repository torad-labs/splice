import { headsStore } from './model/store';

export { fetchHeads, fetchLiveTurns, startHeadsPolling, startHead, stopHead, stopTurn, restartHead } from './api';
export {
  headAttention,
  inflightText,
  liveTurnText,
  providerFamily,
  queueAtMax,
  ATTENTION_CAUSES,
  EDGE_WORDS,
  NO_SIGNALS,
  PROVIDER_FAMILIES,
  FAMILY_NAME,
  familyName,
} from './model/derive';
export type {
  AttentionCause,
  HeadAttention,
  HeadSignals,
  HeadState,
  ProviderFamily,
} from './model/derive';
export { LIVE_KINDS } from './model/live';
export type { LiveTurn, LiveTurnsPayload, StopTurnResult } from './model/turns';
export const useHeads = headsStore.use;
