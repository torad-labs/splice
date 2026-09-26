import { compactStore, instructionsStore } from './model/store';

export { fetchCompact, fetchInstructions, startCompactPolling, startInstructionsPolling } from './api';
export type {
  InstructionRule,
  InstructionScope,
  InstructionScopeWire,
  InstructionsState,
  InstructionsWire,
  UnreadInstructions,
} from './model/types';
export const useCompact = compactStore.use;
export const useInstructions = instructionsStore.use;
