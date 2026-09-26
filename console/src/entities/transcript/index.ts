import { transcriptStore } from './model/store';

export { loadMoreTranscript, loadTranscript, PENDING_TRANSCRIPT, searchedOf } from './api';
export { advanceCursor, openCursor } from './model/cursor';
export type { CursorAdvance } from './model/cursor';
export type {
  TranscriptCursor,
  TranscriptMessage,
  TranscriptMissing,
  TranscriptPage,
  TranscriptRole,
  TranscriptSlice,
  TranscriptState,
} from './model/types';
export const useTranscript = transcriptStore.use;
