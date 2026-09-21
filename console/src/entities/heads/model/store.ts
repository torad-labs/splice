import { createResource } from '@shared/lib';
import type { HeadStatus } from '@shared/api';

export const headsStore = createResource<HeadStatus[]>();
