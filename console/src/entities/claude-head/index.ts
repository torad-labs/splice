import { claudeHeadStore } from './model/store';

export {
  fetchClaudeHead,
  startClaudeHeadPolling,
  unwrapClaudeHead,
  wrapClaudeHead,
} from './api';
export { CLAUDE_HEAD_MODES } from './model/types';
export type {
  ClaudeHeadActionResult,
  ClaudeHeadMode,
  ClaudeHeadPayload,
  ClaudeLoginsCard,
} from './model/types';
export const useClaudeHead = claudeHeadStore.use;
