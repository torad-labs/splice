import { createResource } from '@shared/lib';
import type { AuthPayload } from '@shared/api';
import type { AuthActionState, KeysPayload } from './types';

export const authStore = createResource<AuthPayload>();

/** The last auth WRITE's outcome (switch, login start, login poll, relabel, remove). Separate from
 *  `authStore` because those are not the auth card: the read is the source of truth and a write's
 *  response is a transient outcome the caller shows once. A union with PendingRoute because all
 *  five routes are still v0.4.0 work (V4-132). */
export const authActionStore = createResource<AuthActionState>();

/** GET /api/keys: the key store by name, and which link each api-key head reads now. */
export const keysStore = createResource<KeysPayload>();
