import { createResource } from '@shared/lib';
import type { StatusPayload } from '@shared/api';

export const statusStore = createResource<StatusPayload>();
