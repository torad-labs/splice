import { createResource } from '@shared/lib';
import type { LogsPayload } from '@shared/api';

export const logsStore = createResource<LogsPayload>();
