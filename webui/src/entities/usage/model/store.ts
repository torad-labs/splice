import { createResource } from '@shared/lib';
import type { UsagePayload } from '@shared/api';

export const usageStore = createResource<UsagePayload>();
