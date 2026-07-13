import { createResource } from '@shared/lib';
import type { AuthPayload } from '@shared/api';

export const authStore = createResource<AuthPayload>();
