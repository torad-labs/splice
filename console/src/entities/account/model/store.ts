import { createResource } from '@shared/lib';
import type { AccountsState } from './types';

/** Every account of every provider (GET /api/accounts). A union with PendingRoute because the
 *  route does not exist yet (V4-132): the store holds the honest empty, never a mocked row. */
export const accountsStore = createResource<AccountsState>();
