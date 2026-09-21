import { createResource } from '@shared/lib';
import type { ControlStatusPayload } from '@shared/api';

export const controlStatusStore = createResource<ControlStatusPayload>();
