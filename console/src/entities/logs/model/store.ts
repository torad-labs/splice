import { createKeyed } from '@shared/lib';
import type { LogsPayload } from '@shared/api';

/** The log tails read, by head: the page asks for the head it shows, never the one tailed before it
 *  (V4-304). */
export const logsStore = createKeyed<string, LogsPayload>();
