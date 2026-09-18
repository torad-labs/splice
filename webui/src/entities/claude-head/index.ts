import { claudeHeadStore } from './model/store';

export {
  fetchClaudeHead,
  startClaudeHeadPolling,
  unwrapClaudeHead,
  wrapClaudeHead,
} from './api';
export { CLAUDE_HEAD_MODES, PENDING_CLAUDE_HEAD } from './model/types';
export type {
  ClaudeHeadActionResult,
  ClaudeHeadMode,
  ClaudeHeadPayload,
  ClaudeHeadState,
} from './model/types';
export const useClaudeHead = claudeHeadStore.use;
