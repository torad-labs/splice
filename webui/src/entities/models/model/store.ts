import { createResource } from '@shared/lib';
import type { ModelsPayload } from '@shared/api';

export const modelsStore = createResource<ModelsPayload>();
