import { createResource } from '@shared/lib';
import type { EconomicsPayload } from '@shared/api';

export const economicsStore = createResource<EconomicsPayload>();
